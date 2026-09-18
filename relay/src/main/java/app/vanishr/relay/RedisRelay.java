package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

import static app.vanishr.relay.RelayTypes.*;

@Repository
public class RedisRelay {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final DefaultRedisScript<String> enqueue = script("enqueue");
    private final DefaultRedisScript<String> acknowledge = script("acknowledge");
    private final DefaultRedisScript<String> upload = script("upload");
    private final DefaultRedisScript<String> removeMedia = script("remove-media");

    public RedisRelay(StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    private static DefaultRedisScript<String> script(String name) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        script.setResultType(String.class);
        return script;
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Internal serialization failure"); }
    }

    private <Value> Value decode(String value, Class<Value> type) {
        if (value == null) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        try { return json.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid ephemeral state"); }
    }

    private String checked(String result) {
        if (result == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "relay_unavailable");
        return switch (result) {
            case "NOT_FOUND" -> throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
            case "FORBIDDEN" -> throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
            case "CONFLICT" -> throw new ApiException(HttpStatus.CONFLICT, "conflicting_retry");
            case "EXPIRED" -> throw new ApiException(HttpStatus.GONE, "expired");
            default -> result;
        };
    }

    static String digest(byte[] bytes) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException("Digest unavailable"); }
    }

    public Status send(Actor actor, SendRequest request) {
        RelayPolicy.message(request, actor, clock.instant());
        Message message = new Message(request.id(), actor.userId(), actor.deviceId(), request.recipientId(),
                request.recipientDeviceId(), request.expiry(), clock.millis(), request.expiresAt(),
                request.type(), request.ciphertext(), request.mediaId());
        Receipt receipt = new Receipt(message.id(), message.senderId(), message.senderDeviceId(), message.recipientId(),
                message.recipientDeviceId(), message.expiry(), message.expiresAt(), message.mediaId(), State.QUEUED,
                digest(encode(request).getBytes(StandardCharsets.UTF_8)));
        String result = redis.execute(enqueue, List.of("m:" + message.id(), "r:" + message.id(),
                "inbox:" + message.recipientDeviceId(), "outbox:" + actor.deviceId(), "b:" + message.mediaId()),
                encode(message), encode(receipt), Long.toString(clock.millis()));
        return Status.from(decode(checked(result), Receipt.class));
    }

    public List<Message> pending(Actor actor) {
        return indexed("inbox:" + actor.deviceId(), "m:", Message.class).stream()
                .filter(message -> message.recipientDeviceId().equals(actor.deviceId()) && message.expiresAt() > clock.millis()).toList();
    }

    public List<Status> statuses(Actor actor) {
        return indexed("outbox:" + actor.deviceId(), "r:", Receipt.class).stream()
                .filter(receipt -> receipt.senderDeviceId().equals(actor.deviceId())).map(Status::from).toList();
    }

    public List<Status> statuses(Actor actor, List<UUID> ids) {
        if (ids == null) return statuses(actor);
        if (ids.size() > 50) throw new ApiException(HttpStatus.BAD_REQUEST, "too_many_identifiers");
        if (ids.isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(ids.stream().map(id -> "r:" + id).toList());
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> decode(value, Receipt.class))
                .filter(receipt -> receipt.senderDeviceId().equals(actor.deviceId())).map(Status::from).toList();
    }

    private <Value> List<Value> indexed(String index, String prefix, Class<Value> type) {
        redis.opsForZSet().removeRangeByScore(index, 0, clock.millis());
        Set<String> identifiers = redis.opsForZSet().rangeByScore(index, clock.millis() + 1, Double.POSITIVE_INFINITY, 0, 50);
        if (identifiers == null || identifiers.isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(identifiers.stream().map(id -> prefix + id).toList());
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> decode(value, type)).toList();
    }

    public Status acknowledge(Actor actor, UUID id, State state) {
        String result = redis.execute(acknowledge, List.of("m:" + id, "r:" + id), actor.deviceId().toString(), state.name());
        return Status.from(decode(checked(result), Receipt.class));
    }

    public void upload(Actor actor, UUID mediaId, UUID recipientDeviceId, long expiresAt, byte[] ciphertext) {
        RelayPolicy.deadline(Expiry.HOURS_24, expiresAt, clock.instant());
        if (ciphertext.length < 17 || ciphertext.length > RelayPolicy.MAX_MEDIA_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_media_size");
        Media media = new Media(mediaId, actor.deviceId(), recipientDeviceId, expiresAt, null, ciphertext, digest(ciphertext));
        checked(redis.execute(upload, List.of("b:" + mediaId), encode(media), Long.toString(clock.millis())));
    }

    public byte[] download(Actor actor, UUID id) {
        Media media = decode(redis.opsForValue().get("b:" + id), Media.class);
        if (!media.recipientDeviceId().equals(actor.deviceId()) || media.messageId() == null || media.expiresAt() <= clock.millis())
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        if (!Boolean.TRUE.equals(redis.hasKey("m:" + media.messageId())))
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        return media.ciphertext();
    }

    public void deleteMedia(Actor actor, UUID id) {
        checked(redis.execute(removeMedia, List.of("b:" + id), actor.deviceId().toString()));
    }
}