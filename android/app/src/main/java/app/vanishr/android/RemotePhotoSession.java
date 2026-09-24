package app.vanishr.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import app.vanishr.crypto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class RemotePhotoSession implements AutoCloseable {
    static final long LIFETIME = 900_000;
    record AccountType(UUID userId, String userType) { }
    record Session(UUID id, ChatEngine.Contact requester, ChatEngine.Contact owner, boolean accepted, long expiresAt, PublicBundle key) { }
    record Start(UUID id, ChatEngine.Contact owner, PublicBundle key) { }
    record Approval(ChatEngine.Contact requester) { }
    record Send(UUID id, long expiresAt, int type, byte[] ciphertext) {
        @Override public String toString() { return "PhotoSend[redacted]"; }
    }
    record Exchange(UUID acknowledge, Send packet) { }
    record Packet(UUID id, UUID senderId, UUID senderDeviceId, long expiresAt, int type, byte[] ciphertext) { }
    record Delivery(Packet packet) { }
    record Frame(UUID sessionId, UUID requestId, String action, long photoId, long cursor, long offset, long total,
                 boolean more, byte[] data, ChatEnvelope message) {
        @Override public String toString() { return "PhotoFrame[redacted]"; }
    }

    static ChatEngine.Contact contact(ChatEngine.Peer peer) { return new ChatEngine.Contact(peer.userId(), peer.deviceId(), peer.identityKey()); }
    static boolean administrator(ChatEngine engine) throws Exception {
        AccountType result = engine.groupApi().call("GET", "/account/type", null, AccountType.class);
        return result != null && engine.account().userId().equals(result.userId()) && "ADMIN".equals(result.userType());
    }

    static final class Prepared implements AutoCloseable {
        final UUID id;
        final ChatEngine.Contact own, peer;
        final String peerName;
        final boolean owner;
        final MemoryVault vault;
        final SignalClient signal;
        final PublicBundle key;
        final long deadline;
        final RelayApi api;
        final Session request;
        Prepared(ChatEngine engine, ChatEngine.Peer peer, Session request) throws Exception {
            if (!engine.authenticated() || !engine.groupSignal().isVerified(peer.userId())
                    || engine.peers().stream().noneMatch(saved -> contact(saved).equals(contact(peer))))
                throw new SecurityException("Verify this contact before sharing photos");
            if (request == null && !administrator(engine)) throw new SecurityException("Only administrators can request photos");
            ChatEngine.Contact current = engine.groupApi().call("GET", "/users/id/" + peer.userId(), null, ChatEngine.Contact.class);
            if (!contact(peer).equals(current)) throw new SecurityException("Contact identity changed");
            ChatEngine.Account account = engine.account();
            own = new ChatEngine.Contact(account.userId(), account.deviceId(), Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()));
            this.peer = current; peerName = peer.name(); owner = request != null; this.request = request;
            id = owner ? request.id() : UUID.randomUUID();
            if (owner && (!own.equals(request.owner()) || !current.equals(request.requester()) || request.accepted()
                    || request.key() == null || !current.identityKey().equals(Base64.getEncoder().encodeToString(request.key().identityKey()))))
                throw new SecurityException("Photo request does not match this contact");
            deadline = Math.min(account.expiresAt(), owner ? request.expiresAt() : System.currentTimeMillis() + LIFETIME);
            if (deadline <= System.currentTimeMillis() + 10_000 || deadline > System.currentTimeMillis() + LIFETIME + 5000)
                throw new SecurityException("Photo request expired");
            vault = new MemoryVault(deadline);
            try {
                signal = engine.groupSignal().isolatedSession(vault);
                signal.verifyPeer(current.userId(), Base64.getDecoder().decode(current.identityKey()));
                key = owner ? null : signal.generatePreKey(Instant.now());
                api = new RelayApi(account.origin(), account.accessToken());
            } catch (Exception failure) { vault.close(); throw failure; }
        }
        @Override public void close() { vault.close(); api.close(); }
    }

    private final Prepared prepared;
    private final PhotoLibrary library;
    private final long expiresElapsed;
    private final List<Long> photos = Collections.synchronizedList(new ArrayList<>());
    private final android.util.LruCache<Long, byte[]> thumbnails = new android.util.LruCache<>(2 * 1024 * 1024) {
        @Override protected int sizeOf(Long id, byte[] value) { return value.length; }
        @Override protected void entryRemoved(boolean evicted, Long id, byte[] before, byte[] after) { Arrays.fill(before, (byte) 0); }
    };
    private final ArrayDeque<Frame> responses = new ArrayDeque<>();
    private final Set<Long> missingThumbnails = new LinkedHashSet<>();
    private final Set<Long> unavailableThumbnails = new HashSet<>();
    private Session session;
    private Send outgoing;
    private UUID lastReceived, query;
    private String queryAction;
    private long cursor = Long.MAX_VALUE, receivingOffset, queryPhoto;
    private volatile long selected, wantedPhoto;
    private int pageReceived;
    private byte[] original;
    private PhotoLibrary.Opened source;
    private long sendingPhoto;
    private UUID sendingQuery;
    private volatile boolean wantPage = true, loading, more = true;
    private boolean initialized;
    private long lastPoll;
    private long relayDeadline;
    private long lastProgress = SystemClock.elapsedRealtime();
    private volatile boolean connected, connectionFailed, ended, ready, stopping;
    private volatile String status = "Connecting...";
    private volatile Bitmap fullImage;
    private volatile long revision;
    private okhttp3.WebSocket socket;

    RemotePhotoSession(Context context, Prepared prepared) {
        this.prepared = prepared;
        library = new PhotoLibrary(context);
        expiresElapsed = SystemClock.elapsedRealtime() + Math.max(0, prepared.deadline - System.currentTimeMillis());
    }
    UUID id() { return prepared.id; }
    UUID userId() { return prepared.own.userId(); }
    UUID peerId() { return prepared.peer.userId(); }
    boolean owner() { return prepared.owner; }
    boolean approvedOwner() { return prepared.owner && ready && !ended && !stopping; }
    String peerName() { return prepared.peerName; }
    boolean ended() { return ended; }
    String status() { return status; }
    long revision() { return revision; }
    Bitmap fullImage() { return fullImage; }
    List<Long> photos() { synchronized (photos) { return List.copyOf(photos); } }
    synchronized long selected() { return selected; }
    synchronized boolean loading() { return loading; }
    synchronized boolean hasMore() { return more; }
    synchronized byte[] thumbnail(long id) {
        byte[] value = thumbnails.get(id);
        if (value == null && !ended && ready && !unavailableThumbnails.contains(id) && missingThumbnails.size() < 24) missingThumbnails.add(id);
        return value == null ? null : value.clone();
    }
    synchronized void loadMore() { if (ready && !ended && more && selected == 0) wantPage = true; }
    synchronized void open(long id) {
        if (!ready || ended || !photos.contains(id)) return;
        selected = id; wantedPhoto = id; fullImage = null; status = "Loading photo..."; revision++;
    }
    synchronized void gallery() { selected = 0; fullImage = null; revision++; }

    void connect() { socket = prepared.api.photoEvents(() -> { }, active -> { connected = active; if (!active) connectionFailed = true; }); }

    void tick() throws Exception {
        if (ended || stopping) return;
        if (SystemClock.elapsedRealtime() >= expiresElapsed) throw new IOException("Photo session expired");
        if (connectionFailed) throw new IOException("Photo connection ended");
        if (!connected) return;
        if (!prepared.owner && session != null && session.accepted() && (query != null || !ready)
            && SystemClock.elapsedRealtime() - lastProgress > 60_000) throw new IOException("The other phone is unavailable");
        if (!initialized) {
            if (prepared.owner) {
                session = prepared.api.call("POST", path() + "/accept", new Approval(prepared.peer), Session.class);
                validateSession(session);
                if (!session.accepted()) throw new SecurityException("Photo access was not approved");
                prepared.signal.establish(prepared.peer.userId(), prepared.request.key(), Instant.now());
                responses.add(frame(prepared.id, "HELLO", 0, 0, 0, 0, false, null));
                ready = true; status = "Photo sharing active";
            } else {
                session = prepared.api.call("POST", "/remote-photos", new Start(prepared.id, prepared.peer, prepared.key), Session.class);
                validateSession(session); status = "Waiting for approval...";
            }
            initialized = true; revision++;
        }
        if (!session.accepted()) {
            if (SystemClock.elapsedRealtime() - lastPoll < 1000) return;
            lastPoll = SystemClock.elapsedRealtime();
            session = prepared.api.call("GET", path(), null, Session.class); validateSession(session);
            if (!session.accepted()) return;
            lastProgress = SystemClock.elapsedRealtime();
        }
        if (outgoing == null) {
            Frame next = prepared.owner ? ownerFrame() : viewerFrame();
            if (next != null) {
                byte[] bytes = RelayApi.JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
                try {
                    SignalClient.Packet packet = prepared.signal.encrypt(prepared.peer.userId(), bytes, Instant.now());
                    outgoing = new Send(next.message().id(), next.message().expiresAt(), packet.type(), packet.ciphertext());
                } finally { Arrays.fill(bytes, (byte) 0); if (next.data() != null) Arrays.fill(next.data(), (byte) 0); }
            }
        }
        if (outgoing != null && outgoing.expiresAt() <= System.currentTimeMillis()) throw new IOException("Photo transfer expired");
        Delivery response;
        try { response = prepared.api.photoCall("POST", path() + "/exchange", new Exchange(lastReceived, outgoing), Delivery.class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status == 409 && failure.code.equals("photo_transfer_busy")) return; throw failure; }
        if (ended || stopping) return;
        outgoing = null;
        if (response == null) throw new IOException("Photo transfer unavailable");
        if (response.packet() != null && !response.packet().id().equals(lastReceived)) receive(response.packet());
    }

    private String path() { return "/remote-photos/" + prepared.id; }
    private void validateSession(Session value) {
        if (value == null || !prepared.id.equals(value.id()) || value.expiresAt() <= System.currentTimeMillis()
            || value.expiresAt() > System.currentTimeMillis() + LIFETIME + 5000
            || relayDeadline != 0 && relayDeadline != value.expiresAt()
            || prepared.request != null && prepared.request.expiresAt() != value.expiresAt()
                || !(prepared.owner ? prepared.own.equals(value.owner()) && prepared.peer.equals(value.requester())
                    : prepared.own.equals(value.requester()) && prepared.peer.equals(value.owner())))
            throw new SecurityException("Photo session identity changed");
        relayDeadline = value.expiresAt();
    }

    private Frame frame(UUID request, String action, long photo, long before, long offset, long total, boolean more, byte[] data) {
        long now = System.currentTimeMillis(), deadline = Math.min(Math.min(session.expiresAt(), prepared.deadline), now + 50_000);
        ChatEnvelope message = new ChatEnvelope(1, UUID.randomUUID(), prepared.own.userId(), prepared.own.deviceId(),
                prepared.peer.userId(), prepared.peer.deviceId(), now, deadline, ChatEnvelope.Expiry.HOURS_24, "remote-photos", null);
        return new Frame(prepared.id, request, action, photo, before, offset, total, more, data, message);
    }

    private synchronized Frame viewerFrame() {
        if (!ready || query != null) return null;
        if (wantedPhoto > 0) {
            lastProgress = SystemClock.elapsedRealtime();
            query = UUID.randomUUID(); queryAction = "GET"; long photo = wantedPhoto; wantedPhoto = 0;
            queryPhoto = photo;
            receivingOffset = 0; clearOriginal();
            return frame(query, "GET", photo, 0, 0, 0, false, null);
        }
        if (wantPage && more && selected == 0) {
            lastProgress = SystemClock.elapsedRealtime();
            query = UUID.randomUUID(); queryAction = "LIST"; wantPage = false; loading = true;
            pageReceived = 0;
            status = "Loading photos..."; revision++;
            return frame(query, "LIST", 0, cursor, 0, 0, false, null);
        }
        if (!missingThumbnails.isEmpty() && selected == 0) {
            lastProgress = SystemClock.elapsedRealtime();
            long photo = missingThumbnails.iterator().next(); missingThumbnails.remove(photo);
            query = UUID.randomUUID(); queryAction = "THUMB";
            queryPhoto = photo;
            return frame(query, "THUMB", photo, 0, 0, 0, false, null);
        }
        return null;
    }

    private Frame ownerFrame() throws IOException {
        if (!responses.isEmpty()) return responses.remove();
        if (source == null) return null;
        long offset = source.position();
        byte[] bytes;
        try { bytes = source.next(); }
        catch (IOException failure) { closeSource(); return frame(sendingQuery, "ERROR", sendingPhoto, 0, 0, 0, false, null); }
        long total = source.size; boolean more = source.position() < total;
        if (!more) closeSource();
        return frame(sendingQuery, "CHUNK", sendingPhoto, 0, offset, total, more, bytes);
    }

    private void receive(Packet packet) throws Exception {
        if (!prepared.peer.userId().equals(packet.senderId()) || !prepared.peer.deviceId().equals(packet.senderDeviceId())
                || packet.expiresAt() <= System.currentTimeMillis() || packet.expiresAt() > session.expiresAt())
            throw new SecurityException("Invalid photo sender");
        byte[] plaintext = prepared.signal.decrypt(prepared.peer.userId(), new SignalClient.Packet(packet.type(), packet.ciphertext()));
        Frame value = null;
        try {
            value = RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), Frame.class);
            if (value == null || !prepared.id.equals(value.sessionId()) || value.requestId() == null || value.message() == null
                    || value.action() == null || value.data() != null && value.data().length > PhotoLibrary.CHUNK_SIZE)
                throw new SecurityException("Invalid photo response");
            value.message().verify(packet.id(), prepared.peer.userId(), prepared.peer.deviceId(), prepared.own.userId(), prepared.own.deviceId(),
                    ChatEnvelope.Expiry.HOURS_24, packet.expiresAt(), null, Instant.now());
            if (!"remote-photos".equals(value.message().text())) throw new SecurityException("Invalid photo purpose");
            if (prepared.owner) ownerReceive(value); else viewerReceive(value);
            lastReceived = packet.id();
            lastProgress = SystemClock.elapsedRealtime();
        } finally { Arrays.fill(plaintext, (byte) 0); if (value != null && value.data() != null) Arrays.fill(value.data(), (byte) 0); }
    }

    private void ownerReceive(Frame value) throws Exception {
        if (!responses.isEmpty() || source != null || value.data() != null) throw new SecurityException("Unexpected photo request");
        try {
            switch (value.action()) {
                case "LIST" -> {
                    PhotoLibrary.Page page = library.page(value.cursor());
                    for (long photo : page.ids()) {
                        byte[] thumbnail;
                        try { thumbnail = library.thumbnail(photo); } catch (IOException failure) { thumbnail = null; }
                        responses.add(frame(value.requestId(), "ENTRY", photo, 0, 0, 0, false, thumbnail));
                    }
                    responses.add(frame(value.requestId(), "PAGE_END", 0, page.cursor(), 0, 0, page.more(), null));
                }
                case "THUMB" -> responses.add(frame(value.requestId(), "THUMB", value.photoId(), 0, 0, 0, false, library.thumbnail(value.photoId())));
                case "GET" -> { source = library.open(value.photoId()); sendingPhoto = value.photoId(); sendingQuery = value.requestId(); }
                default -> throw new SecurityException("Unsupported photo request");
            }
        } catch (IOException failure) { responses.add(frame(value.requestId(), "ERROR", value.photoId(), 0, 0, 0, false, null)); }
    }

    private void viewerReceive(Frame value) throws IOException {
        if (value.action().equals("HELLO") && !ready && prepared.id.equals(value.requestId())) {
            ready = true; status = "Connected"; revision++; return;
        }
        if (!ready || query == null || !query.equals(value.requestId())) throw new SecurityException("Unrequested photo response");
        switch (value.action()) {
            case "ENTRY" -> {
                if (!"LIST".equals(queryAction) || value.photoId() <= 0 || value.photoId() >= cursor
                    || ++pageReceived > PhotoLibrary.PAGE_SIZE || !photos.isEmpty() && value.photoId() >= photos.get(photos.size() - 1)) throw new SecurityException("Invalid gallery order");
                photos.add(value.photoId()); putThumbnail(value); revision++;
            }
            case "PAGE_END" -> {
                if (!"LIST".equals(queryAction) || value.more() && (value.cursor() <= 0 || value.cursor() >= cursor)
                    || pageReceived > 0 && value.cursor() != photos.get(photos.size() - 1)) throw new SecurityException("Invalid gallery cursor");
                cursor = value.cursor(); more = value.more(); loading = false; query = null;
                status = photos.isEmpty() ? "No accessible photos" : ""; revision++;
            }
            case "THUMB" -> { if (!"THUMB".equals(queryAction) || queryPhoto != value.photoId()) throw new SecurityException("Unrequested thumbnail"); putThumbnail(value); query = null; revision++; }
            case "CHUNK" -> {
                if (!"GET".equals(queryAction) || queryPhoto != value.photoId() || value.offset() != receivingOffset || value.total() <= 0 || value.total() > PhotoLibrary.MAX_OPEN_BYTES
                        || value.data() == null || value.data().length == 0 || receivingOffset + value.data().length > value.total()
                        || value.more() != (receivingOffset + value.data().length < value.total())) throw new SecurityException("Invalid photo chunk");
                if (original == null) original = new byte[(int) value.total()];
                if (original.length != value.total()) throw new SecurityException("Photo changed during transfer");
                System.arraycopy(value.data(), 0, original, (int) receivingOffset, value.data().length); receivingOffset += value.data().length;
                status = "Loading photo " + (receivingOffset * 100 / value.total()) + "%"; revision++;
                if (!value.more()) {
                    try { if (selected == value.photoId()) fullImage = PhotoLibrary.decode(original, 4096); status = ""; }
                    catch (IOException failure) { status = "This photo format cannot be displayed"; }
                    finally { clearOriginal(); query = null; revision++; }
                }
            }
            case "ERROR" -> {
                synchronized (this) { if ("THUMB".equals(queryAction)) unavailableThumbnails.add(queryPhoto); }
                clearOriginal(); query = null; loading = false; status = "Photo unavailable or too large to display"; revision++;
            }
            default -> throw new SecurityException("Invalid photo response action");
        }
    }
    private synchronized void putThumbnail(Frame value) {
        if (value.data() != null) {
            if (value.data().length > PhotoLibrary.THUMBNAIL_BYTES) throw new SecurityException("Invalid thumbnail size");
            thumbnails.put(value.photoId(), value.data().clone()); missingThumbnails.remove(value.photoId());
        } else unavailableThumbnails.add(value.photoId());
    }
    private void clearOriginal() { if (original != null) Arrays.fill(original, (byte) 0); original = null; }
    private void closeSource() { if (source != null) try { source.close(); } catch (IOException ignored) { } source = null; }
    void requestStop() { stopping = true; fullImage = null; revision++; }
    void cancelTransport() { if (socket != null) socket.cancel(); prepared.api.close(); }
    synchronized void end(String reason) { status = reason; close(); }
    @Override public synchronized void close() {
        if (ended) return;
        ended = true; ready = false; revision++; fullImage = null; clearOriginal(); closeSource();
        thumbnails.evictAll(); photos.clear(); missingThumbnails.clear(); unavailableThumbnails.clear();
        for (Frame response : responses) if (response.data() != null) Arrays.fill(response.data(), (byte) 0);
        responses.clear(); outgoing = null;
        if (socket != null) socket.cancel();
        prepared.close();
    }

    static final class MemoryVault implements SecureVault, AutoCloseable {
        private Map<String, byte[]> values = new HashMap<>();
        private final long deadline;
        private boolean closed;
        MemoryVault(long deadline) { this.deadline = SystemClock.elapsedRealtime() + Math.max(0, deadline - System.currentTimeMillis()); }
        private void check() { if (closed || SystemClock.elapsedRealtime() >= deadline) { close(); throw new SecurityException("Photo keys expired"); } }
        @Override public synchronized byte[] get(String name) { check(); byte[] bytes = values.get(name); return bytes == null ? null : bytes.clone(); }
        @Override public synchronized void put(String name, byte[] value) { check(); if (value.length > 65_536 || !values.containsKey(name) && values.size() >= 128) throw new SecurityException("Photo state limit exceeded"); byte[] before = values.put(name, value.clone()); if (before != null) Arrays.fill(before, (byte) 0); }
        @Override public synchronized void remove(String name) { check(); byte[] before = values.remove(name); if (before != null) Arrays.fill(before, (byte) 0); }
        @Override public synchronized List<String> names(String prefix) { check(); return values.keySet().stream().filter(name -> name.startsWith(prefix)).toList(); }
        @Override public synchronized <Result> Result transaction(Operation<Result> operation) throws Exception {
            check(); Map<String, byte[]> before = new HashMap<>(); values.forEach((name, bytes) -> before.put(name, bytes.clone()));
            try { Result result = operation.run(); before.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0)); return result; }
            catch (Exception failure) { values.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0)); values = before; throw failure; }
        }
        @Override public synchronized void close() { values.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0)); values.clear(); closed = true; }
    }
}