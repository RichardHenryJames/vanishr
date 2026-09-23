package app.vanishr.android;

import android.os.SystemClock;
import java.util.*;

final class ContactPresence {
    static final long LIFETIME = 12_000;
    static final long TYPING_LIFETIME = 5_000;
    static final long LAST_SEEN_LIFETIME = 86_400_000;
    record Update(List<ChatEngine.Contact> contacts, UUID typingTo, long typingForMillis, boolean lastSeen) {
        Update(List<ChatEngine.Contact> contacts, UUID typingTo, long typingForMillis) { this(contacts, typingTo, typingForMillis, true); }
        @Override public String toString() { return "PresenceUpdate[redacted]"; }
    }
    record Status(ChatEngine.Contact peer, long onlineForMillis, long typingForMillis, Long lastSeenAgoMillis) {
        Status(ChatEngine.Contact peer, long onlineForMillis, long typingForMillis) { this(peer, onlineForMillis, typingForMillis, null); }
    }
    record Seen(ChatEngine.Contact peer, long typingUntil, Long lastSeenAt, long expiresAt) { }
    private final ChatEngine engine;
    private volatile Map<UUID, Seen> states = Map.of();
    private volatile boolean foreground;
    private UUID conversation;
    private long typingUntil;
    private long generation;
    private long retryAt;

    ContactPresence(ChatEngine engine) { this.engine = engine; }

    synchronized void foreground(boolean active) {
        if (foreground != active) { foreground = active; generation++; }
        if (!active) { conversation = null; typingUntil = 0; states = Map.of(); }
    }

    synchronized void conversation(UUID peer) {
        if (!Objects.equals(conversation, peer)) { conversation = peer; typingUntil = 0; generation++; }
    }

    synchronized void edited(UUID peer, boolean hasText) {
        if (!foreground || !Objects.equals(conversation, peer)) return;
        typingUntil = hasText ? SystemClock.elapsedRealtime() + TYPING_LIFETIME : 0;
    }

    void disconnected() { states = Map.of(); }

    private static ChatEngine.Contact identity(ChatEngine.Peer peer) {
        return new ChatEngine.Contact(peer.userId(), peer.deviceId(), peer.identityKey());
    }

    synchronized Update update(long now) {
        if (!foreground || !engine.authenticated()) return null;
        List<ChatEngine.Peer> peers = new ArrayList<>(engine.peers());
        peers.sort(Comparator.comparing(peer -> !peer.userId().equals(conversation)));
        List<ChatEngine.Contact> contacts = peers.stream().filter(peer -> engine.groupSignal().isVerified(peer.userId()))
                .limit(128).map(ContactPresence::identity).toList();
        long remaining = Math.max(0, Math.min(TYPING_LIFETIME, typingUntil - now));
        UUID typing = remaining > 0 && contacts.stream().anyMatch(peer -> peer.userId().equals(conversation)) ? conversation : null;
        return new Update(contacts, typing, typing == null ? 0 : remaining);
    }

    void refresh() throws Exception {
        long started = SystemClock.elapsedRealtime();
        long attempt;
        Update update;
        synchronized (this) {
            if (started < retryAt) return;
            update = update(started); attempt = generation;
        }
        if (update == null) { disconnected(); return; }
        if (!engine.realtimeReady()) { disconnected(); engine.reconnect(); return; }
        try {
            Status[] response = engine.groupApi().presence(update);
            synchronized (this) {
                if (foreground && generation == attempt && engine.realtimeReady() && engine.authenticated())
                    accept(response, update.contacts(), started, SystemClock.elapsedRealtime());
            }
        } catch (RelayApi.ApiFailure failure) {
            disconnected();
            if (failure.status == 404 || failure.status == 429) retryAt = SystemClock.elapsedRealtime() + 60_000;
            if (failure.status == 401) throw failure;
        } catch (java.io.IOException | SecurityException failure) { disconnected(); }
    }

    void accept(Status[] response, List<ChatEngine.Contact> audience, long started, long now) {
        states = Map.of();
        if (response == null || response.length > 128 || started < 0 || now < started) throw new SecurityException("Invalid presence response");
        Map<UUID, Seen> received = new HashMap<>();
        Set<UUID> returned = new HashSet<>();
        for (Status status : response) {
            if (status == null || status.peer() == null || !audience.contains(status.peer())
                || status.onlineForMillis() < 0 || status.onlineForMillis() > LIFETIME
                    || status.typingForMillis() < 0 || status.typingForMillis() > TYPING_LIFETIME
                || status.typingForMillis() > status.onlineForMillis() || !returned.add(status.peer().userId())
                || (status.onlineForMillis() > 0 ? status.lastSeenAgoMillis() != null
                : status.lastSeenAgoMillis() == null || status.lastSeenAgoMillis() < 0 || status.lastSeenAgoMillis() >= LAST_SEEN_LIFETIME))
                throw new SecurityException("Invalid peer presence");
            Long lastSeenAt = status.lastSeenAgoMillis() == null ? null : started - status.lastSeenAgoMillis();
            long deadline = lastSeenAt == null ? started + status.onlineForMillis()
                : Math.min(started + LIFETIME, lastSeenAt + LAST_SEEN_LIFETIME);
            if (deadline > now) received.put(status.peer().userId(), new Seen(status.peer(),
                started + status.typingForMillis(), lastSeenAt, deadline));
        }
        states = Map.copyOf(received);
    }

    String label(ChatEngine.Peer peer, long now) {
        if (peer == null || !foreground || !engine.realtimeReady() || !engine.authenticated()) return "";
        Seen state = states.get(peer.userId());
        if (state == null || state.expiresAt() <= now || !state.peer().equals(identity(peer))) return "";
        ChatEngine.Peer saved = engine.peers().stream().filter(value -> value.userId().equals(peer.userId())).findFirst().orElse(null);
        if (saved == null || !identity(saved).equals(state.peer()) || !engine.groupSignal().isVerified(peer.userId())) return "";
        if (state.lastSeenAt() == null) return state.typingUntil() > now ? "Typing" : "Online";
        long age = now - state.lastSeenAt();
        if (age < 60_000) return "Last seen just now";
        if (age < 3_600_000) return "Last seen " + age / 60_000 + " min ago";
        long hours = age / 3_600_000;
        return "Last seen " + hours + (hours == 1 ? " hour ago" : " hours ago");
    }
}