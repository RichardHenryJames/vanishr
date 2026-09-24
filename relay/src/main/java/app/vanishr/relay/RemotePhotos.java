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
import java.nio.charset.StandardCharsets;
import java.util.*;

import static app.vanishr.relay.RelayTypes.Actor;

@Repository
public class RemotePhotos {
    public static final long LIFETIME = 900_000;
    public static final long REQUEST_LIFETIME = 120_000;
    public static final long PACKET_LIFETIME = 60_000;
    public record Request(@NotNull UUID id, @NotNull @Valid Presence.Peer owner, @NotNull @Valid AccountDirectory.PreKey key) { }
    public record Approval(@NotNull @Valid Presence.Peer requester) { }
    public record Session(UUID id, Presence.Peer requester, Presence.Peer owner, boolean accepted, long expiresAt, AccountDirectory.PreKey key) { }
    public record Send(@NotNull UUID id, @Positive long expiresAt, @Min(2) @Max(3) int type,
                       @NotNull @Size(min = 32, max = 65_536) byte[] ciphertext) {
        @Override public String toString() { return "RemotePhotoSend[redacted]"; }
    }
    public record Exchange(UUID acknowledge, @Valid Send packet) {
        @Override public String toString() { return "RemotePhotoExchange[redacted]"; }
    }
    public record Packet(UUID id, UUID senderId, UUID senderDeviceId, long expiresAt, int type, byte[] ciphertext) {
        @Override public String toString() { return "RemotePhotoPacket[redacted]"; }
    }
    public record Delivery(Packet packet) { }
    record Stored(Session session, UUID requesterVersion, UUID ownerVersion, String requesterConnection, String ownerConnection) {
        @Override public String toString() { return "RemotePhotoSession[redacted]"; }
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final RealtimeHub realtime;
    private final DefaultRedisScript<String> sessions = new DefaultRedisScript<>();
    private final DefaultRedisScript<String> exchange = new DefaultRedisScript<>();

    public RemotePhotos(StringRedisTemplate redis, ObjectMapper json, Clock clock, AccountDirectory accounts, RealtimeHub realtime) {
        this.redis = redis; this.json = json; this.clock = clock; this.accounts = accounts; this.realtime = realtime;
        sessions.setLocation(new ClassPathResource("redis/remote-photo-session.lua"));
        sessions.setResultType(String.class);
        exchange.setLocation(new ClassPathResource("redis/remote-photo-exchange.lua"));
        exchange.setResultType(String.class);
    }

    private Actor actor(Presence.Peer peer) { return new Actor(peer.userId(), peer.deviceId()); }
    private Presence.Peer peer(UUID userId) {
        AccountDirectory.Contact contact = accounts.contact(userId);
        return new Presence.Peer(contact.userId(), contact.deviceId(), contact.identityKey());
    }
    private List<String> keys(Session session) {
        return List.of("rps:" + session.id(), "rpsi:" + session.requester().deviceId(), "rpsi:" + session.owner().deviceId(),
            "rps-ended:" + session.id(), "rpq:" + session.id() + ":" + session.requester().deviceId(),
            "rpq:" + session.id() + ":" + session.owner().deviceId(),
            "rpr:" + session.id() + ":" + session.requester().deviceId(), "rpr:" + session.id() + ":" + session.owner().deviceId());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Remote photo serialization failed"); }
    }
    private Stored decode(String value) {
        try { return json.readValue(value, Stored.class); }
        catch (Exception failure) { throw new IllegalStateException("Invalid remote photo state"); }
    }
    private boolean participant(Actor caller, Session session) {
        return caller.equals(actor(session.requester())) || caller.equals(actor(session.owner()));
    }
    private void admin(UUID userId) {
        if (accounts.accountType(userId).userType() != AccountDirectory.UserType.ADMIN)
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_required");
    }
    private void remove(Stored stored, String expected) {
        redis.execute(sessions, keys(stored.session()), "DELETE", expected, "", Long.toString(clock.millis()),
                Long.toString(stored.session().expiresAt()), stored.session().id().toString());
    }
    private Stored checked(Actor caller, UUID id, boolean requireAccepted) {
        String value = redis.opsForValue().get("rps:" + id);
        if (value == null) throw new ApiException(HttpStatus.GONE, "photo_session_ended");
        Stored stored = decode(value);
        Session session = stored.session();
        if (!participant(caller, session)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        long remaining = session.expiresAt() - clock.millis();
        boolean live = remaining > 0 && remaining <= LIFETIME
                && (session.accepted() || remaining > LIFETIME - REQUEST_LIFETIME)
                && accounts.active(actor(session.requester()), stored.requesterVersion())
                && accounts.active(actor(session.owner()), stored.ownerVersion())
                && accounts.accountType(session.requester().userId()).userType() == AccountDirectory.UserType.ADMIN
                && Objects.equals(stored.requesterConnection(), realtime.photoConnection(actor(session.requester())))
                && (!session.accepted() || Objects.equals(stored.ownerConnection(), realtime.photoConnection(actor(session.owner()))));
        if (!live) { remove(stored, value); throw new ApiException(HttpStatus.GONE, "photo_session_ended"); }
        if (requireAccepted && !session.accepted()) throw new ApiException(HttpStatus.FORBIDDEN, "photo_approval_required");
        return stored;
    }

    public Session request(Actor caller, Request request) {
        admin(caller.userId());
        if (caller.userId().equals(request.owner().userId())) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        Presence.Peer requester = peer(caller.userId());
        if (!caller.deviceId().equals(requester.deviceId()) || !peer(request.owner().userId()).equals(request.owner()))
            throw new ApiException(HttpStatus.CONFLICT, "photo_identity_changed");
        if (!requester.identityKey().equals(Base64.getEncoder().encodeToString(request.key().identityKey())))
            throw new ApiException(HttpStatus.CONFLICT, "photo_identity_changed");
        String requesterConnection = realtime.photoConnection(caller), ownerConnection = realtime.connection(actor(request.owner()));
        if (requesterConnection == null || ownerConnection == null) throw new ApiException(HttpStatus.CONFLICT, "photo_peer_offline");
        Session session = new Session(request.id(), requester, request.owner(), false, clock.millis() + LIFETIME, request.key());
        Stored stored = new Stored(session, accounts.version(requester.deviceId()), accounts.version(request.owner().deviceId()),
                requesterConnection, ownerConnection);
        String result = redis.execute(sessions, keys(session), "CREATE", "", encode(stored), Long.toString(clock.millis()),
                Long.toString(session.expiresAt()), session.id().toString());
        if ("EXISTS".equals(result)) {
            Session previous = checked(caller, request.id(), false).session();
            if (!previous.requester().equals(requester) || !previous.owner().equals(request.owner()) || !encode(previous.key()).equals(encode(request.key())))
                throw new ApiException(HttpStatus.CONFLICT, "conflicting_retry");
            return previous;
        }
        if ("FULL".equals(result)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "photo_session_capacity");
        if ("EXPIRED".equals(result)) throw new ApiException(HttpStatus.GONE, "photo_session_ended");
        if (!"OK".equals(result)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "relay_unavailable");
        realtime.wake(session.owner().deviceId());
        return session;
    }

    public List<Session> pending(Actor caller) {
        String index = "rpsi:" + caller.deviceId();
        redis.opsForZSet().removeRangeByScore(index, 0, clock.millis());
        Set<String> ids = redis.opsForZSet().rangeByScore(index, clock.millis() + 1, Double.POSITIVE_INFINITY, 0, 4);
        List<Session> result = new ArrayList<>();
        if (ids != null) for (String id : ids) {
            try { result.add(checked(caller, UUID.fromString(id), false).session()); }
            catch (ApiException failure) {
                if (failure.status != HttpStatus.GONE && failure.status != HttpStatus.NOT_FOUND) throw failure;
                redis.opsForZSet().remove(index, id);
            }
        }
        return result;
    }

    public Session get(Actor caller, UUID id) { return checked(caller, id, false).session(); }

    public Delivery exchange(Actor caller, UUID id, Exchange request) {
        Stored stored = checked(caller, id, true);
        Session session = stored.session();
        boolean requester = caller.equals(actor(session.requester()));
        UUID recipient = requester ? session.owner().deviceId() : session.requester().deviceId();
        String encoded = "", digest = "";
        long deadline = 0;
        if (request.packet() != null) {
            Send packet = request.packet();
            deadline = packet.expiresAt();
            if (deadline <= clock.millis() || deadline > Math.min(session.expiresAt(), clock.millis() + PACKET_LIFETIME))
                throw new ApiException(HttpStatus.GONE, "photo_packet_expired");
            encoded = encode(new Packet(packet.id(), caller.userId(), caller.deviceId(), deadline, packet.type(), packet.ciphertext()));
            digest = RedisRelay.digest(encoded.getBytes(StandardCharsets.UTF_8));
        }
        String result = redis.execute(exchange, List.of("rps:" + id, "rpq:" + id + ":" + caller.deviceId(),
                        "rpq:" + id + ":" + recipient, "rpr:" + id + ":" + caller.deviceId()),
                encode(stored), request.acknowledge() == null ? "" : request.acknowledge().toString(), encoded, digest,
                Long.toString(clock.millis()), Long.toString(deadline), Long.toString(session.expiresAt()));
        if ("BUSY".equals(result)) throw new ApiException(HttpStatus.CONFLICT, "photo_transfer_busy");
        if ("CONFLICT".equals(result)) throw new ApiException(HttpStatus.CONFLICT, "conflicting_retry");
        if ("ENDED".equals(result)) throw new ApiException(HttpStatus.GONE, "photo_session_ended");
        if (result == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "relay_unavailable");
        Packet incoming = null;
        if (!result.isEmpty()) {
            try { incoming = json.readValue(result, Packet.class); }
            catch (Exception failure) { throw new IllegalStateException("Invalid remote photo packet"); }
        }
        if (request.packet() != null) realtime.wakePhotos(recipient);
        return new Delivery(incoming);
    }

    public Session accept(Actor caller, UUID id, Approval approval) {
        Stored stored = checked(caller, id, false);
        Session current = stored.session();
        if (!caller.equals(actor(current.owner()))) throw new ApiException(HttpStatus.FORBIDDEN, "photo_owner_required");
        if (!current.requester().equals(approval.requester())) throw new ApiException(HttpStatus.CONFLICT, "photo_identity_changed");
        if (current.accepted()) return current;
        String ownerConnection = realtime.photoConnection(caller);
        if (ownerConnection == null) throw new ApiException(HttpStatus.CONFLICT, "photo_peer_offline");
        Session accepted = new Session(id, current.requester(), current.owner(), true, current.expiresAt(), current.key());
        Stored next = new Stored(accepted, stored.requesterVersion(), stored.ownerVersion(), stored.requesterConnection(), ownerConnection);
        String result = redis.execute(sessions, keys(current), "ACCEPT", encode(stored), encode(next), Long.toString(clock.millis()),
                Long.toString(current.expiresAt()), id.toString());
        if (!"OK".equals(result)) throw new ApiException(HttpStatus.GONE, "photo_session_ended");
        realtime.wakePhotos(current.requester().deviceId());
        return accepted;
    }

    public void stop(Actor caller, UUID id) {
        String value = redis.opsForValue().get("rps:" + id);
        if (value == null) return;
        Stored stored = decode(value);
        if (!participant(caller, stored.session())) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        remove(stored, value);
        realtime.wake(stored.session().requester().deviceId()); realtime.wake(stored.session().owner().deviceId());
        realtime.wakePhotos(stored.session().requester().deviceId()); realtime.wakePhotos(stored.session().owner().deviceId());
    }
}