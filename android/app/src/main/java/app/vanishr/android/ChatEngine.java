package app.vanishr.android;

import android.content.Context;
import app.vanishr.crypto.*;
import okhttp3.WebSocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static app.vanishr.android.RelayApi.JSON;

final class ChatEngine implements AutoCloseable {
    static final String USERNAME_PATTERN = "[a-z0-9_-]{3,32}";
    static final String USERNAME_HINT = "Use 3-32 lowercase letters, numbers, underscores or hyphens";
    record Account(String origin, String handle, UUID userId, UUID deviceId, String accessToken, long expiresAt, boolean enrolled,
                   String refreshToken, long refreshExpiresAt) {
        Account(String origin, String handle, UUID userId, UUID deviceId, String accessToken, long expiresAt, boolean enrolled) {
            this(origin, handle, userId, deviceId, accessToken, expiresAt, enrolled, null, 0);
        }
        @Override public String toString() { return "Account[redacted]"; }
    }
    record Token(UUID userId, UUID deviceId, String accessToken, long expiresAt, String refreshToken, long refreshExpiresAt) {
        Token(UUID userId, UUID deviceId, String accessToken, long expiresAt) { this(userId, deviceId, accessToken, expiresAt, null, 0); }
        @Override public String toString() { return "Token[redacted]"; }
    }
    record Refresh(String refreshToken, String nextRefreshToken) { @Override public String toString() { return "Refresh[redacted]"; } }
    record Renewal(String token, String next, long expiresAt) { @Override public String toString() { return "Renewal[redacted]"; } }
    record Login(String handle, String password, UUID deviceId) { }
    record GoogleRequest(String challengeId, String idToken) {
        @Override public String toString() { return "GoogleRequest[redacted]"; }
    }
    record GoogleResponse(Token session, String handle) { }
    record DeviceRegistration(UUID deviceId, byte[] identityKey, boolean replaceExisting) { }
    record Peer(UUID userId, UUID deviceId, String identityKey, String name, String handle, String profileName) {
        Peer(UUID userId, UUID deviceId, String identityKey, String name) { this(userId, deviceId, identityKey, name, null, null); }
        String username() { return handle == null ? name : handle; }
    }
    record Username(UUID userId, String handle) { }
    record UsernameChange(String handle) { }
    record Profile(UUID userId, String handle, String displayName) { }
    record ProfileChange(String displayName) { }
    record Contact(UUID userId, UUID deviceId, String identityKey) { }
    record KeyCount(int remaining, boolean fallbackSupported, int fallbackKeyId, long fallbackExpiresAt) {
        KeyCount(int remaining) { this(remaining, false, 0, 0); }
    }
    record FallbackKey(PublicBundle key, long expiresAt) { }
    record Send(UUID id, UUID recipientId, UUID recipientDeviceId, ChatEnvelope.Expiry expiry, long expiresAt,
                int type, byte[] ciphertext, UUID mediaId) { }
    record Incoming(UUID id, UUID senderId, UUID senderDeviceId, UUID recipientId, UUID recipientDeviceId,
                    ChatEnvelope.Expiry expiry, long createdAt, long expiresAt, int type, byte[] ciphertext, UUID mediaId) { }
    record Outbox(Send message, byte[] media) { }
    record Status(UUID id, String state, long expiresAt) { }
    record Entry(UUID id, UUID peerId, long expiresAt, ChatEnvelope.Expiry expiry, boolean outgoing, boolean image, String state, UUID keyOwner,
                 UUID groupEpoch, long groupRevision, UUID senderId) {
        Entry(UUID id, UUID peerId, long expiresAt, ChatEnvelope.Expiry expiry, boolean outgoing, boolean image, String state, UUID keyOwner) {
            this(id,peerId,expiresAt,expiry,outgoing,image,state,keyOwner,null,0,null);
        }
        Entry(UUID id, UUID peerId, long expiresAt, ChatEnvelope.Expiry expiry, boolean outgoing, boolean image, String state) {
            this(id, peerId, expiresAt, expiry, outgoing, image, state, null);
        }
        Entry withState(String state) { return new Entry(id, peerId, expiresAt, expiry, outgoing, image, state, keyOwner,groupEpoch,groupRevision,senderId); }
    }
    record Content(ChatEnvelope envelope, byte[] image) { }
    record Ack(UUID id, long expiresAt, String action) { }
    record NotificationDestination(UUID userId, UUID deviceId, UUID conversationId, UUID messageId, long expiresAt) { }

    private final AndroidVault vault;
    private final GroupChat groups;
    private final ProfilePhotos photos;
    private final ContactPresence presence;
    private final AdminOnboarding onboarding;
    private final AccountBackup backup;
    private final AccountSafety safety;
    private Account account;
    private volatile RelayApi api;
    private volatile boolean cancelled;
    private SignalClient signal;
    private WebSocket socket;
    private volatile boolean realtimeReady;
    private volatile long connectionGeneration;
    private long nextConnect;
    private Runnable wake;
    private long nextKeyCheck;
    private long nextProfileCheck;
    private int profileCursor;
    boolean online;
    boolean unverifiedIncoming;

    ChatEngine(AndroidVault vault) throws Exception {
        this(vault, AdminOnboarding.OFFICIAL);
    }

    ChatEngine(AndroidVault vault, AdminOnboarding.Pin adminPin) throws Exception {
        this(vault, adminPin, AccountSafety::new);
    }

    ChatEngine(AndroidVault vault, AdminOnboarding.Pin adminPin,
               java.util.function.BiFunction<ChatEngine, AndroidVault, AccountSafety> safetyFactory) throws Exception {
        this.vault = vault;
        groups = new GroupChat(this,vault);
        photos = new ProfilePhotos(this,vault);
        presence = new ContactPresence(this);
        onboarding = new AdminOnboarding(this, vault, adminPin);
        backup = new AccountBackup(this, vault);
        safety = Objects.requireNonNull(safetyFactory.apply(this, vault));
        if (safety.deletionPending()) {
            try { safety.retryPendingDeletion(); }
            catch (AccountSafety.DeletionPendingException pending) {
                // The retry UI needs an engine, but account sessions and Signal must remain unopened.
                safety.retainPendingFailure(pending);
                return;
            }
        }
        if (safety.deletionCompleted()) return;
        account = read("account", Account.class);
        if (account != null) {
            useApi(new RelayApi(account.origin(), account.accessToken()));
            if (!authenticated() && account.accessToken() != null && !account.accessToken().isEmpty()) invalidateToken();
            if (account.enrolled()) api.sessionRefresh(this::refreshSession);
            signal = new SignalClient(account.userId(), vault.accountVault(account));
        }
        purge();
        Map<String, String> protectedRecords = new LinkedHashMap<>();
        for (String prefix : accountPrefixes()) {
            for (String name : vault.names(prefix + "entry/")) {
                Entry entry = read(name, Entry.class);
                protectedRecords.put(prefix + "body/" + entry.id(), AndroidVault.contentAlias(entry.expiresAt(), entry.id(), entry.keyOwner()));
            }
        }
        vault.migrateProtectedRecords(protectedRecords);
    }

    Account account() { return account; }
    GroupChat groups() { return groups; }
    ProfilePhotos photos() { return photos; }
    ContactPresence presence() { return presence; }
    AdminOnboarding onboarding() { return onboarding; }
    AccountBackup backup() { return backup; }
    AccountSafety safety() { return safety; }
    boolean independentlyVerified(UUID userId) {
        return signal != null && !safety.deletionPending() && !safety.isBlocked(userId) && signal.isVerified(userId)
                && vault.get("contact/" + userId) != null && !onboarding.automatic(userId);
    }
    void prepareConversation(Peer peer) throws Exception { safety.requireAllowed(peer.userId()); onboarding.prepare(peer); }
    boolean realtimeReady() { return realtimeReady; }
    RelayApi groupApi() { return api; }
    private void useApi(RelayApi next) {
        api = next;
        if (cancelled) next.cancelRequests();
    }
    SignalClient groupSignal() { return signal; }
    private boolean accessReady() { return !safety.deletionPending() && account != null && account.enrolled() && account.accessToken() != null && !account.accessToken().isEmpty() && account.expiresAt() > System.currentTimeMillis() + 5000; }
    private boolean remembered() { return !safety.deletionPending() && account != null && account.enrolled() && account.refreshToken() != null && account.refreshToken().matches("[A-Za-z0-9_-]{43}") && account.refreshExpiresAt() > System.currentTimeMillis() + 5000; }
    boolean authenticated() { return accessReady() || remembered(); }
    boolean usesGoogle() { return account != null && vault.get("google-account") != null; }
    UUID activeDeviceId() { return account != null && account.enrolled() ? account.deviceId() : null; }

    String displayName() {
        String saved = read("profile-name", String.class);
        return saved == null ? account.handle() : saved;
    }

    void renameProfile(String name) throws Exception {
        String normalized = validName(name, false);
        if (!authenticated()) throw new SecurityException("Sign in before editing your profile");
        applyProfile(api.call("PATCH", "/account/profile", new ProfileChange(normalized), Profile.class));
    }

    static String validUsername(String value) {
        String handle = value.strip().toLowerCase(Locale.ROOT);
        if (handle.startsWith("@")) handle = handle.substring(1);
        if (!handle.matches(USERNAME_PATTERN)) throw new IllegalArgumentException(USERNAME_HINT);
        return handle;
    }

    private static String validName(String value, boolean allowEmpty) {
        String name = value.strip();
        if ((!allowEmpty && name.isEmpty()) || name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a name of 1-40 characters");
        return name;
    }

    static void validateProfile(Profile profile, UUID userId) {
        if (profile == null || !userId.equals(profile.userId()) || profile.handle() == null || !profile.handle().matches(USERNAME_PATTERN))
            throw new SecurityException("Profile does not match the account");
        if (profile.displayName() != null && !validName(profile.displayName(), false).equals(profile.displayName()))
            throw new SecurityException("Invalid profile name");
    }

    void applyProfile(Profile profile) throws Exception {
        if (account == null) throw new SecurityException("No active account");
        validateProfile(profile, account.userId());
        Account updated = new Account(account.origin(), profile.handle(), account.userId(), account.deviceId(), account.accessToken(), account.expiresAt(), account.enrolled(), account.refreshToken(), account.refreshExpiresAt());
        vault.transaction(() -> {
            write("account", updated);
            if (profile.displayName() != null) write("profile-name", profile.displayName());
            return null;
        });
        account = updated;
    }

    void renameUsername(String value) throws Exception {
        String handle = validUsername(value);
        if (!authenticated()) throw new SecurityException("Sign in before changing your username");
        Username updated = api.call("PATCH", "/account/username", new UsernameChange(handle), Username.class);
        if (updated == null || !account.userId().equals(updated.userId()) || !handle.equals(updated.handle()))
            throw new SecurityException("Username update did not match");
        applyProfile(new Profile(updated.userId(), updated.handle(), null));
    }

    String privateName(Peer peer) {
        String name = read("contact-name/" + peer.userId(), String.class);
        return name == null ? "" : name;
    }

    void renameContact(Peer peer, String value) throws Exception {
        String nickname = validName(value, true);
        Peer saved = read("contact/" + peer.userId(), Peer.class);
        if (account == null || saved == null) throw new SecurityException("Contact is not saved in this account");
        String display = nickname.isEmpty() ? (saved.profileName() == null ? saved.username() : saved.profileName()) : nickname;
        vault.transaction(() -> {
            if (nickname.isEmpty()) vault.remove("contact-name/" + saved.userId()); else write("contact-name/" + saved.userId(), nickname);
            write("contact/" + saved.userId(), new Peer(saved.userId(), saved.deviceId(), saved.identityKey(), display, saved.username(), saved.profileName()));
            return null;
        });
    }

    void applyContactProfile(UUID userId, Profile profile) throws Exception {
        validateProfile(profile, userId);
        Peer saved = read("contact/" + userId, Peer.class);
        if (account == null || saved == null) throw new SecurityException("Contact is not saved in this account");
        String nickname = privateName(saved);
        String name = nickname.isEmpty() ? (profile.displayName() == null ? profile.handle() : profile.displayName()) : nickname;
        vault.transaction(() -> {
            write("contact/" + userId, new Peer(saved.userId(), saved.deviceId(), saved.identityKey(), name, profile.handle(), profile.displayName()));
            return null;
        });
    }

    private void refreshProfiles() throws Exception {
        if (System.currentTimeMillis() < nextProfileCheck) return;
        nextProfileCheck = System.currentTimeMillis() + 30_000;
        applyProfile(api.call("GET", "/account/profile", null, Profile.class));
        List<Peer> contacts = savedPeers();
        if (contacts.isEmpty()) { profileCursor = 0; return; }
        int count = Math.min(10, contacts.size());
        for (int index = 0; index < count; index++) {
            Peer peer = contacts.get((profileCursor + index) % contacts.size());
            try { applyContactProfile(peer.userId(), api.call("GET", "/users/id/" + peer.userId() + "/profile", null, Profile.class)); }
            catch (RelayApi.ApiFailure failure) { if (failure.status != 404) throw failure; }
        }
        profileCursor = (profileCursor + count) % contacts.size();
    }

    private static byte[] bytes(Object value) { return JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private <Value> Value read(String key, Class<Value> type) {
        byte[] value = vault.get(key);
        return value == null ? null : JSON.fromJson(new String(value, StandardCharsets.UTF_8), type);
    }
    private void write(String key, Object value) {
        if (safety != null && (safety.deletionPending() || safety.deletionCompleted()))
            throw new SecurityException("Account deletion prevents further writes");
        vault.put(key, bytes(value));
    }

    void login(String origin, String handle, String password, boolean register, boolean replaceExisting) throws Exception {
        safety.requireNotDeleting();
        purge();
        if (usesGoogle()) throw new SecurityException("Use Google sign-in for this account");
        if (account != null && !account.origin().equals(okhttp3.HttpUrl.get(origin).toString()))
            throw new SecurityException("Sign out before switching accounts");
        handle = validUsername(handle);
        if (password.length() < 16 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Use a valid handle and a 16-64 character password");
        if (api != null) api.close();
        useApi(new RelayApi(origin, null));
        UUID existingDevice = account == null ? null : account.deviceId();
        Token token;
        try { token = api.call("POST", register ? "/auth/register" : "/auth/login", new Login(handle, password, existingDevice), Token.class); }
        catch (RelayApi.ApiFailure failure) {
            if (register || failure.status != 401 || account == null || account.enrolled()) throw failure;
            token = api.call("POST", "/auth/login", new Login(handle, password, null), Token.class);
        }
        finishLogin(token, handle, existingDevice, replaceExisting, false);
    }

    void loginGoogle(String origin, GoogleSignIn.Challenge challenge, String idToken, boolean replaceExisting) throws Exception {
        safety.requireNotDeleting();
        purge();
        if (account != null && !usesGoogle()) throw new SecurityException("Account linking is not enabled");
        if (challenge.expiresAt() <= System.currentTimeMillis() || !BuildConfig.GOOGLE_WEB_CLIENT_ID.equals(challenge.clientId()))
            throw new SecurityException("Google sign-in challenge expired");
        if (account != null && !account.origin().equals(okhttp3.HttpUrl.get(origin).toString())) throw new SecurityException("Relay changed");
        if (api != null) api.close();
        useApi(new RelayApi(origin, null));
        GoogleResponse response = api.call("POST", "/auth/google", new GoogleRequest(challenge.id(), idToken), GoogleResponse.class);
        finishGoogleLogin(response, replaceExisting);
    }

    void finishGoogleLogin(GoogleResponse response, boolean replaceExisting) throws Exception {
        if (response == null || response.session() == null || response.handle() == null
                || (account != null && !account.userId().equals(response.session().userId())))
            throw new SecurityException("A different Google account was selected");
        finishLogin(response.session(), response.handle(), account == null ? null : account.deviceId(), replaceExisting, true);
    }

    private void finishLogin(Token token, String handle, UUID existingDevice, boolean replaceExisting, boolean google) throws Exception {
        safety.requireNotDeleting();
        if (token == null || token.userId() == null || token.accessToken() == null || token.expiresAt() <= System.currentTimeMillis())
            throw new SecurityException("Invalid authenticated session");
        if (account == null) {
            Account saved = read(AndroidVault.savedAccountPrefix(token.userId()) + "account", Account.class);
            if (saved != null) {
                if (!saved.userId().equals(token.userId()) || !saved.origin().equals(api.origin())
                        || google != (vault.get(AndroidVault.savedAccountPrefix(token.userId()) + "google-account") != null))
                    throw new SecurityException("Saved account does not match the authenticated account");
                vault.transaction(() -> {
                    vault.remove("google-account");
                    if (!vault.restoreAccount(token.userId())) throw new SecurityException("Saved account is unavailable");
                    return null;
                });
                account = saved;
                existingDevice = saved.deviceId();
            }
        }
        if (account != null && !account.userId().equals(token.userId())) throw new SecurityException("Account identity changed");
        UUID deviceId = existingDevice == null ? UUID.randomUUID() : existingDevice;
        if (token.deviceId() != null && !token.deviceId().equals(deviceId)) throw new SecurityException("Authenticated device does not match");
        Account signedIn = new Account(api.origin(), handle, token.userId(), deviceId, token.accessToken(), token.expiresAt(), token.deviceId() != null, token.refreshToken(), token.refreshExpiresAt());
        vault.transaction(() -> {
            write("account", signedIn);
            vault.remove("session-renewal");
            if (google) vault.put("google-account", new byte[]{1}); else vault.remove("google-account");
            return null;
        });
        account = signedIn;
        signal = new SignalClient(account.userId(), vault.accountVault(account));
        api.token(token.accessToken());
        if (token.deviceId() == null) {
            Token registered = api.call("POST", "/devices", new DeviceRegistration(deviceId, signal.publicIdentity(), replaceExisting), Token.class);
            if (registered == null || !token.userId().equals(registered.userId()) || !deviceId.equals(registered.deviceId())
                    || registered.accessToken() == null || registered.accessToken().isEmpty() || registered.expiresAt() <= System.currentTimeMillis())
                throw new SecurityException("Authenticated device does not match");
            account = new Account(api.origin(), handle, registered.userId(), deviceId, registered.accessToken(), registered.expiresAt(), true, registered.refreshToken(), registered.refreshExpiresAt());
            api.token(registered.accessToken());
            vault.transaction(() -> { write("account", account); return null; });
        }
        api.sessionRefresh(this::refreshSession);
        safety.authenticatedAccount();
        replenishKeys();
        online = true;
    }

    String identityCode() { return account.userId() + ":" + Base64.getEncoder().encodeToString(signal.publicIdentity()); }

    static String safetyNumber(UUID userId, String identityKey) throws Exception {
        byte[] key = Base64.getDecoder().decode(identityKey);
        if (userId == null || key.length != 33) throw new SecurityException("Invalid public identity");
        String canonical = userId + ":" + Base64.getEncoder().encodeToString(key);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII));
        StringBuilder number = new StringBuilder();
        for (int index = 0; index < digest.length; index++) {
            if (index > 0 && index % 4 == 0) number.append(' ');
            number.append(String.format(Locale.ROOT, "%02X", digest[index] & 0xff));
        }
        return number.toString();
    }

    String safetyNumber() throws Exception { return safetyNumber(account.userId(), Base64.getEncoder().encodeToString(signal.publicIdentity())); }

    List<Peer> peers() {
        Map<UUID, Peer> combined = new LinkedHashMap<>();
        for (Peer peer : savedPeers()) if (!safety.isBlocked(peer.userId())) combined.put(peer.userId(), peer);
        for (Peer peer : onboarding.peers()) if (!safety.isBlocked(peer.userId())) combined.putIfAbsent(peer.userId(), peer);
        List<Peer> result = new ArrayList<>(combined.values());
        result.sort(Comparator.comparing(Peer::name));
        return result;
    }

    private List<Peer> savedPeers() {
        List<Peer> peers = new ArrayList<>();
        for (String name : vault.names("contact/")) peers.add(read(name, Peer.class));
        peers.sort(Comparator.comparing(Peer::name));
        return peers;
    }

    Peer findPeer(String username) throws Exception {
        String handle = validUsername(username);
        if (!authenticated()) throw new SecurityException("Sign in before adding a contact");
        Contact contact = api.call("GET", "/users/" + handle, null, Contact.class);
        if (contact == null || contact.userId() == null || contact.deviceId() == null || contact.identityKey() == null)
            throw new SecurityException("Invalid contact response");
        if (contact.userId().equals(account.userId())) throw new IllegalArgumentException("Choose another account");
        safety.requireAllowed(contact.userId());
        safetyNumber(contact.userId(), contact.identityKey());
        Profile profile = api.call("GET", "/users/id/" + contact.userId() + "/profile", null, Profile.class);
        validateProfile(profile, contact.userId());
        return new Peer(contact.userId(), contact.deviceId(), contact.identityKey(), profile.displayName() == null ? profile.handle() : profile.displayName(), profile.handle(), profile.displayName());
    }

    void addPeer(Peer independentlyVerified) throws Exception {
        safety.refresh();
        safety.requireAllowed(independentlyVerified.userId());
        Peer current = findPeer(independentlyVerified.username());
        if (!current.userId().equals(independentlyVerified.userId()) || !current.deviceId().equals(independentlyVerified.deviceId())
                || !current.identityKey().equals(independentlyVerified.identityKey())) throw new SecurityException("Contact identity changed; verify it again");
        vault.transaction(() -> {
            onboarding.manuallyVerified(current);
            signal.verifyPeer(current.userId(), Base64.getDecoder().decode(current.identityKey()));
            write("contact/" + current.userId(), current);
            if (!privateName(current).isEmpty()) renameContact(current, privateName(current));
            return null;
        });
    }

    void clearChat(UUID peerId) throws Exception {
        if (account == null || peers().stream().noneMatch(peer -> peer.userId().equals(peerId)))
            throw new SecurityException("Contact is unavailable");
        for (String name : vault.names("entry/")) {
            Entry entry = read(name, Entry.class);
            if (entry.groupEpoch() == null && peerId.equals(entry.peerId())) erase(entry, null);
        }
    }

    void forget(Peer peer) throws Exception {
        PhotoSharingService.endFor(account.userId(), peer.userId());
        for (Entry entry : entries(peer.userId())) erase(entry, "delete");
        vault.transaction(() -> {
            photos.forget(peer);
            signal.forgetPeer(peer.userId());
            vault.remove("contact/" + peer.userId());
            vault.remove("contact-name/" + peer.userId());
            onboarding.dismiss(peer.userId());
            return null;
        });
    }

    void discardBlockedContact(UUID peerId) throws Exception {
        if (account == null || !safety.isBlocked(peerId)) throw new SecurityException("A blocked account is required");
        PhotoSharingService.endFor(account.userId(), peerId);
        presence.disconnected();
        for (String name : vault.names("entry/")) {
            Entry entry = read(name, Entry.class);
            if (entry.groupEpoch() == null && peerId.equals(entry.peerId())
                    || entry.groupEpoch() != null && !entry.outgoing() && peerId.equals(entry.senderId()))
                erase(entry, entry.outgoing() ? null : "delete");
        }
        Peer saved = read("contact/" + peerId, Peer.class);
        vault.transaction(() -> {
            for (String name : vault.names("outbox/"))
                if (peerId.equals(read(name, Outbox.class).message().recipientId())) vault.remove(name);
            if (saved != null) photos.forget(saved);
            else {
                for (String prefix : List.of("profile-photo-cache/", "profile-photo-request/", "profile-photo-grant/"))
                    vault.remove(prefix + peerId);
                for (String name : vault.names("profile-photo-out/"))
                    if (peerId.equals(read(name, ProfilePhotos.Queued.class).packet().recipientId())) vault.remove(name);
            }
            vault.remove("contact/" + peerId);
            vault.remove("contact-name/" + peerId);
            onboarding.dismiss(peerId);
            groups.discardBlockedControls(peerId);
            return null;
        });
        // Shared-group controls still need the pinned Signal ratchet, never direct-contact permission.
    }

    private void replenishKeys() throws Exception {
        signal.prunePreKeys(Instant.now().minusSeconds(172800));
        KeyCount count = api.call("GET", "/keys", null, KeyCount.class);
        if (count == null || count.remaining() < 0 || count.remaining() > 256)
            throw new SecurityException("Invalid public key inventory");
        if (count.fallbackSupported()) replenishFallback(count);
        byte[] pending = vault.get("public-upload");
        if (pending == null) {
            int target = groups.conversations().isEmpty() ? 16 : 224;
            if (count.remaining() >= target) return;
            int uploadCount = Math.min(32,target-count.remaining());
            pending = vault.transaction(() -> {
                signal.prunePreKeys(Instant.now().minusSeconds(172800));
                List<PublicBundle> keys = new ArrayList<>();
                for (int index = 0; index < uploadCount; index++) keys.add(signal.generatePreKey(Instant.now()));
                byte[] upload = bytes(Collections.singletonMap("keys", keys));
                vault.put("public-upload", upload);
                return upload;
            });
        }
        api.call("POST", "/keys", JSON.fromJson(new String(pending, StandardCharsets.UTF_8), com.google.gson.JsonObject.class), Void.class);
        vault.transaction(() -> { vault.remove("public-upload"); return null; });
    }

    private void replenishFallback(KeyCount count) throws Exception {
        long now = System.currentTimeMillis();
        FallbackKey saved = read("fallback-key", FallbackKey.class);
        if (saved == null || saved.expiresAt() - now <= 23L * 86400_000) {
            Instant expiresAt = Instant.ofEpochMilli(now + 30L * 86400_000 - 300_000);
            saved = vault.transaction(() -> {
                FallbackKey generated = new FallbackKey(signal.generateFallbackPreKey(Instant.ofEpochMilli(now), expiresAt), expiresAt.toEpochMilli());
                write("fallback-key", generated);
                return generated;
            });
        }
        if (count.fallbackKeyId() != saved.key().kyberPreKeyId() || count.fallbackExpiresAt() != saved.expiresAt())
            api.call("PUT", "/keys/fallback", saved, Void.class);
    }

    PublicBundle claimPreKey(UUID peerId) throws Exception {
        return api.call("POST", "/keys/" + peerId + "/claim?fallback=true", null, PublicBundle.class);
    }

    void send(Peer peer, String text, byte[] image, ChatEnvelope.Expiry expiry) throws Exception {
        send(peer, text, image, expiry, UUID.randomUUID(), System.currentTimeMillis(), () -> { });
    }

    void send(Peer peer, String text, byte[] image, ChatEnvelope.Expiry expiry, UUID id, long createdAt, Runnable stored) throws Exception {
        safety.requireAllowed(peer.userId());
        safety.refresh();
        safety.requireAllowed(peer.userId());
        long expiresAt = Math.addExact(createdAt, expiry.milliseconds);
        if (expiresAt <= System.currentTimeMillis()) throw new IllegalArgumentException("Message expired");
        purge();
        onboarding.prepare(peer);
        if (vault.names("entry/").size() >= 100) throw new IllegalStateException("Local message capacity reached");
        try {
            Contact current = api.call("GET", "/users/id/" + peer.userId(), null, Contact.class);
            onboarding.requireCurrent(peer.userId(), current);
            if (!current.deviceId().equals(peer.deviceId()) || !current.identityKey().equals(peer.identityKey()))
                throw new SecurityException("Peer device changed; independent verification is required");
        } catch (IOException failure) {
            online = false;
            if (failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status < 500) throw failure;
            if (!signal.hasSession(peer.userId())) throw failure;
        }
        if (!signal.hasSession(peer.userId())) {
            PublicBundle bundle = claimPreKey(peer.userId());
            signal.establish(peer.userId(), bundle, Instant.now());
        }
        Instant now = Instant.now();
        UUID mediaId = image == null ? null : UUID.randomUUID();
        ImageCipher.EncryptedImage encryptedImage = image == null ? null : ImageCipher.encrypt(mediaId, image);
        ChatEnvelope.Attachment attachment = encryptedImage == null ? null : new ChatEnvelope.Attachment(mediaId, encryptedImage.key(), encryptedImage.nonce(), "image/jpeg");
        ChatEnvelope envelope = new ChatEnvelope(1, id, account.userId(), account.deviceId(), peer.userId(), peer.deviceId(),
            createdAt, expiresAt, expiry, image == null ? text : null, attachment);
        envelope.verify(id, account.userId(), account.deviceId(), peer.userId(), peer.deviceId(), expiry, expiresAt, mediaId, now);
        Entry entry = new Entry(id, peer.userId(), expiresAt, expiry, true, image != null, "PENDING", account.userId());
        try {
            vault.transaction(() -> {
                safety.requireAllowed(peer.userId());
                SignalClient.Packet encrypted = signal.encrypt(peer.userId(), bytes(envelope), now);
                Send request = new Send(id, peer.userId(), peer.deviceId(), expiry, expiresAt, encrypted.type(), encrypted.ciphertext(), mediaId);
                write("outbox/" + id, new Outbox(request, encryptedImage == null ? null : encryptedImage.ciphertext()));
                write("entry/" + id, entry);
                storeContent(entry, new Content(envelope, image));
                return null;
            });
        } catch (Exception failure) { AndroidVault.deleteContentKey(expiresAt, id, account.userId()); throw failure; }
        finally { if (encryptedImage != null) Arrays.fill(encryptedImage.key(), (byte) 0); }
        stored.run();
        try { flushOutgoing(); }
        catch (IOException failure) {
            online = false;
            if (failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status == 401) invalidateToken();
        }
    }

    void storeContent(Entry entry, Content content) throws Exception {
        safety.requireNotDeleting();
        byte[] plaintext = bytes(content);
        try { vault.put("body/" + entry.id(), vault.seal(contentKey(entry), plaintext)); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private String contentKey(Entry entry) {
        if (account == null || (entry.keyOwner() != null && !entry.keyOwner().equals(account.userId())))
            throw new SecurityException("Content belongs to another account");
        return AndroidVault.contentAlias(entry.expiresAt(), entry.id(), entry.keyOwner());
    }

    List<Entry> entries(UUID peerId) {
        List<Entry> result = new ArrayList<>();
        for (String name : vault.names("entry/")) {
            Entry entry = read(name, Entry.class);
            if (entry.expiresAt() > System.currentTimeMillis() && (peerId == null || peerId.equals(entry.peerId()))
                    && !safety.isBlocked(entry.groupEpoch() == null ? entry.peerId() : entry.senderId())) result.add(entry);
        }
        result.sort(Comparator.comparingLong(entry -> entry.expiresAt() - entry.expiry().milliseconds));
        return result;
    }

    Content content(Entry entry, boolean consume) throws Exception {
        safety.requireAllowed(entry.groupEpoch() == null ? entry.peerId() : entry.senderId());
        if (entry.expiresAt() <= System.currentTimeMillis()) { erase(entry, null); throw new SecurityException("Message expired"); }
        if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE && !consume) throw new SecurityException("Explicit viewing is required");
        byte[] plaintext = vault.unseal(contentKey(entry), vault.get("body/" + entry.id()));
        try {
            Content content = JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), Content.class);
            if (entry.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) erase(entry, entry.outgoing() ? null : "read");
            else if (!entry.outgoing() && !entry.state().equals("READ")) markRead(entry);
            return content;
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private void markRead(Entry entry) throws Exception {
        vault.transaction(() -> {
            write("entry/" + entry.id(), entry.withState("READ"));
            if (entry.groupEpoch()!=null) groups.acknowledge(entry,"read");
            else write("ack/" + entry.id(), new Ack(entry.id(), entry.expiresAt(), "read"));
            return null;
        });
    }

    void erase(Entry entry, String acknowledgement) throws Exception {
        contentKey(entry);
        vault.deleteOwnedContentKey(entry, account.userId());
        vault.transaction(() -> {
            vault.remove("body/" + entry.id());
            vault.remove("entry/" + entry.id());
            vault.remove("outbox/" + entry.id());
            vault.remove("group-out/" + entry.id());
            if (acknowledgement != null && entry.expiresAt() > System.currentTimeMillis()) {
                if (entry.groupEpoch()!=null) groups.acknowledge(entry,acknowledgement);
                else write("ack/" + entry.id(), new Ack(entry.id(), entry.expiresAt(), acknowledgement));
            }
            return null;
        });
    }

    void purge() throws Exception {
        safety.requireNotDeleting();
        long now = System.currentTimeMillis();
        AndroidVault.expireContentKeys(now);
        vault.transaction(() -> {
            for (String prefix : accountPrefixes()) {
                groups.purge(prefix,now);
                photos.purge(prefix,now);
                Renewal pending = read(prefix + "session-renewal", Renewal.class);
                if (pending != null && pending.expiresAt() <= now) vault.remove(prefix + "session-renewal");
                for (String name : vault.names(prefix + "entry/")) {
                    Entry entry = read(name, Entry.class);
                    if (entry.expiresAt() <= now) {
                        vault.remove(name);
                        vault.remove(prefix + "body/" + entry.id());
                        vault.remove(prefix + "outbox/" + entry.id());
                    }
                }
                for (String name : vault.names(prefix + "outbox/")) if (read(name, Outbox.class).message().expiresAt() <= now) vault.remove(name);
                for (String name : vault.names(prefix + "ack/")) if (read(name, Ack.class).expiresAt() <= now) vault.remove(name);
                for (String name : vault.names(prefix + "seen/")) {
                    long deadline = Long.parseLong(new String(vault.get(name), StandardCharsets.US_ASCII));
                    if (deadline <= now) vault.remove(name);
                }
            }
            return null;
        });
    }

    private List<String> accountPrefixes() {
        List<String> prefixes = new ArrayList<>();
        prefixes.add("");
        for (String name : vault.names(AndroidVault.SAVED_ACCOUNT)) {
            if (!name.endsWith("/account")) continue;
            Account saved = read(name, Account.class);
            String prefix = AndroidVault.savedAccountPrefix(saved.userId());
            if (!name.equals(prefix + "account")) throw new SecurityException("Invalid saved account partition");
            prefixes.add(prefix);
        }
        return prefixes;
    }

    private void flushOutgoing() throws Exception {
        safety.requireNotDeleting();
        if (!vault.names("outbox/").isEmpty()) safety.refresh();
        for (String name : vault.names("outbox/")) {
            Outbox outbox = read(name, Outbox.class);
            if (outbox == null) continue;
            Send message = outbox.message();
            if (safety.isBlocked(message.recipientId())) { discardBlockedContact(message.recipientId()); continue; }
            if (message.expiresAt() <= System.currentTimeMillis()) continue;
            Status status;
            try {
                if (message.mediaId() != null) api.upload(message.mediaId(), message.recipientId(), message.recipientDeviceId(), message.expiresAt(), outbox.media());
                status = api.call("POST", "/messages", message, Status.class);
            } catch (RelayApi.ApiFailure failure) {
                if (failure.status != 403 && failure.status != 404) throw failure;
                vault.transaction(() -> {
                    Entry entry = read("entry/" + message.id(), Entry.class);
                    if (entry != null) write("entry/" + entry.id(), entry.withState("Not sent: contact unavailable"));
                    vault.remove(name);
                    return null;
                });
                continue;
            }
            if (safety.isBlocked(message.recipientId())) { discardBlockedContact(message.recipientId()); continue; }
            Entry previous = read("entry/" + message.id(), Entry.class);
            if (previous == null) { vault.transaction(() -> { vault.remove(name); return null; }); continue; }
            vault.transaction(() -> {
                write("entry/" + message.id(), previous.withState(status.state()));
                vault.remove(name);
                return null;
            });
            if (message.expiry() == ChatEnvelope.Expiry.VIEW_ONCE) {
                AndroidVault.deleteContentKey(message.expiresAt(), message.id(), previous.keyOwner());
                vault.transaction(() -> { vault.remove("body/" + message.id()); return null; });
            }
        }
    }

    private void flushAcks() throws Exception {
        for (String name : vault.names("ack/")) {
            Ack acknowledgement = read(name, Ack.class);
            try {
                api.call(acknowledgement.action().equals("delete") ? "DELETE" : "POST", "/messages/" + acknowledgement.id()
                        + (acknowledgement.action().equals("delete") ? "" : "/" + acknowledgement.action()), null, Void.class);
            } catch (RelayApi.ApiFailure failure) {
                if (failure.status != 404 && failure.status != 410
                        && !(failure.status == 403 && acknowledgement.action().equals("delete"))) throw failure;
            }
            vault.transaction(() -> { vault.remove(name); return null; });
        }
    }

    private void receive(Incoming message) throws Exception {
        if (message.expiresAt() <= System.currentTimeMillis()) return;
        if (safety.isBlocked(message.senderId())) {
            if (message.id() == null || !account.userId().equals(message.recipientId())
                    || !account.deviceId().equals(message.recipientDeviceId())
                    || message.expiresAt() > System.currentTimeMillis() + 86_400_000L)
                throw new SecurityException("Invalid blocked message routing");
            vault.transaction(() -> {
                byte[] seen = vault.get("seen/" + message.id());
                long deadline = seen == null ? message.expiresAt()
                        : Math.min(message.expiresAt(), Long.parseLong(new String(seen, StandardCharsets.US_ASCII)));
                write("ack/" + message.id(), new Ack(message.id(), deadline, "delete"));
                vault.put("seen/" + message.id(), Long.toString(deadline).getBytes(StandardCharsets.US_ASCII));
                return null;
            });
            return;
        }
        if (!signal.isVerified(message.senderId()) || read("contact/" + message.senderId(), Peer.class) == null)
            onboarding.acceptIncoming(message.senderId(), message.senderDeviceId());
        onboarding.requireSaved(message.senderId());
        Peer sender = read("contact/" + message.senderId(), Peer.class);
        if (!signal.isVerified(message.senderId()) || sender == null || !sender.deviceId().equals(message.senderDeviceId())) { unverifiedIncoming = true; return; }
        if (vault.get("seen/" + message.id()) != null) return;
        if (vault.names("entry/").size() >= 100) return;
        byte[] imageCiphertext = message.mediaId() == null ? null : api.download(message.mediaId());
        try {
            vault.transaction(() -> {
                safety.requireNotDeleting();
                byte[] plaintext = signal.decrypt(message.senderId(), new SignalClient.Packet(message.type(), message.ciphertext()));
                try {
                    ChatEnvelope envelope = JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ChatEnvelope.class);
                    envelope.verify(message.id(), message.senderId(), message.senderDeviceId(), account.userId(), account.deviceId(),
                            message.expiry(), message.expiresAt(), message.mediaId(), Instant.now());
                    byte[] image = imageCiphertext == null ? null : ImageCipher.decrypt(message.mediaId(), imageCiphertext,
                            envelope.image().key(), envelope.image().nonce());
                    Entry entry = new Entry(message.id(), message.senderId(), message.expiresAt(), message.expiry(), false, image != null, "DELIVERED", account.userId());
                    try { storeContent(entry, new Content(envelope, image)); }
                    finally { if (image != null) Arrays.fill(image, (byte) 0); }
                    write("entry/" + message.id(), entry);
                    write("ack/" + message.id(), new Ack(message.id(), message.expiresAt(), "delivered"));
                    vault.put("seen/" + message.id(), Long.toString(message.expiresAt()).getBytes(StandardCharsets.US_ASCII));
                } finally { Arrays.fill(plaintext, (byte) 0); }
                return null;
            });
        } catch (Exception failure) { AndroidVault.deleteContentKey(message.expiresAt(), message.id(), account.userId()); throw failure; }
    }

    void sync() throws Exception {
        safety.requireNotDeleting();
        purge();
        if (!authenticated()) {
            if (account != null && account.accessToken() != null && !account.accessToken().isEmpty()) invalidateToken();
            return;
        }
        try {
            safety.refresh();
            if (System.currentTimeMillis() >= nextKeyCheck) { replenishKeys(); nextKeyCheck = System.currentTimeMillis() + 30_000; }
            flushAcks();
            flushOutgoing();
            unverifiedIncoming = false;
            Incoming[] pending = api.call("GET", "/messages/pending", null, Incoming[].class);
            for (Incoming message : pending) receive(message);
            flushAcks();
            List<Status> statuses = new ArrayList<>();
            List<Entry> outgoing = new ArrayList<>();
            for (Entry entry : entries(null)) if (entry.outgoing() && entry.groupEpoch()==null) outgoing.add(entry);
            for (int offset = 0; offset < outgoing.size(); offset += 50) {
                List<String> ids = new ArrayList<>();
                for (Entry entry : outgoing.subList(offset, Math.min(outgoing.size(), offset + 50))) ids.add(entry.id().toString());
                Collections.addAll(statuses, api.call("GET", "/messages/status?ids=" + String.join(",", ids), null, Status[].class));
            }
            vault.transaction(() -> {
                for (Status status : statuses) {
                    Entry entry = read("entry/" + status.id(), Entry.class);
                    if (entry != null && entry.outgoing() && Arrays.asList("QUEUED", "DELIVERED", "READ", "DELETED").contains(status.state()))
                        write("entry/" + entry.id(), entry.withState(status.state()));
                }
                return null;
            });
            boolean hadGroups=!groups.conversations().isEmpty();
            groups.sync();
            if (!hadGroups && !groups.conversations().isEmpty()) nextKeyCheck=0;
            refreshProfiles();
            photos.sync();
            online = true;
            onboarding.refresh();
        } catch (RelayApi.ApiFailure failure) {
            online = false;
            if (failure.status == 401) invalidateToken();
            throw failure;
        } catch (IOException failure) { online = false; throw failure; }
    }

    void connect(Runnable wake) { this.wake = wake; reconnect(); }
    void reconnect() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cancelled || wake == null || !accessReady() || realtimeReady || now < nextConnect) return;
        nextConnect = now + 10_000;
        long generation = ++connectionGeneration;
        if (socket != null) socket.cancel();
        socket = api.events(wake, connected -> {
            if (generation != connectionGeneration) return;
            realtimeReady = connected;
            if (!connected) presence.disconnected();
        });
    }
        void pushToken(String token) throws Exception { api.call("POST", "/devices/push", Map.of("token", token, "routeHints", true), Void.class); }
    void disablePush() throws Exception { api.call("DELETE", "/devices/push", null, Void.class); }

        UUID openNotification(String reference) throws Exception {
        if (!authenticated() || !PushService.validReference(reference)) return null;
        NotificationDestination destination;
        try { destination = api.call("POST", "/notifications/resolve", Map.of("reference", reference), NotificationDestination.class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status == 404 || failure.status == 410) return null; throw failure; }
        if (destination == null || !account.userId().equals(destination.userId()) || !account.deviceId().equals(destination.deviceId())
            || destination.conversationId() == null || destination.messageId() == null || destination.expiresAt() <= System.currentTimeMillis()
            || destination.expiresAt() > System.currentTimeMillis() + PushService.ROUTE_LIFETIME) return null;
        sync();
        return notificationConversation(destination);
        }

        UUID notificationConversation(NotificationDestination destination) throws Exception {
        if (!authenticated() || destination == null || !account.userId().equals(destination.userId()) || !account.deviceId().equals(destination.deviceId())
            || destination.expiresAt() <= System.currentTimeMillis() || destination.messageId() == null || destination.conversationId() == null) return null;
        Entry entry = read("entry/" + destination.messageId(), Entry.class);
        if (entry == null || entry.outgoing() || !entry.peerId().equals(destination.conversationId()) || entry.expiresAt() <= System.currentTimeMillis()
            || !entry.state().equals("DELIVERED") || entry.keyOwner() != null && !account.userId().equals(entry.keyOwner())
            || safety.isBlocked(entry.groupEpoch() == null ? entry.peerId() : entry.senderId())
            || !vault.names("body/").contains("body/" + entry.id())) return null;
        if (entry.groupEpoch() != null) {
            GroupChat.Conversation group = groups.get(entry.peerId());
            return groups.ready(group) ? entry.peerId() : null;
        }
        Peer peer = peers().stream().filter(value -> value.userId().equals(entry.peerId())).findFirst().orElse(null);
        if (peer == null || !signal.isVerified(peer.userId())) return null;
        Contact current;
        try { current = api.call("GET", "/users/id/" + peer.userId(), null, Contact.class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status == 404) return null; throw failure; }
        if (current == null || !peer.userId().equals(current.userId()) || !peer.deviceId().equals(current.deviceId())
            || !peer.identityKey().equals(current.identityKey())) return null;
        signal.verifyPeer(peer.userId(), Base64.getDecoder().decode(peer.identityKey()));
        return peer.userId();
        }

    private Renewal prepareRenewal(Account owner) throws Exception {
        Renewal pending = read("session-renewal", Renewal.class);
        if (pending != null && pending.expiresAt() > System.currentTimeMillis() && owner.refreshToken().equals(pending.token())
                && pending.next() != null && pending.next().matches("[A-Za-z0-9_-]{43}") && !pending.next().equals(pending.token())) return pending;
        byte[] entropy = new byte[32]; new java.security.SecureRandom().nextBytes(entropy);
        String next;
        try { next = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); }
        finally { Arrays.fill(entropy, (byte) 0); }
        Renewal prepared = new Renewal(owner.refreshToken(), next, owner.refreshExpiresAt());
        vault.transaction(() -> { write("session-renewal", prepared); return null; });
        return prepared;
    }

    private void refreshSession(boolean rejected) throws Exception {
        safety.requireNotDeleting();
        if (!rejected && accessReady()) return;
        if (!remembered()) { invalidateToken(); throw new RelayApi.ApiFailure(401); }
        Account previous = account;
        Renewal pending = prepareRenewal(previous);
        Token token;
        try { token = api.call("POST", "/auth/refresh", new Refresh(pending.token(), pending.next()), Token.class); }
        catch (RelayApi.ApiFailure failure) {
            if (failure.status != 401) { if (failure.status == 403) invalidateToken(); throw failure; }
            Account recovering = new Account(previous.origin(), previous.handle(), previous.userId(), previous.deviceId(), "", 0, true, pending.next(), previous.refreshExpiresAt());
            vault.transaction(() -> { write("account", recovering); vault.remove("session-renewal"); return null; });
            account = recovering; api.token(null);
            pending = prepareRenewal(recovering);
            try { token = api.call("POST", "/auth/refresh", new Refresh(pending.token(), pending.next()), Token.class); }
            catch (RelayApi.ApiFailure recoveryFailure) {
                if (recoveryFailure.status == 401 || recoveryFailure.status == 403) invalidateToken();
                throw recoveryFailure;
            }
        }
        long now = System.currentTimeMillis();
        if (token == null || !previous.userId().equals(token.userId()) || !previous.deviceId().equals(token.deviceId())
                || token.accessToken() == null || !token.accessToken().matches("[A-Za-z0-9_-]{43}")
                || token.refreshToken() == null || !token.refreshToken().matches("[A-Za-z0-9_-]{43}")
                || !token.refreshToken().equals(pending.next()) || token.accessToken().equals(token.refreshToken())
                || token.expiresAt() <= now + 5000 || token.expiresAt() > now + 3_660_000
                || token.refreshExpiresAt() <= token.expiresAt() || token.refreshExpiresAt() > now + 2_592_060_000L) {
            invalidateToken();
            throw new SecurityException("Renewed session does not match the saved account");
        }
        Account updated = new Account(previous.origin(), previous.handle(), previous.userId(), previous.deviceId(), token.accessToken(), token.expiresAt(), true, token.refreshToken(), token.refreshExpiresAt());
        vault.transaction(() -> { write("account", updated); vault.remove("session-renewal"); return null; });
        account = updated;
        api.token(token.accessToken());
        connectionGeneration++; realtimeReady = false; nextConnect = 0;
        presence.disconnected();
        if (socket != null) socket.cancel();
        socket = null;
        if (wake != null) connect(wake);
    }

    /** Lets a background upload start with a token that will outlive it, without ending a still-usable session. */
    void renewAccessIfExpiringWithin(long millis) throws Exception {
        if (account == null || api == null || !authenticated()) throw new SecurityException("Sign in before using backup");
        if (accessReady() && account.expiresAt() - System.currentTimeMillis() >= millis) return;
        if (remembered()) refreshSession(true);
        else if (!accessReady()) throw new RelayApi.ApiFailure(401);
    }

    void invalidateToken() throws Exception {
        safety.requireNotDeleting();
        if (account == null) return;
        PhotoSharingService.endFor(account.userId(), null);
        connectionGeneration++; realtimeReady = false;
        presence.disconnected();
        account = new Account(account.origin(), account.handle(), account.userId(), account.deviceId(), "", 0, account.enrolled());
        if (socket != null) socket.cancel();
        socket = null;
        online = false;
        api.token(null);
        vault.transaction(() -> { write("account", account); vault.remove("session-renewal"); return null; });
    }

    void logout() throws Exception {
        safety.requireNotDeleting();
        if (account != null) PhotoSharingService.endFor(account.userId(), null);
        try {
            try { if (authenticated()) flushAcks(); }
            catch (IOException failure) { online = false; }
            finally { if (api != null && account != null && (remembered() || (account.accessToken() != null && !account.accessToken().isEmpty()))) api.call("POST", "/auth/logout", null, Void.class); }
        }
        catch (IOException failure) { online = false; }
        finally {
            try {
                if (account != null) {
                    Account signedOut = new Account(account.origin(), account.handle(), account.userId(), account.deviceId(), "", 0, account.enrolled());
                    vault.transaction(() -> { write("account", signedOut); vault.remove("session-renewal"); vault.saveAccount(signedOut.userId()); return null; });
                }
            } finally { online = false; close(); }
        }
    }

    void pauseForAccountDeletion() {
        if (account != null) PhotoSharingService.endFor(account.userId(), null);
        connectionGeneration++; realtimeReady = false;
        presence.disconnected();
        if (socket != null) socket.cancel();
        socket = null;
        online = false;
    }

    void finishAccountDeletion() {
        pauseForAccountDeletion();
        presence.foreground(false);
        if (api != null) api.close();
        api = null;
        signal = null;
        account = null;
        wake = null;
    }

    void cancelPendingRequests() {
        cancelled = true;
        connectionGeneration++; realtimeReady = false;
        presence.foreground(false);
        if (socket != null) socket.cancel();
        RelayApi current = api;
        if (current != null) current.cancelRequests();
    }

    @Override public void close() {
        cancelPendingRequests();
        if (api != null) api.close();
        socket = null;
        wake = null;
        api = null;
        signal = null;
        account = null;
        vault.close();
    }
}