package app.vanishr.android;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static app.vanishr.android.RelayApi.JSON;

final class AdminOnboarding {
    static final class Failure extends SecurityException {
        Failure(String message) { super(message); }
    }

    record Pin(String origin, UUID userId, UUID deviceId, String fingerprint) {
        void require(ChatEngine.Contact contact) throws Exception {
            if (contact == null || !userId.equals(contact.userId()) || !deviceId.equals(contact.deviceId())
                    || contact.identityKey() == null || !contact.identityKey().matches("[A-Za-z0-9+/]{44}")
                    || !fingerprint.equalsIgnoreCase(ChatEngine.safetyNumber(userId, contact.identityKey()).replace(" ", "")))
                throw new Failure("Official admin identity changed. A reviewed app update is required.");
        }
    }

    // Public identity fingerprint approved for this signed app, not a private key or a relay-supplied trust root.
    static final Pin OFFICIAL = new Pin(
            "https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com/",
            UUID.fromString("98ad3c75-0127-422d-9a08-540edb9d3301"),
            UUID.fromString("ae1db2e6-479d-41dc-b915-735ea4e1955d"),
            "d0844c373dea6c0a3798de3abd61e0dbcec6e62a6b81ead26f8ea19c04dffa17");

    record Introduction(UUID userId, UUID deviceId, String identityKey, String handle, String displayName) {
        ChatEngine.Peer peer() {
            return new ChatEngine.Peer(userId, deviceId, identityKey, displayName == null ? handle : displayName, handle, displayName);
        }
    }
    record Page(UUID userId, ChatEngine.Contact admin, List<Introduction> contacts, UUID nextAfter) { }
    record Cached(UUID after, Page page) { }

    private static final String DIRECTORY = "admin-directory";
    private static final String AUTOMATIC = "admin-contact/";
    private static final String DISMISSED = "admin-dismissed/";
    private final ChatEngine engine;
    private final AndroidVault vault;
    private final Pin pin;
    private long nextRefresh;
    private volatile long generation;
    private volatile String problem;

    AdminOnboarding(ChatEngine engine, AndroidVault vault, Pin pin) {
        this.engine = engine; this.vault = vault; this.pin = pin;
    }

    boolean enabled() { return engine.account() != null && pin.origin().equals(engine.account().origin()); }
    boolean official(UUID userId) { return enabled() && pin.userId().equals(userId); }
    boolean admin() { return enabled() && pin.userId().equals(engine.account().userId()); }
    boolean automatic(UUID userId) { return vault.get(AUTOMATIC + userId) != null; }
    long generation() { return generation; }
    String problem() { return problem; }
    void blocked(Failure failure) {
        if (!Objects.equals(problem, failure.getMessage())) { problem = failure.getMessage(); generation++; }
    }
    private boolean dismissed(UUID userId) { return vault.get(DISMISSED + userId) != null; }

    private <Value> Value read(String key, Class<Value> type) {
        byte[] value = vault.get(key);
        if (value == null) return null;
        try { return JSON.fromJson(new String(value, StandardCharsets.UTF_8), type); }
        finally { Arrays.fill(value, (byte) 0); }
    }
    private void write(String key, Object value) {
        byte[] encoded = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        try { vault.put(key, encoded); }
        finally { Arrays.fill(encoded, (byte) 0); }
    }

    private void requireLocalAdmin() throws Exception {
        if (admin()) pin.require(new ChatEngine.Contact(engine.account().userId(), engine.account().deviceId(),
                Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity())));
    }

    void requireOfficial(ChatEngine.Peer peer) throws Exception {
        if (official(peer.userId())) pin.require(RemotePhotoSession.contact(peer));
        if (automatic(peer.userId())) requireLocalAdmin();
    }

    void requireSaved(UUID userId) throws Exception {
        ChatEngine.Peer peer = read("contact/" + userId, ChatEngine.Peer.class);
        if (peer != null) requireOfficial(peer);
    }

    void requireCurrent(UUID userId, ChatEngine.Contact contact) throws Exception {
        if (official(userId)) pin.require(contact);
    }

    private void validate(Page page, UUID after) throws Exception {
        if (page == null || !engine.account().userId().equals(page.userId()) || page.contacts() == null || page.contacts().size() > 64)
            throw new Failure("Invalid admin contact directory");
        if (page.admin() == null) {
            if (!page.contacts().isEmpty() || page.nextAfter() != null) throw new Failure("Unpinned admin contact directory");
            return;
        }
        pin.require(page.admin());
        requireLocalAdmin();
        if (!admin() && page.contacts().size() > 1) throw new Failure("Unrelated admin contacts were returned");
        String previous = after == null ? "" : after.toString();
        for (Introduction contact : page.contacts()) {
            if (contact == null || contact.userId() == null || contact.deviceId() == null || contact.identityKey() == null
                    || engine.account().userId().equals(contact.userId()) || contact.userId().toString().compareTo(previous) <= 0
                    || !contact.identityKey().matches("[A-Za-z0-9+/]{44}"))
                throw new Failure("Invalid introduced identity");
            try { ChatEngine.validateProfile(new ChatEngine.Profile(contact.userId(), contact.handle(), contact.displayName()), contact.userId()); }
            catch (SecurityException | IllegalArgumentException invalid) { throw new Failure("Invalid introduced profile"); }
            ChatEngine.safetyNumber(contact.userId(), contact.identityKey());
            if (!admin()) pin.require(RemotePhotoSession.contact(contact.peer()));
            previous = contact.userId().toString();
        }
        if (page.nextAfter() != null && (page.contacts().size() != 64 || !previous.equals(page.nextAfter().toString())))
            throw new Failure("Invalid admin contact page");
    }

    private Cached cached() {
        Cached value = read(DIRECTORY, Cached.class);
        if (value != null && (value.page() == null || !engine.account().userId().equals(value.page().userId())
                || value.page().contacts() == null || value.page().contacts().size() > 64))
            throw new SecurityException("Invalid saved admin directory");
        return value;
    }

    List<ChatEngine.Peer> peers() {
        if (!enabled()) return List.of();
        Cached value = cached();
        if (value == null) return List.of();
        return value.page().contacts().stream().filter(contact -> !dismissed(contact.userId())).map(Introduction::peer).toList();
    }

    boolean hasNext() { Cached value = enabled() ? cached() : null; return value != null && value.page().nextAfter() != null; }
    boolean hasPrevious() { Cached value = enabled() ? cached() : null; return value != null && value.after() != null; }

    void next() throws Exception {
        Cached value = enabled() ? cached() : null;
        if (value == null || value.page().nextAfter() == null) throw new IllegalStateException("No more new accounts");
        load(value.page().nextAfter());
    }
    void first() throws Exception {
        if (!enabled()) throw new IllegalStateException("Admin directory is unavailable");
        load(null);
    }
    void refresh() throws Exception {
        if (!enabled() || System.currentTimeMillis() < nextRefresh) return;
        nextRefresh = System.currentTimeMillis() + 30_000;
        Cached value = cached();
        load(value == null ? null : value.after());
    }
    private void load(UUID after) throws Exception {
        Page page;
        try { page = engine.groupApi().call("GET", "/account/admin-contacts" + (after == null ? "" : "?after=" + after), null, Page.class); }
        catch (com.google.gson.JsonParseException invalid) { throw new Failure("Invalid admin contact response"); }
        validate(page, after);
        Cached previous = cached(), updated = new Cached(after, page);
        vault.transaction(() -> {
            if (!updated.equals(previous)) write(DIRECTORY, updated);
            if (!admin()) for (Introduction contact : page.contacts()) if (!dismissed(contact.userId())) store(contact.peer());
            return null;
        });
        if (!updated.equals(previous) || problem != null) { problem = null; generation++; }
        nextRefresh = System.currentTimeMillis() + 30_000;
    }

    private boolean eligible(UUID peerId) {
        return enabled() && !engine.account().userId().equals(peerId) && !dismissed(peerId) && (admin() || official(peerId));
    }
    private ChatEngine.Peer lookup(UUID peerId) throws Exception {
        Page page = engine.groupApi().call("GET", "/account/admin-contacts/" + peerId, null, Page.class);
        validate(page, null);
        if (page.contacts().isEmpty()) return null;
        if (page.contacts().size() != 1 || !peerId.equals(page.contacts().get(0).userId()) || page.nextAfter() != null)
            throw new Failure("Admin introduction does not match");
        return page.contacts().get(0).peer();
    }

    boolean acceptIncoming(UUID peerId, UUID deviceId) throws Exception {
        if (!eligible(peerId)) return false;
        ChatEngine.Peer contact = lookup(peerId);
        if (contact == null) return false;
        if (!deviceId.equals(contact.deviceId())) throw new Failure("Introduced device changed");
        vault.transaction(() -> { store(contact); return null; });
        return true;
    }

    void prepare(ChatEngine.Peer peer) throws Exception {
        if (!engine.authenticated()) throw new SecurityException("Sign in before messaging");
        ChatEngine.Peer saved = read("contact/" + peer.userId(), ChatEngine.Peer.class);
        if (saved != null) {
            if (!RemotePhotoSession.contact(saved).equals(RemotePhotoSession.contact(peer)))
                throw new SecurityException("Contact identity changed");
            requireOfficial(saved);
            return;
        }
        if (!eligible(peer.userId())) throw new SecurityException("Verify this contact before messaging");
        ChatEngine.Peer current = lookup(peer.userId());
        if (current == null || !RemotePhotoSession.contact(current).equals(RemotePhotoSession.contact(peer)))
            throw new Failure("Introduced identity changed");
        vault.transaction(() -> { store(current); return null; });
    }

    private void store(ChatEngine.Peer peer) throws Exception {
        // Automatic direct-chat trust must not confer independently verified sharing permissions.
        requireOfficial(peer);
        ChatEngine.Peer previous = read("contact/" + peer.userId(), ChatEngine.Peer.class);
        if (previous != null && !RemotePhotoSession.contact(previous).equals(RemotePhotoSession.contact(peer)))
            throw new Failure("Introduced identity changed; the saved identity was not replaced");
        try { engine.groupSignal().verifyPeer(peer.userId(), Base64.getDecoder().decode(peer.identityKey())); }
        catch (SecurityException changed) { throw new Failure("Identity changed; automatic contact setup was stopped"); }
        if (previous == null) {
            write(AUTOMATIC + peer.userId(), true);
            write("contact/" + peer.userId(), peer);
        }
    }

    void manuallyVerified(ChatEngine.Peer peer) throws Exception {
        requireOfficial(peer);
        vault.remove(AUTOMATIC + peer.userId());
        vault.remove(DISMISSED + peer.userId());
    }

    void dismiss(UUID peerId) {
        if (enabled() && (admin() || official(peerId))) write(DISMISSED + peerId, true);
        vault.remove(AUTOMATIC + peerId);
    }
}
