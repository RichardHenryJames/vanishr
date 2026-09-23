package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.*;

import static app.vanishr.relay.RelayTypes.Actor;

@Repository
public class Presence {
    public static final long LIFETIME = 12_000;
    public static final long LAST_SEEN_LIFETIME = 86_400_000;
    public record Peer(@NotNull UUID userId, @NotNull UUID deviceId,
                       @NotNull @Pattern(regexp = "[A-Za-z0-9+/]{44}") String identityKey) { }
    public record Update(@NotNull @Size(max = 128) List<@NotNull @Valid Peer> contacts,
                         UUID typingTo, @Min(0) @Max(5000) long typingForMillis, boolean lastSeen) {
        public Update(List<Peer> contacts, UUID typingTo, long typingForMillis) { this(contacts, typingTo, typingForMillis, false); }
        @Override public String toString() { return "PresenceUpdate[redacted]"; }
    }
    public record Status(Peer peer, long onlineForMillis, long typingForMillis, Long lastSeenAgoMillis) { }
    record State(Peer owner, List<Peer> contacts, String connectionId, String sessionKey,
                 UUID typingTo, long typingUntil, long expiresAt) {
        @Override public String toString() { return "PresenceState[redacted]"; }
    }
    record LastSeen(Peer owner, List<Peer> contacts, UUID deviceVersion, long observedAt, long expiresAt) {
        @Override public String toString() { return "LastSeen[redacted]"; }
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final AuthService auth;
    private final RealtimeHub realtime;
    private final DefaultRedisScript<Long> heartbeat = new DefaultRedisScript<>();

    public Presence(StringRedisTemplate redis, ObjectMapper json, Clock clock, AccountDirectory accounts,
                    AuthService auth, RealtimeHub realtime) {
        this.redis = redis; this.json = json; this.clock = clock; this.accounts = accounts;
        this.auth = auth; this.realtime = realtime;
        heartbeat.setLocation(new ClassPathResource("redis/presence-heartbeat.lua"));
        heartbeat.setResultType(Long.class);
    }

    public void clear(UUID deviceId) {
        redis.delete(List.of("presence:" + deviceId, "last-seen:" + deviceId));
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
        UUID deviceVersion = accounts.version(actor.deviceId());
        if (!auth.activeSession(sessionKey, actor)) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        Peer owner = new Peer(identity.userId(), identity.deviceId(), identity.identityKey());
        long now = clock.millis();
        State state = new State(owner, List.copyOf(update.contacts()), connectionId, sessionKey,
                update.typingTo(), now + update.typingForMillis(), now + LIFETIME);
        String encoded;
        String recent = "";
        try {
            encoded = json.writeValueAsString(state);
            if (update.lastSeen() && !update.contacts().isEmpty()) recent = json.writeValueAsString(new LastSeen(
                owner, state.contacts(), deviceVersion, now, now + LAST_SEEN_LIFETIME));
        }
        catch (Exception failure) { throw new IllegalStateException("Presence serialization failed"); }
        if (!Long.valueOf(1).equals(redis.execute(heartbeat, List.of(key, "last-seen:" + actor.deviceId(), sessionKey),
            encoded, Long.toString(LIFETIME), recent, Long.toString(LAST_SEEN_LIFETIME))))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        if (update.contacts().isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(update.contacts().stream().map(peer -> "presence:" + peer.deviceId()).toList());
        if (values == null) return List.of();
        List<String> recentValues = update.lastSeen() ? redis.opsForValue().multiGet(
            update.contacts().stream().map(peer -> "last-seen:" + peer.deviceId()).toList()) : null;
        List<Status> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Peer peer = update.contacts().get(index);
            Actor peerActor = new Actor(peer.userId(), peer.deviceId());
            if (values.get(index) != null) {
            State peerState;
            try { peerState = json.readValue(values.get(index), State.class); }
            catch (Exception failure) { throw new IllegalStateException("Invalid ephemeral presence state"); }
            long remaining = peerState.expiresAt() - clock.millis();
            if (remaining > 0 && remaining <= LIFETIME && peer.equals(peerState.owner())
                && peerState.contacts().contains(owner) && auth.activeSession(peerState.sessionKey(), peerActor)
                && peerState.connectionId().equals(realtime.connection(peerActor))) {
                long typing = owner.userId().equals(peerState.typingTo())
                    ? Math.max(0, Math.min(remaining, peerState.typingUntil() - clock.millis())) : 0;
                result.add(new Status(peer, remaining, typing, null));
                continue;
            }
            }
            if (recentValues == null || recentValues.get(index) == null) continue;
            LastSeen lastSeen;
            try { lastSeen = json.readValue(recentValues.get(index), LastSeen.class); }
            catch (Exception failure) { throw new IllegalStateException("Invalid last-seen state"); }
            long age = clock.millis() - lastSeen.observedAt();
            if (age < 0 || age >= LAST_SEEN_LIFETIME || lastSeen.expiresAt() <= clock.millis()
                || lastSeen.expiresAt() != lastSeen.observedAt() + LAST_SEEN_LIFETIME
                || !peer.equals(lastSeen.owner()) || !lastSeen.contacts().contains(owner)
                || !accounts.active(peerActor, lastSeen.deviceVersion())) continue;
            result.add(new Status(peer, 0, 0, age));
        }
        return result;
    }
}