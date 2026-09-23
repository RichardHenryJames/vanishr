package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

import static app.vanishr.relay.RelayTypes.*;

@Repository
public class GroupMessages {
    public record Send(@NotNull UUID id, @NotNull UUID epoch, @Positive long revision, @NotNull Expiry expiry,
                       @Positive long expiresAt, @NotNull @Size(min=32,max=65_536) byte[] ciphertext,
                       UUID mediaId, @Size(min=17,max=2_097_168) byte[] media) { }
    public record Message(UUID id, UUID groupId, UUID epoch, long revision, UUID senderId, UUID senderDeviceId,
                          Expiry expiry, long expiresAt, byte[] ciphertext, UUID mediaId) { }
    public record Receipt(UUID id, UUID groupId, UUID senderId, UUID senderDeviceId, Expiry expiry, long expiresAt,
                          String digest, Map<String,String> recipients) { }
    public record Status(UUID id, long expiresAt, int recipients, int delivered, int read, String state) { }
    public record ControlSend(@NotNull UUID id, @NotNull UUID recipientId, @NotNull UUID recipientDeviceId,
                              @NotNull UUID epoch, @Positive long revision, @Positive long expiresAt,
                              @Min(2) @Max(3) int type, @NotNull @Size(min=32,max=65_536) byte[] ciphertext) { }
    public record Control(UUID id, UUID groupId, UUID senderId, UUID senderDeviceId, UUID recipientId, UUID recipientDeviceId,
                          UUID epoch, long revision, long expiresAt, int type, byte[] ciphertext) { }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final DefaultRedisScript<String> send = script("group-send");
    private final DefaultRedisScript<String> ack = script("group-ack");
    private final DefaultRedisScript<String> control = script("group-control");

    public GroupMessages(StringRedisTemplate redis, ObjectMapper json, Clock clock) { this.redis=redis; this.json=json; this.clock=clock; }

    private static DefaultRedisScript<String> script(String name) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/"+name+".lua")); script.setResultType(String.class); return script;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Group serialization failed"); }
    }
    private <Value> Value decode(String value, Class<Value> type) {
        if (value == null) throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
        try { return json.readValue(value,type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid ephemeral group state"); }
    }
    private String checked(String result) {
        if (result == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"relay_unavailable");
        return switch (result) {
            case "FORBIDDEN", "NOT_FOUND" -> throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
            case "CONFLICT" -> throw new ApiException(HttpStatus.CONFLICT,"conflicting_retry");
            case "FULL" -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"group_queue_full");
            case "EXPIRED" -> throw new ApiException(HttpStatus.GONE,"expired");
            default -> result;
        };
    }
    private String key(String type, UUID group, UUID id) { return type+":"+group+":"+id; }
    private Status status(Receipt receipt) {
        int delivered=0; int read=0; int deleted=0;
        for (String state : receipt.recipients().values()) {
            if (state.equals("DELIVERED") || state.equals("READ")) delivered++;
            if (state.equals("READ")) read++;
            if (state.equals("DELETED")) deleted++;
        }
        int total=receipt.recipients().size();
        String state = deleted==total ? "DELETED" : read==total ? "READ" : delivered==total ? "DELIVERED" : "QUEUED";
        return new Status(receipt.id(),receipt.expiresAt(),total,delivered,read,state);
    }

    public Status send(Actor actor, GroupDirectory.Snapshot group, Send request) {
        if (!group.epoch().equals(request.epoch()) || group.revision()!=request.revision()) throw new ApiException(HttpStatus.CONFLICT,"group_changed");
        RelayPolicy.deadline(request.expiry(),request.expiresAt(),clock.instant());
        if ((request.mediaId()==null)!=(request.media()==null)) throw new ApiException(HttpStatus.BAD_REQUEST,"invalid_media_size");
        Map<String,String> recipients = new TreeMap<>();
        for (GroupDirectory.Member member : group.active()) if (!member.userId().equals(actor.userId())) recipients.put(member.deviceId().toString(),"QUEUED");
        if (recipients.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,"group_not_ready");
        Message message = new Message(request.id(),group.id(),request.epoch(),request.revision(),actor.userId(),actor.deviceId(),request.expiry(),request.expiresAt(),request.ciphertext(),request.mediaId());
        Receipt receipt = new Receipt(request.id(),group.id(),actor.userId(),actor.deviceId(),request.expiry(),request.expiresAt(),
                RedisRelay.digest(encode(request).getBytes(StandardCharsets.UTF_8)),recipients);
        return status(decode(checked(redis.execute(send,List.of(key("gm",group.id(),request.id()),key("gr",group.id(),request.id()),
                        "gi:"+group.id(),key("gb",group.id(),request.id())),encode(message),encode(receipt),
                request.media()==null ? "" : Base64.getEncoder().encodeToString(request.media()),Long.toString(clock.millis()))),Receipt.class));
    }

    private List<Receipt> receipts(UUID group) {
        String index="gi:"+group;
        redis.opsForZSet().removeRangeByScore(index,0,clock.millis());
        Set<String> ids=redis.opsForZSet().rangeByScore(index,clock.millis()+1,Double.POSITIVE_INFINITY,0,256);
        if (ids==null || ids.isEmpty()) return List.of();
        List<String> values=redis.opsForValue().multiGet(ids.stream().map(id -> "gr:"+group+":"+id).toList());
        if (values==null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> decode(value,Receipt.class)).toList();
    }

    public List<Message> pending(Actor actor, UUID group) {
        List<Message> messages=new ArrayList<>();
        for (Receipt receipt : receipts(group)) {
            String state=receipt.recipients().get(actor.deviceId().toString());
            if (!"QUEUED".equals(state)) continue;
            String value=redis.opsForValue().get(key("gm",group,receipt.id()));
            if (value!=null) messages.add(decode(value,Message.class));
            if (messages.size()>=32) break;
        }
        return messages;
    }

    public List<Status> statuses(Actor actor, UUID group) {
        return receipts(group).stream().filter(receipt -> receipt.senderDeviceId().equals(actor.deviceId())).map(this::status).toList();
    }

    public Status acknowledge(Actor actor, UUID group, UUID id, String action) {
        return status(decode(checked(redis.execute(ack,List.of(key("gm",group,id),key("gr",group,id),key("gb",group,id)),
                actor.deviceId().toString(),action,Long.toString(clock.millis()))),Receipt.class));
    }

    public byte[] media(Actor actor, UUID group, UUID id) {
        Receipt receipt=decode(redis.opsForValue().get(key("gr",group,id)),Receipt.class);
        String state=receipt.recipients().get(actor.deviceId().toString());
        if (receipt.expiresAt()<=clock.millis() || (!"QUEUED".equals(state) && !(receipt.expiry()==Expiry.VIEW_ONCE && "DELIVERED".equals(state))))
            throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
        String value=redis.opsForValue().get(key("gb",group,id));
        if (value==null) throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
        return Base64.getDecoder().decode(value);
    }

    public void control(Actor actor, GroupDirectory.Snapshot group, ControlSend request) {
        RelayPolicy.deadline(Expiry.HOURS_24,request.expiresAt(),clock.instant());
        if (!group.epoch().equals(request.epoch()) || group.revision()!=request.revision()) throw new ApiException(HttpStatus.CONFLICT,"group_changed");
        GroupDirectory.Member recipient=group.member(request.recipientId());
        if (!recipient.deviceId().equals(request.recipientDeviceId()) || recipient.userId().equals(actor.userId())
                || (!recipient.state().equals("ACTIVE") && !group.ownerId().equals(actor.userId())))
            throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
        Control packet=new Control(request.id(),group.id(),actor.userId(),actor.deviceId(),recipient.userId(),recipient.deviceId(),
                request.epoch(),request.revision(),request.expiresAt(),request.type(),request.ciphertext());
        String encoded=encode(packet);
        checked(redis.execute(control,List.of(key("gc",group.id(),request.id()),key("gcr",group.id(),request.id()),
                        key("gci",group.id(),recipient.deviceId())),"SEND",encoded,RedisRelay.digest(encoded.getBytes(StandardCharsets.UTF_8)),
                Long.toString(clock.millis()),Long.toString(request.expiresAt()),request.id().toString(),recipient.deviceId().toString()));
    }

    public List<Control> controls(Actor actor, UUID group) {
        String index=key("gci",group,actor.deviceId());
        redis.opsForZSet().removeRangeByScore(index,0,clock.millis());
        Set<String> ids=redis.opsForZSet().rangeByScore(index,clock.millis()+1,Double.POSITIVE_INFINITY,0,64);
        if (ids==null || ids.isEmpty()) return List.of();
        List<String> values=redis.opsForValue().multiGet(ids.stream().map(id -> "gc:"+group+":"+id).toList());
        if (values==null) return List.of();
        return values.stream().filter(Objects::nonNull).map(value -> decode(value,Control.class))
                .filter(value -> value.recipientDeviceId().equals(actor.deviceId())).toList();
    }

    public void acknowledgeControl(Actor actor, UUID group, UUID id) {
        checked(redis.execute(control,List.of(key("gc",group,id),key("gcr",group,id),key("gci",group,actor.deviceId())),
                "ACK","","",Long.toString(clock.millis()),"0",id.toString(),actor.deviceId().toString()));
    }
}