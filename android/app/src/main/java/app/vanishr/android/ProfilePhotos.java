package app.vanishr.android;

import app.vanishr.crypto.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static app.vanishr.android.RelayApi.JSON;

final class ProfilePhotos {
    private static final long LIFETIME = 86_400_000L;
    private static final long REFRESH = 43_200_000L;
    private static final long RETRY = 60_000L;
    private static final int LIMIT = 128;
    record Own(long revision, byte[] photo) { @Override public String toString() { return "OwnPhoto[redacted]"; } }
    record Request(UUID id, UUID deviceId, String identityKey, long expiresAt, long refreshAt) { }
    record Grant(UUID requestId, UUID deviceId, String identityKey, long expiresAt, long revision) { }
    record Cached(UUID deviceId, String identityKey, long revision, byte[] photo, long expiresAt) {
        @Override public String toString() { return "CachedPhoto[redacted]"; }
    }
    record Send(UUID id, UUID recipientId, UUID recipientDeviceId, long expiresAt, int type, byte[] ciphertext) {
        @Override public String toString() { return "ProfileSend[redacted]"; }
    }
    record Packet(UUID id, UUID senderId, UUID senderDeviceId, UUID recipientId, UUID recipientDeviceId,
                  long expiresAt, int type, byte[] ciphertext) {
        @Override public String toString() { return "ProfilePacket[redacted]"; }
    }
    record Queued(Send packet, ProfileEnvelope.Action action, UUID requestId, String identityKey, long revision) { }
    private final ChatEngine engine;
    private final AndroidVault vault;
    private boolean supported = true;
    private int cursor;
    private volatile long generation;

    ProfilePhotos(ChatEngine engine, AndroidVault vault) { this.engine = engine; this.vault = vault; }
    private RelayApi api() { return engine.groupApi(); }
    private SignalClient signal() { return engine.groupSignal(); }
    private <Value> Value read(String key, Class<Value> type) {
        byte[] value = vault.get(key);
        if (value == null) return null;
        try { return JSON.fromJson(new String(value, StandardCharsets.UTF_8), type); }
        finally { Arrays.fill(value, (byte) 0); }
    }
    private void write(String key, Object value) {
        byte[] encoded = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        try { vault.put(key, encoded); } finally { Arrays.fill(encoded, (byte) 0); }
    }
    private ChatEngine.Peer peer(UUID userId) {
        return engine.peers().stream().filter(value -> value.userId().equals(userId)).findFirst().orElse(null);
    }
    private boolean matches(ChatEngine.Peer peer, UUID device, String identity) {
        return peer != null && peer.deviceId().equals(device) && peer.identityKey().equals(identity) && signal().isVerified(peer.userId());
    }
    long generation() { return generation; }
    byte[] own() {
        Own value = read("profile-photo", Own.class);
        return value == null ? null : value.photo();
    }
    byte[] contact(UUID userId) {
        Cached cached = read("profile-photo-cache/" + userId, Cached.class);
        if (cached == null) return null;
        if (cached.expiresAt() > System.currentTimeMillis() && matches(peer(userId), cached.deviceId(), cached.identityKey())) return cached.photo();
        if (cached.photo() != null) Arrays.fill(cached.photo(), (byte) 0);
        return null;
    }
    long expiresAt(UUID userId) {
        Cached cached = read("profile-photo-cache/" + userId, Cached.class);
        if (cached == null) return 0;
        if (cached.photo() != null) Arrays.fill(cached.photo(), (byte) 0);
        return cached.expiresAt();
    }
    void update(byte[] image) throws Exception {
        if (!engine.authenticated()) throw new SecurityException("Sign in before editing your photo");
        if (image != null) SafeImages.displayProfilePhoto(image).recycle();
        Own previous = read("profile-photo", Own.class);
        long revision = Math.max(System.currentTimeMillis(), previous == null ? 1 : Math.addExact(previous.revision(), 1));
        try { vault.transaction(() -> { write("profile-photo", new Own(revision, image)); return null; }); }
        finally { if (previous != null && previous.photo() != null) Arrays.fill(previous.photo(), (byte) 0); }
        generation++;
    }

    private void verifyCurrent(ChatEngine.Peer peer) throws Exception {
        if (!matches(peer(peer.userId()), peer.deviceId(), peer.identityKey())) { invalidate(peer.userId()); throw new SecurityException("Profile contact verification changed"); }
        ChatEngine.Contact current;
        try { current = api().call("GET", "/users/id/" + peer.userId(), null, ChatEngine.Contact.class); }
        catch (RelayApi.ApiFailure failure) {
            if (failure.status == 404) invalidate(peer.userId());
            throw failure;
        }
        if (current == null || !peer.userId().equals(current.userId()) || !peer.deviceId().equals(current.deviceId())
                || !peer.identityKey().equals(current.identityKey())) { invalidate(peer.userId()); throw new SecurityException("Profile contact identity changed"); }
        signal().verifyPeer(peer.userId(), Base64.getDecoder().decode(peer.identityKey()));
    }

    private void invalidate(UUID userId) throws Exception {
        vault.transaction(() -> {
            clearPeer(userId);
            for (String name : vault.names("profile-photo-out/")) if (read(name, Queued.class).packet().recipientId().equals(userId)) vault.remove(name);
            return null;
        });
        generation++;
    }

    private void queue(ChatEngine.Peer peer, ProfileEnvelope.Action action, UUID requestId, long revision, byte[] image, long deadline) throws Exception {
        if (vault.names("profile-photo-out/").size() >= 64) throw new IllegalStateException("Profile update queue is full");
        if (!signal().hasSession(peer.userId())) {
            PublicBundle bundle = api().call("POST", "/keys/" + peer.userId() + "/claim", null, PublicBundle.class);
            if (bundle == null || !peer.identityKey().equals(Base64.getEncoder().encodeToString(bundle.identityKey()))) throw new SecurityException("Profile key identity changed");
            signal().establish(peer.userId(), bundle, Instant.now());
        }
        vault.transaction(() -> {
            UUID id = action == ProfileEnvelope.Action.REQUEST ? requestId : UUID.randomUUID();
            long created = System.currentTimeMillis();
            ChatEnvelope context = new ChatEnvelope(1, id, engine.account().userId(), engine.account().deviceId(),
                    peer.userId(), peer.deviceId(), created, deadline, ChatEnvelope.Expiry.HOURS_24, "profile-photo", null);
            ProfileEnvelope envelope = new ProfileEnvelope(1, action, requestId, revision, image, context);
            envelope.verify(id, engine.account().userId(), engine.account().deviceId(), peer.userId(), peer.deviceId(), deadline, Instant.now());
            byte[] plaintext = JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
            SignalClient.Packet encrypted;
            try { encrypted = signal().encrypt(peer.userId(), plaintext, Instant.now()); }
            finally { Arrays.fill(plaintext, (byte) 0); }
            write("profile-photo-out/" + id, new Queued(new Send(id, peer.userId(), peer.deviceId(), deadline,
                    encrypted.type(), encrypted.ciphertext()), action, requestId, peer.identityKey(), revision));
            if (action == ProfileEnvelope.Action.REQUEST) write("profile-photo-request/" + peer.userId(),
                    new Request(requestId, peer.deviceId(), peer.identityKey(), deadline, created + REFRESH));
            else if (action == ProfileEnvelope.Action.UPDATE) write("profile-photo-grant/" + peer.userId(),
                    new Grant(requestId, peer.deviceId(), peer.identityKey(), deadline, revision));
            return null;
        });
    }

    void forget(ChatEngine.Peer peer) throws Exception {
        Grant grant = read("profile-photo-grant/" + peer.userId(), Grant.class);
        vault.transaction(() -> {
            for (String name : vault.names("profile-photo-out/")) if (read(name, Queued.class).packet().recipientId().equals(peer.userId())) vault.remove(name);
            if (grant != null && grant.expiresAt() > System.currentTimeMillis() && matches(peer, grant.deviceId(), grant.identityKey()) && signal().hasSession(peer.userId()))
                queue(peer, ProfileEnvelope.Action.REVOKE, grant.requestId(), 0, null, grant.expiresAt());
            clearPeer(peer.userId());
            return null;
        });
        generation++;
    }

    private void clearPeer(UUID id) {
        for (String prefix : List.of("profile-photo-cache/", "profile-photo-request/", "profile-photo-grant/")) vault.remove(prefix + id);
    }
    private void acknowledge(UUID id) throws Exception {
        try { api().call("DELETE", "/profile/packets/" + id, null, Void.class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status != 404 && failure.status != 410) throw failure; }
    }

    void receive(Packet packet) throws Exception {
        long now = System.currentTimeMillis();
        if (packet == null || packet.id() == null || packet.senderId() == null || packet.senderDeviceId() == null
                || !engine.account().userId().equals(packet.recipientId()) || !engine.account().deviceId().equals(packet.recipientDeviceId())
                || packet.expiresAt() > now + LIFETIME || packet.ciphertext() == null || packet.ciphertext().length > 65_536)
            throw new SecurityException("Invalid profile packet");
        ChatEngine.Peer sender = peer(packet.senderId());
        if (packet.expiresAt() <= now || sender == null || !sender.deviceId().equals(packet.senderDeviceId()) || !signal().isVerified(sender.userId())) {
            acknowledge(packet.id()); return;
        }
        if (vault.get("profile-photo-seen/" + packet.id()) != null) { acknowledge(packet.id()); return; }
        verifyCurrent(sender);
        if (vault.names("profile-photo-seen/").size() >= 512) return;
        vault.transaction(() -> {
            byte[] plaintext = signal().decrypt(sender.userId(), new SignalClient.Packet(packet.type(), packet.ciphertext()));
            ProfileEnvelope envelope = null;
            try {
                envelope = JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ProfileEnvelope.class);
                if (envelope == null) throw new SecurityException("Invalid profile envelope");
                envelope.verify(packet.id(), sender.userId(), sender.deviceId(), engine.account().userId(), engine.account().deviceId(), packet.expiresAt(), Instant.now());
                if (envelope.action() == ProfileEnvelope.Action.REQUEST) {
                    Grant previous = read("profile-photo-grant/" + sender.userId(), Grant.class);
                    if (previous == null && vault.names("profile-photo-grant/").size() >= LIMIT) throw new SecurityException("Profile sharing capacity reached");
                    write("profile-photo-grant/" + sender.userId(), new Grant(envelope.requestId(), sender.deviceId(), sender.identityKey(), packet.expiresAt(), 0));
                    if (previous == null && vault.get("profile-photo-cache/" + sender.userId()) == null) {
                        Request pending = read("profile-photo-request/" + sender.userId(), Request.class);
                        if (pending != null) write("profile-photo-request/" + sender.userId(),
                                new Request(pending.id(), pending.deviceId(), pending.identityKey(), pending.expiresAt(), 0));
                    }
                } else if (envelope.action() == ProfileEnvelope.Action.UPDATE) {
                    Request request = read("profile-photo-request/" + sender.userId(), Request.class);
                    Cached previous = read("profile-photo-cache/" + sender.userId(), Cached.class);
                    if (request != null && request.id().equals(envelope.requestId()) && request.expiresAt() >= packet.expiresAt()
                            && matches(sender, request.deviceId(), request.identityKey()) && (previous == null || previous.revision() <= envelope.revision())) {
                        if (envelope.photo() != null) SafeImages.displayProfilePhoto(envelope.photo()).recycle();
                        write("profile-photo-cache/" + sender.userId(), new Cached(sender.deviceId(), sender.identityKey(), envelope.revision(), envelope.photo(), packet.expiresAt()));
                        if (request.refreshAt() == 0) write("profile-photo-request/" + sender.userId(),
                            new Request(request.id(), request.deviceId(), request.identityKey(), request.expiresAt(), request.expiresAt() - REFRESH));
                        generation++;
                    }
                    if (previous != null && previous.photo() != null) Arrays.fill(previous.photo(), (byte) 0);
                } else {
                    Request request = read("profile-photo-request/" + sender.userId(), Request.class);
                    if (request != null && request.id().equals(envelope.requestId())) { clearPeer(sender.userId()); generation++; }
                }
                write("profile-photo-seen/" + packet.id(), packet.expiresAt());
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                if (envelope != null && envelope.photo() != null) Arrays.fill(envelope.photo(), (byte) 0);
            }
            return null;
        });
        acknowledge(packet.id());
    }

    private void flush() throws Exception {
        int sent = 0;
        Own own = read("profile-photo", Own.class);
        long revision = own == null ? 1 : own.revision();
        if (own != null && own.photo() != null) Arrays.fill(own.photo(), (byte) 0);
        for (String name : vault.names("profile-photo-out/")) {
            if (sent >= 4) break;
            Queued queued = read(name, Queued.class);
            Send packet = queued.packet();
            ChatEngine.Peer peer = peer(packet.recipientId());
            boolean allowed = packet.expiresAt() > System.currentTimeMillis();
            if (queued.action() != ProfileEnvelope.Action.REVOKE) allowed &= matches(peer, packet.recipientDeviceId(), queued.identityKey());
            if (queued.action() == ProfileEnvelope.Action.UPDATE) {
                Grant grant = read("profile-photo-grant/" + packet.recipientId(), Grant.class);
                allowed &= grant != null && grant.requestId().equals(queued.requestId()) && grant.expiresAt() >= packet.expiresAt() && queued.revision() == revision;
            }
            if (allowed) {
                if (queued.action() != ProfileEnvelope.Action.REVOKE) verifyCurrent(peer);
                try { api().call("POST", "/profile/packets", packet, Void.class); sent++; }
                catch (RelayApi.ApiFailure failure) { if (failure.status != 404 && failure.status != 410) throw failure; }
            }
            vault.transaction(() -> { vault.remove(name); return null; });
        }
    }

    void sync() throws Exception {
        if (!supported || !engine.authenticated()) return;
        Packet[] packets;
        try { packets = api().call("GET", "/profile/packets", null, Packet[].class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status == 404) { supported = false; return; } throw failure; }
        if (packets == null || packets.length > 16) throw new SecurityException("Invalid profile inbox");
        for (Packet packet : packets) receive(packet);
        flush();
        List<ChatEngine.Peer> peers = engine.peers();
        if (!peers.isEmpty()) {
            Own own = read("profile-photo", Own.class);
            try {
                for (int index = 0; index < Math.min(2, peers.size()); index++) {
                    ChatEngine.Peer peer = peers.get((cursor + index) % peers.size());
                    if (!signal().isVerified(peer.userId())) continue;
                    try {
                        Request request = read("profile-photo-request/" + peer.userId(), Request.class);
                        Grant grant = read("profile-photo-grant/" + peer.userId(), Grant.class);
                        long revision = own == null ? 1 : own.revision();
                        if (grant != null && grant.expiresAt() > System.currentTimeMillis() && matches(peer, grant.deviceId(), grant.identityKey()) && grant.revision() != revision) {
                            verifyCurrent(peer);
                            queue(peer, ProfileEnvelope.Action.UPDATE, grant.requestId(), revision, own == null ? null : own.photo(), grant.expiresAt());
                        }
                        boolean missingReply = request != null && grant != null && grant.expiresAt() > System.currentTimeMillis()
                            && matches(peer, grant.deviceId(), grant.identityKey()) && vault.get("profile-photo-cache/" + peer.userId()) == null
                            && request.expiresAt() - LIFETIME + RETRY <= System.currentTimeMillis();
                        if ((request == null || Math.max(request.refreshAt(), request.expiresAt() - LIFETIME + RETRY) <= System.currentTimeMillis() || missingReply)
                            && (request != null || vault.names("profile-photo-request/").size() < LIMIT)) {
                            verifyCurrent(peer);
                            queue(peer, ProfileEnvelope.Action.REQUEST, UUID.randomUUID(), 0, null, System.currentTimeMillis() + LIFETIME);
                        }
                    } catch (SecurityException failure) {
                        vault.transaction(() -> { clearPeer(peer.userId()); return null; }); generation++;
                    } catch (RelayApi.ApiFailure failure) { if (failure.status != 404 && failure.status != 409 && failure.status != 429) throw failure; }
                }
                cursor = (cursor + Math.min(2, peers.size())) % peers.size();
            } finally { if (own != null && own.photo() != null) Arrays.fill(own.photo(), (byte) 0); }
        }
        flush();
    }

    void purge(String prefix, long now) {
        for (String name : vault.names(prefix + "profile-photo-cache/")) {
            Cached cached = read(name, Cached.class);
            if (cached.expiresAt() <= now) { vault.remove(name); generation++; }
            if (cached.photo() != null) Arrays.fill(cached.photo(), (byte) 0);
        }
        for (String name : vault.names(prefix + "profile-photo-request/")) if (read(name, Request.class).expiresAt() <= now) vault.remove(name);
        for (String name : vault.names(prefix + "profile-photo-grant/")) if (read(name, Grant.class).expiresAt() <= now) vault.remove(name);
        for (String name : vault.names(prefix + "profile-photo-out/")) if (read(name, Queued.class).packet().expiresAt() <= now) vault.remove(name);
        for (String name : vault.names(prefix + "profile-photo-seen/")) if (read(name, Long.class) <= now) vault.remove(name);
    }
}