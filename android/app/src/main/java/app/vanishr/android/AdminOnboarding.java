package app.vanishr.android;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static app.vanishr.android.RelayApi.JSON;

final class AdminOnboarding {
    static class Failure extends SecurityException {
        Failure(String message) { super(message); }
    }

    /**
     * The relay introduces a different identity for a contact that is already saved (the person reinstalled Vanishr or changed phone). The
     * saved identity is never replaced by it on the relay's say-so. It is a failure of that one contact only: it never stops the rest of
     * a directory page, and it is kept for the person to review (see {@link #changed} and {@link #acceptChanged}).
     */
    static final class ChangedIdentity extends Failure {
        ChangedIdentity() { super("Introduced identity changed; the saved identity was not replaced"); }
    }

    /**
     * An identity was presented for the official admin account that is not the pinned one. It is the only failure after which asking for a
     * newer certificate can change the answer, so it is the only one that does (see MainActivity), and nothing else makes a request.
     */
    static final class Mismatch extends Failure {
        Mismatch() { super("Official admin identity changed. A reviewed app update is required."); }
    }

    record Pin(String origin, UUID userId, UUID deviceId, String fingerprint) {
        void require(ChatEngine.Contact contact) throws Exception {
            if (contact == null || !userId.equals(contact.userId()) || !deviceId.equals(contact.deviceId())
                    || contact.identityKey() == null || !contact.identityKey().matches("[A-Za-z0-9+/]{44}")
                    || !fingerprint.equalsIgnoreCase(ChatEngine.safetyNumber(userId, contact.identityKey()).replace(" ", "")))
                throw new Mismatch();
        }
    }

    // Public identity fingerprint approved for this signed app, not a private key or a relay-supplied trust root. It is the identity of the
    // owner's current phone (the SHA-256 of "userId:identityKey", the number that phone shows as its safety number). A later phone is
    // certified with a signed AdminIdentity certificate, and the next app version carries that identity here.
    static final Pin OFFICIAL = new Pin(
            "https://vanishr-dev-ec0d36067d.hhb5hebdbfagapbu.centralindia.sysgen.cloudapp.azure.com/",
            UUID.fromString("98ad3c75-0127-422d-9a08-540edb9d3301"),
            UUID.fromString("638b9ff1-8f90-428c-91b7-e1f8ce4d78a0"),
            "7b0c40727d9b7de86ec820ef52db3e8a0efea1644cace6c2e3ed64a441520ece");

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
    // What the relay now introduces for a saved contact whose saved identity is different: kept for review, trusted for nothing.
    private static final String CHANGED = "admin-changed/";
    private static final long CHANGE_LOOKUP_PAUSE = 60_000;
    private final ChatEngine engine;
    private final AndroidVault vault;
    private final Map<UUID, Long> nextChangeLookup = new java.util.concurrent.ConcurrentHashMap<>();
    // What the app trusts for the official admin right now: the built-in identity, or a newer one the owner certified (see adopt).
    private volatile Pin pin;
    private volatile long nextRefresh;
    private volatile long generation;
    private volatile String problem;

    AdminOnboarding(ChatEngine engine, AndroidVault vault, Pin pin) {
        this.engine = engine; this.vault = vault; this.pin = pin;
    }

    Pin pin() { return pin; }

    /**
     * Takes the identity from a certificate that has already been verified (AdminIdentity). Only the device and the identity of the same
     * admin account can change: the relay origin and the account stay what the app was built with.
     */
    void adopt(AdminIdentity.Certificate certificate) {
        Pin current = pin;
        if (certificate == null || !current.userId().equals(certificate.userId())) return;
        Pin next = new Pin(current.origin(), current.userId(), certificate.deviceId(), certificate.fingerprint());
        if (next.equals(current)) return;
        pin = next;
        nextRefresh = 0;
        problem = null;
        generation++;
    }

    boolean enabled() { return engine.account() != null && pin.origin().equals(engine.account().origin()); }
    boolean official(UUID userId) { return enabled() && pin.userId().equals(userId); }
    boolean admin() { return enabled() && pin.userId().equals(engine.account().userId()); }
    // admin() only compares the signed-in user id, which the relay supplies at sign-in. Anything that unlocks a capability must also
    // match this phone's own device id and identity key to the pin, and treats any failure as "not the admin".
    boolean verifiedAdmin() {
        if (!admin()) return false;
        try { requireLocalAdmin(); return true; }
        catch (Exception unavailable) { return false; }
    }
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
        requireOfficial(RemotePhotoSession.contact(peer));
        if (automatic(peer.userId())) requireLocalAdmin();
    }

    /** Same check when only the three identity fields are known (no display name), such as a remote-photo requester reported by the relay. */
    void requireOfficial(ChatEngine.Contact contact) throws Exception {
        if (official(contact.userId())) pin.require(contact);
    }

    void requireSaved(UUID userId) throws Exception {
        ChatEngine.Peer peer = read("contact/" + userId, ChatEngine.Peer.class);
        if (peer != null) requireOfficial(peer);
    }

    void requireCurrent(UUID userId, ChatEngine.Contact contact) throws Exception {
        if (official(userId)) pin.require(contact);
    }

    /**
     * True when a contact saved for the official admin is not the identity pinned now: what a changed pin leaves behind. Local only, and
     * only ever about the official admin account; nobody else's saved identity is treated this way.
     */
    boolean rotationPending(UUID userId) {
        if (!official(userId)) return false;
        ChatEngine.Peer saved = read("contact/" + userId, ChatEngine.Peer.class);
        return saved != null && !matchesPin(saved);
    }

    /**
     * The official admin account shown with an identity that is not the pinned one. Whoever approved that identity earlier (a person who
     * verified it, a group owner's roster), it is retired from the moment the pin moved: nothing is sent to it and nothing from it is taken.
     * False for every other account, and local only.
     */
    boolean retired(UUID userId, ChatEngine.Contact contact) { return official(userId) && !matches(contact); }

    /**
     * The same for something that only names a device (a queued message or group key addressed to it): the official admin account's device is
     * the pinned one, and anything encrypted for another one was made for an identity that is retired now, so it is not sent.
     */
    boolean retiredDevice(UUID userId, UUID deviceId) { return official(userId) && !pin.deviceId().equals(deviceId); }

    /**
     * True for a message sent by the device the saved contact stands for while that contact is the retired one (the pin moved after it was
     * saved). Such a message is not read; it is also not what a certificate could still make acceptable, because the device that sent it is
     * known and is not the pinned one. A message from any other device is not this: it may be the new phone, which a certificate can explain.
     */
    boolean fromRetiredContact(UUID userId, UUID deviceId) {
        if (!official(userId)) return false;
        ChatEngine.Peer saved = read("contact/" + userId, ChatEngine.Peer.class);
        return saved != null && saved.deviceId().equals(deviceId) && !matchesPin(saved);
    }

    private boolean matchesPin(ChatEngine.Peer peer) { return matches(RemotePhotoSession.contact(peer)); }

    private boolean matches(ChatEngine.Contact contact) {
        try { pin.require(contact); return true; }
        catch (Exception mismatch) { return false; }
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
        Set<UUID> users = new HashSet<>();
        for (Introduction contact : page.contacts()) {
            if (contact == null || contact.userId() == null || contact.deviceId() == null || contact.identityKey() == null
                    || engine.account().userId().equals(contact.userId()) || !users.add(contact.userId())
                    || !contact.identityKey().matches("[A-Za-z0-9+/]{44}"))
                throw new Failure("Invalid introduced identity");
            try { ChatEngine.validateProfile(new ChatEngine.Profile(contact.userId(), contact.handle(), contact.displayName()), contact.userId()); }
            catch (SecurityException | IllegalArgumentException invalid) { throw new Failure("Invalid introduced profile"); }
            ChatEngine.safetyNumber(contact.userId(), contact.identityKey());
            if (!admin()) pin.require(RemotePhotoSession.contact(contact.peer()));
        }
        if (page.nextAfter() != null && (page.contacts().size() != 64
                || !page.nextAfter().equals(page.contacts().get(63).userId())))
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
        return value.page().contacts().stream().filter(contact -> !dismissed(contact.userId())
                && !engine.safety().isBlocked(contact.userId())).map(Introduction::peer).toList();
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
        load(null);
    }
    private void load(UUID after) throws Exception {
        Page page;
        try { page = engine.groupApi().call("GET", "/account/admin-contacts" + (after == null ? "" : "?after=" + after), null, Page.class); }
        catch (com.google.gson.JsonParseException invalid) { throw new Failure("Invalid admin contact response"); }
        validate(page, after);
        Cached previous = cached(), updated = new Cached(after, page);
        boolean[] reviewChanged = new boolean[1];
        vault.transaction(() -> {
            if (!updated.equals(previous)) write(DIRECTORY, updated);
            // One contact whose identity changed is kept for review; it never stops the others on the page from being saved.
            for (Introduction contact : page.contacts())
                if (!dismissed(contact.userId()) && !engine.safety().isBlocked(contact.userId())) reviewChanged[0] |= introduce(contact.peer());
            return null;
        });
        if (!updated.equals(previous) || problem != null || reviewChanged[0]) { problem = null; generation++; }
        nextRefresh = System.currentTimeMillis() + 30_000;
    }

    /** Saves an introduced contact. True when the set of contacts waiting for review changed. */
    private boolean introduce(ChatEngine.Peer peer) throws Exception {
        try { store(peer); return clearChanged(peer.userId()); }
        catch (ChangedIdentity changed) { return keepForReview(peer); }
    }

    private boolean keepForReview(ChatEngine.Peer introduced) throws Exception {
        if (introduced.equals(read(CHANGED + introduced.userId(), ChatEngine.Peer.class))) return false;
        vault.transaction(() -> { write(CHANGED + introduced.userId(), introduced); return null; });
        return true;
    }

    private boolean clearChanged(UUID userId) {
        if (vault.get(CHANGED + userId) == null) return false;
        vault.remove(CHANGED + userId);
        return true;
    }

    /**
     * The identity the relay now introduces for a saved contact whose saved identity is different, or null. Only the admin account has any.
     * Nothing is sent to it and nothing from it is read until the person has looked at it and accepted it ({@link #acceptChanged}).
     */
    ChatEngine.Peer changed(UUID userId) {
        return admin() ? read(CHANGED + userId, ChatEngine.Peer.class) : null;
    }

    /** Contacts waiting for review, so a list can say which. */
    Set<UUID> changedContacts() {
        Set<UUID> result = new HashSet<>();
        if (!admin()) return result;
        for (String name : vault.names(CHANGED)) {
            try { result.add(UUID.fromString(name.substring(CHANGED.length()))); } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }

    /**
     * The person has looked at the new identity ({@code reviewedIdentityKey} is the one shown) and accepted it. It replaces the saved one only if
     * the relay still introduces exactly that identity now, so what was accepted is what is used; anything else changes nothing and asks again.
     * Accepting makes the contact an automatic one: it is no longer one this person verified, whatever it was before.
     */
    void acceptChanged(UUID userId, String reviewedIdentityKey) throws Exception {
        if (!admin() || official(userId) || !eligible(userId)) throw new SecurityException("This contact cannot be reviewed here");
        ChatEngine.Peer recorded = changed(userId);
        if (recorded == null || reviewedIdentityKey == null || !recorded.identityKey().equals(reviewedIdentityKey))
            throw new Failure("Review this contact's new identity again");
        ChatEngine.Peer current = lookup(userId);
        if (current == null || !RemotePhotoSession.contact(current).equals(RemotePhotoSession.contact(recorded))) {
            if (current != null && keepForReview(current)) generation++;
            throw new Failure("Their identity changed again. Review it again.");
        }
        vault.transaction(() -> { store(current, true); vault.remove(CHANGED + userId); return null; });
        generation++;
    }

    /**
     * A message arrived from a device that is not the one saved for a contact. When the relay introduces exactly that device, this is the
     * contact's new identity: it is kept for review, once, and the message waits at the relay. At most one look-up a minute per contact, only
     * about contacts that are not the official admin (that one has its own, certified, way), and a failed look-up changes nothing.
     */
    void noteUnrecognizedSender(UUID userId, UUID deviceId) throws Exception {
        if (!admin() || official(userId) || !eligible(userId)) return;
        ChatEngine.Peer recorded = read(CHANGED + userId, ChatEngine.Peer.class);
        if (recorded != null && recorded.deviceId().equals(deviceId)) return;
        long now = System.currentTimeMillis();
        Long next = nextChangeLookup.get(userId);
        if (next != null && now < next) return;
        nextChangeLookup.put(userId, now + CHANGE_LOOKUP_PAUSE);
        ChatEngine.Peer introduced;
        try { introduced = lookup(userId); }
        catch (AndroidVault.PhoneLockedException locked) { throw locked; }
        // Only the ways a look-up can fail for want of an answer: nothing is recorded and the next pass tries again after the pause.
        catch (java.io.IOException | SecurityException | com.google.gson.JsonParseException unavailable) { return; }
        ChatEngine.Peer saved = read("contact/" + userId, ChatEngine.Peer.class);
        if (introduced != null && saved != null && introduced.deviceId().equals(deviceId)
                && !RemotePhotoSession.contact(saved).equals(RemotePhotoSession.contact(introduced)) && keepForReview(introduced)) generation++;
    }

    private boolean eligible(UUID peerId) {
        return enabled() && !engine.account().userId().equals(peerId) && !dismissed(peerId)
                && !engine.safety().isBlocked(peerId) && (admin() || official(peerId));
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
        try { vault.transaction(() -> { store(contact); return null; }); }
        catch (ChangedIdentity changed) { if (keepForReview(contact)) generation++; return false; }
        return true;
    }

    boolean permitsPhotoRequest(ChatEngine.Peer peer) throws Exception {
        if (!eligible(peer.userId()) || !automatic(peer.userId())) return false;
        requireOfficial(peer);
        ChatEngine.Peer current = lookup(peer.userId());
        if (current == null || !RemotePhotoSession.contact(current).equals(RemotePhotoSession.contact(peer)))
            throw new Failure("The automatic connection could not be verified for this photo request.");
        return true;
    }

    /** True while a saved automatic connection still matches the signed-app admin pin; any doubt fails closed. */
    boolean connected(UUID userId) {
        try {
            ChatEngine.Peer saved = read("contact/" + userId, ChatEngine.Peer.class);
            if (saved == null || !automatic(userId) || !eligible(userId)) return false;
            requireOfficial(saved);
            return true;
        } catch (AndroidVault.PhoneLockedException locked) { throw locked; }
        catch (Exception invalid) { return false; }
    }

    /** The local half of preparing a chat: true when the contact is saved and still matches its pins; false needs the network. */
    boolean prepareSaved(ChatEngine.Peer peer) throws Exception {
        if (!engine.authenticated()) throw new SecurityException("Sign in before messaging");
        if (engine.safety().isBlocked(peer.userId())) throw new SecurityException("This account is blocked");
        ChatEngine.Peer saved = read("contact/" + peer.userId(), ChatEngine.Peer.class);
        if (saved == null) return false;
        // A saved official admin from before the pin changed is not refused: it is replaced from the network (prepare), which is the only
        // place that can show what the admin's identity is now, and only by an identity that matches the pin.
        if (official(peer.userId()) && !matchesPin(saved)) return false;
        if (!RemotePhotoSession.contact(saved).equals(RemotePhotoSession.contact(peer)))
            throw new SecurityException("Contact identity changed");
        requireOfficial(saved);
        return true;
    }

    void prepare(ChatEngine.Peer peer) throws Exception {
        if (prepareSaved(peer)) return;
        if (!eligible(peer.userId())) throw new SecurityException("Verify this contact before messaging");
        ChatEngine.Peer current = lookup(peer.userId());
        // When the saved copy is the retired one, the copy the screen holds is that one too, so the network's answer is held against the pin
        // (store requires it to match) and not against the screen's copy.
        boolean replacing = rotationPending(peer.userId());
        if (current == null || !replacing && !RemotePhotoSession.contact(current).equals(RemotePhotoSession.contact(peer)))
            throw new Failure("Introduced identity changed");
        vault.transaction(() -> { store(current); return null; });
    }

    private void store(ChatEngine.Peer peer) throws Exception { store(peer, false); }

    /** {@code accepted} is only ever true for an identity the person has just reviewed and accepted (see acceptChanged). */
    private void store(ChatEngine.Peer peer, boolean accepted) throws Exception {
        // Introductions never grant photo access; that requires separate per-session owner approval.
        requireOfficial(peer);
        ChatEngine.Peer previous = read("contact/" + peer.userId(), ChatEngine.Peer.class);
        boolean changed = previous != null && !RemotePhotoSession.contact(previous).equals(RemotePhotoSession.contact(peer));
        // requireOfficial has just shown that this is the pinned identity. A saved official admin that is not is the one the old pin
        // approved, so it gives way; any other saved identity is never replaced unless the person has just accepted this exact one.
        if (changed && !accepted && !(official(peer.userId()) && !matchesPin(previous))) throw new ChangedIdentity();
        if (changed) engine.groupSignal().forgetPeer(peer.userId());
        try { engine.groupSignal().verifyPeer(peer.userId(), Base64.getDecoder().decode(peer.identityKey())); }
        catch (SecurityException changedIdentity) { throw new Failure("Identity changed; automatic contact setup was stopped"); }
        if (previous == null || changed) {
            write(AUTOMATIC + peer.userId(), true);
            write("contact/" + peer.userId(), peer);
        }
    }

    void manuallyVerified(ChatEngine.Peer peer) throws Exception {
        requireOfficial(peer);
        vault.remove(AUTOMATIC + peer.userId());
        vault.remove(DISMISSED + peer.userId());
        vault.remove(CHANGED + peer.userId());
    }

    void dismiss(UUID peerId) {
        if (enabled() && (admin() || official(peerId))) write(DISMISSED + peerId, true);
        vault.remove(AUTOMATIC + peerId);
        vault.remove(CHANGED + peerId);
    }
}
