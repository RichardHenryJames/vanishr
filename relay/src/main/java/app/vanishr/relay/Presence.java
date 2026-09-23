package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static app.vanishr.relay.RelayTypes.Actor;

@Repository
public class Presence {
    public static final long LIFETIME = 12_000;
    public record Peer(@NotNull UUID userId, @NotNull UUID deviceId,
                       @NotNull @Pattern(regexp = "[A-Za-z0-9+/]{44}") String identityKey) { }
    public record Update(@NotNull @Size(max = 128) List<@NotNull @Valid Peer> contacts,
                         UUID typingTo, @Min(0) @Max(5000) long typingForMillis) {
        @Override public String toString() { return "PresenceUpdate[redacted]"; }
    }
    public record Status(Peer peer, long onlineForMillis, long typingForMillis) { }
    record State(Peer owner, List<Peer> contacts, String connectionId, String sessionKey,
                 UUID typingTo, long typingUntil, long expiresAt) {
        @Override public String toString() { return "PresenceState[redacted]"; }
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final AuthService auth;
    private final RealtimeHub realtime;

    public Presence(StringRedisTemplate redis, ObjectMapper json, Clock clock, AccountDirectory accounts,
                    AuthService auth, RealtimeHub realtime) {
        this.redis = redis; this.json = json; this.clock = clock; this.accounts = accounts;
        this.auth = auth; this.realtime = realtime;
    }

    public List<Status> update(Actor actor, String sessionKey, Update update) {
        Set<UUID> recipients = new HashSet<>();
        for (Peer peer : update.contacts()) {
            if (peer.userId().equals(actor.userId()) || !recipients.add(peer.userId()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_presence");
        }
        if (update.typingTo() == null && update.typingForMillis() != 0
                || update.typingTo() != null && (!recipients.contains(update.typingTo()) || update.typingForMillis() == 0))
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_presence");
        String key = "presence:" + actor.deviceId();
        String connectionId = realtime.connection(actor);
        if (connectionId == null) { redis.delete(key); return List.of(); }
        AccountDirectory.Contact identity = accounts.contact(actor.userId());
        if (!identity.deviceId().equals(actor.deviceId())) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        Peer owner = new Peer(identity.userId(), identity.deviceId(), identity.identityKey());
        long now = clock.millis();
        State state = new State(owner, List.copyOf(update.contacts()), connectionId, sessionKey,
                update.typingTo(), now + update.typingForMillis(), now + LIFETIME);
        String encoded;
        try { encoded = json.writeValueAsString(state); }
        catch (Exception failure) { throw new IllegalStateException("Presence serialization failed"); }
        redis.opsForValue().set(key, encoded, Duration.ofMillis(LIFETIME));
        if (update.contacts().isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(update.contacts().stream().map(peer -> "presence:" + peer.deviceId()).toList());
        if (values == null) return List.of();
        List<Status> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == null) continue;
            State peerState;
            try { peerState = json.readValue(values.get(index), State.class); }
            catch (Exception failure) { throw new IllegalStateException("Invalid ephemeral presence state"); }
            Peer peer = update.contacts().get(index);
            Actor peerActor = new Actor(peer.userId(), peer.deviceId());
            long remaining = peerState.expiresAt() - clock.millis();
            if (remaining <= 0 || remaining > LIFETIME || !peer.equals(peerState.owner())
                    || !peerState.contacts().contains(owner) || !auth.activeSession(peerState.sessionKey(), peerActor)
                    || !peerState.connectionId().equals(realtime.connection(peerActor))) continue;
            long typing = owner.userId().equals(peerState.typingTo())
                    ? Math.max(0, Math.min(remaining, peerState.typingUntil() - clock.millis())) : 0;
            result.add(new Status(peer, remaining, typing));
        }
        return result;
    }
}