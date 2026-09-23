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
public class ProfileMessages {
    public record Send(@NotNull UUID id, @NotNull UUID recipientId, @NotNull UUID recipientDeviceId,
                       @Positive long expiresAt, @Min(2) @Max(3) int type,
                       @NotNull @Size(min=32,max=65_536) byte[] ciphertext) {
        @Override public String toString() { return "ProfileSend[redacted]"; }
    }
    public record Packet(UUID id, UUID senderId, UUID senderDeviceId, UUID recipientId,
                         UUID recipientDeviceId, long expiresAt, int type, byte[] ciphertext) {
        @Override public String toString() { return "ProfilePacket[redacted]"; }
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final DefaultRedisScript<String> control = new DefaultRedisScript<>();

    public ProfileMessages(StringRedisTemplate redis, ObjectMapper json, Clock clock, AccountDirectory accounts) {
        this.redis=redis; this.json=json; this.clock=clock; this.accounts=accounts;
        control.setLocation(new ClassPathResource("redis/group-control.lua")); control.setResultType(String.class);
    }

    private List<String> keys(UUID device, UUID id) {
        return List.of("pc:"+device+":"+id, "pcr:"+device+":"+id, "pci:"+device);
    }

    private void checked(String result) {
        if ("OK".equals(result)) return;
        throw switch (result == null ? "UNAVAILABLE" : result) {
            case "CONFLICT" -> new ApiException(HttpStatus.CONFLICT,"conflicting_retry");
            case "FULL" -> new ApiException(HttpStatus.TOO_MANY_REQUESTS,"profile_queue_full");
            case "EXPIRED" -> new ApiException(HttpStatus.GONE,"expired");
            case "FORBIDDEN", "NOT_FOUND" -> new ApiException(HttpStatus.NOT_FOUND,"not_found");
            default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"relay_unavailable");
        };
    }

    public void send(Actor actor, Send request) {
        RelayPolicy.deadline(Expiry.HOURS_24,request.expiresAt(),clock.instant());
        if (actor.userId().equals(request.recipientId()) || !accounts.active(request.recipientId(),request.recipientDeviceId()))
            throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
        Packet packet=new Packet(request.id(),actor.userId(),actor.deviceId(),request.recipientId(),
                request.recipientDeviceId(),request.expiresAt(),request.type(),request.ciphertext());
        String encoded;
        try { encoded=json.writeValueAsString(packet); }
        catch (Exception failure) { throw new IllegalStateException("Profile serialization failed"); }
        checked(redis.execute(control,keys(request.recipientDeviceId(),request.id()),"SEND",encoded,
                RedisRelay.digest(encoded.getBytes(StandardCharsets.UTF_8)),Long.toString(clock.millis()),
                Long.toString(request.expiresAt()),request.id().toString(),request.recipientDeviceId().toString(),"64"));
    }

    public List<Packet> pending(Actor actor) {
        String index="pci:"+actor.deviceId();
        redis.opsForZSet().removeRangeByScore(index,0,clock.millis());
        Set<String> ids=redis.opsForZSet().rangeByScore(index,clock.millis()+1,Double.POSITIVE_INFINITY,0,16);
        if (ids==null || ids.isEmpty()) return List.of();
        List<String> values=redis.opsForValue().multiGet(ids.stream().map(id -> "pc:"+actor.deviceId()+":"+id).toList());
        if (values==null) return List.of();
        List<Packet> result=new ArrayList<>();
        for (String value : values) {
            if (value==null) continue;
            Packet packet;
            try { packet=json.readValue(value,Packet.class); }
            catch (Exception failure) { throw new IllegalStateException("Invalid ephemeral profile state"); }
            if (!packet.recipientId().equals(actor.userId()) || !packet.recipientDeviceId().equals(actor.deviceId())) continue;
            if (packet.expiresAt()>clock.millis() && accounts.active(packet.senderId(),packet.senderDeviceId())) result.add(packet);
            else acknowledge(actor,packet.id());
        }
        return result;
    }

    public void acknowledge(Actor actor, UUID id) {
        checked(redis.execute(control,keys(actor.deviceId(),id),"ACK","","",Long.toString(clock.millis()),
                "0",id.toString(),actor.deviceId().toString(),"64"));
    }
}