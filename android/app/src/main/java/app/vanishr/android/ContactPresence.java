package app.vanishr.android;

import android.os.SystemClock;
import java.util.*;

final class ContactPresence {
    static final long LIFETIME = 12_000;
    static final long TYPING_LIFETIME = 5_000;
    static final long LAST_SEEN_LIFETIME = 86_400_000;
    static final int LIMIT = 128;
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

    // The open chat first, then independently verified contacts, then the automatic admin connection, within the relay's limit.
    private List<ChatEngine.Contact> audience(UUID selected) {
        List<ChatEngine.Peer> peers = new ArrayList<>(engine.peers());
        peers.sort(Comparator.comparing(peer -> !peer.userId().equals(selected)));
        List<ChatEngine.Contact> verified = new ArrayList<>(), automatic = new ArrayList<>();
        for (ChatEngine.Peer peer : peers) {
            if (verified.size() >= LIMIT) break;
            if (engine.independentlyVerified(peer.userId())) verified.add(identity(peer));
            else if (automatic.size() < LIMIT && engine.automaticConnection(peer.userId())) automatic.add(identity(peer));
        }
        List<ChatEngine.Contact> contacts = new ArrayList<>(verified);
        contacts.addAll(automatic);
        contacts.sort(Comparator.comparing(contact -> !contact.userId().equals(selected)));
        return List.copyOf(contacts.subList(0, Math.min(LIMIT, contacts.size())));
    }

    Update update(long now) {
        if (!foreground || !engine.authenticated()) return null;
        UUID selected;
        long attempt;
        synchronized (this) { selected = conversation; attempt = generation; }
        List<ChatEngine.Contact> contacts = audience(selected);
        synchronized (this) {
            if (!foreground || generation != attempt) return null;
            long remaining = Math.max(0, Math.min(TYPING_LIFETIME, typingUntil - now));
            UUID typing = remaining > 0 && contacts.stream().anyMatch(peer -> peer.userId().equals(selected)) ? selected : null;
            return new Update(contacts, typing, typing == null ? 0 : remaining);
        }
    }

    void refresh() throws Exception {
        long started = SystemClock.elapsedRealtime();
        long attempt;
        Update update;
        synchronized (this) {
            if (started < retryAt) return;
            attempt = generation;
        }
        update = update(started);
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
        if (response == null || response.length > LIMIT || started < 0 || now < started) throw new SecurityException("Invalid presence response");
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
        return labelSnapshot(snapshot(peer), now);
    }

    Seen state(UUID peer) { return states.get(peer); }

    Seen snapshot(ChatEngine.Peer peer) {
        if (peer == null || !foreground || !engine.realtimeReady() || !engine.authenticated()) return null;
        Seen state = states.get(peer.userId());
        if (state == null || !state.peer().equals(identity(peer))) return null;
        ChatEngine.Peer saved = engine.peers().stream().filter(value -> value.userId().equals(peer.userId())).findFirst().orElse(null);
        if (saved == null || !identity(saved).equals(state.peer()) || !engine.sharesPresence(peer.userId())) return null;
        return state;
    }

    String labelSnapshot(Seen state, long now) {
        if (state == null || !foreground || !engine.realtimeReady() || !engine.authenticated()
                || states.get(state.peer().userId()) != state || state.expiresAt() <= now) return "";
        if (state.lastSeenAt() == null) return state.typingUntil() > now ? "Typing" : "Online";
        long age = now - state.lastSeenAt();
        if (age < 60_000) return "Last seen just now";
        if (age < 3_600_000) return "Last seen " + age / 60_000 + " min ago";
        long hours = age / 3_600_000;
        return "Last seen " + hours + (hours == 1 ? " hour ago" : " hours ago");
    }
}