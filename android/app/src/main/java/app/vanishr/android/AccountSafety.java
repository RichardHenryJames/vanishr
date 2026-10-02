package app.vanishr.android;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import okhttp3.*;

import static app.vanishr.android.RelayApi.JSON;

final class AccountSafety {
    enum Reason { SPAM, HARASSMENT, SEXUAL_CONTENT, CHILD_SAFETY, IMPERSONATION, THREATS, OTHER }
    record ReportReceipt(UUID id, long expiresAt) { }
    record Report(UUID id, UUID reporterId, UUID targetId, Reason reason, UUID messageId, UUID groupId,
                  long createdAt, long expiresAt) { }
    private record ReportRequest(UUID targetId, Reason reason, UUID messageId, UUID groupId) { }
    enum DeletionState { PENDING, DELETED }
    record DeletionStatus(UUID userId, DeletionState state, long expiresAt) { }
    private record DeletionRequest(String confirmation, String deletionProof) {
        @Override public String toString() { return "DeletionRequest[redacted]"; }
    }
    private record DeletionProof(String deletionProof) {
        @Override public String toString() { return "DeletionProof[redacted]"; }
    }
    static final class DeletionPendingException extends IOException {
        final boolean serverConfirmed;
        final int status;
        final String code;
        DeletionPendingException(boolean serverConfirmed) {
            this(serverConfirmed, null);
        }
        DeletionPendingException(boolean serverConfirmed, RelayApi.ApiFailure failure) {
            super(serverConfirmed
                    ? "The account was deleted on the server. Keep this app installed and unlock it to finish local cleanup."
                    : "Deletion could not be confirmed. Keep this app installed; do not sign in again until deletion is resolved.");
            this.serverConfirmed = serverConfirmed;
            status = failure == null ? 0 : failure.status;
            code = failure == null ? "" : failure.code;
        }
    }
    static final long REPORT_LIFETIME = 30L * 24 * 60 * 60 * 1000;
    static final long DELETION_PROOF_LIFETIME = 86_400_000L;
    private static final long CLOCK_SKEW = 30_000;
    static final int MAX_BLOCKS = 512;
    static final int MAX_REPORTS = 50;
    private static final long REFRESH_INTERVAL = 30_000;
    private static final String BLOCKED = "blocked/";
    private final ChatEngine engine;
    private final AndroidVault vault;
    private final Function<String, RelayApi> temporaryApis;
    private final Supplier<OkHttpClient> deletionClients;
    private UUID refreshedOwner;
    private long nextRefresh;
    private boolean refreshReady;
    private volatile boolean deleting;
    private volatile boolean deletionCompleted;
    private volatile DeletionPendingException pendingFailure;
    private boolean serverDeleted;

    AccountSafety(ChatEngine engine, AndroidVault vault) {
        this(engine, vault, origin -> new RelayApi(origin, null));
    }

    AccountSafety(ChatEngine engine, AndroidVault vault, Function<String, RelayApi> temporaryApis) {
        this(engine, vault, temporaryApis, () -> new OkHttpClient.Builder()
                .connectionSpecs(List.of(ConnectionSpec.MODERN_TLS)).followRedirects(false).followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build());
    }

    AccountSafety(ChatEngine engine, AndroidVault vault, Function<String, RelayApi> temporaryApis,
                  Supplier<OkHttpClient> deletionClients) {
        this.engine = Objects.requireNonNull(engine);
        this.vault = Objects.requireNonNull(vault);
        this.temporaryApis = Objects.requireNonNull(temporaryApis);
        this.deletionClients = Objects.requireNonNull(deletionClients);
        deleting = vault.get(AndroidVault.ACCOUNT_DELETION) != null;
    }

    boolean deletionPending() { return deleting; }
    boolean deletionCompleted() { return deletionCompleted; }
    DeletionPendingException pendingFailure() { return pendingFailure; }

    void retainPendingFailure(DeletionPendingException failure) {
        if (!deleting) throw new IllegalStateException("Deletion recovery is not pending");
        pendingFailure = Objects.requireNonNull(failure);
    }

    void requireNotDeleting() throws DeletionPendingException {
        if (deletionCompleted) throw new SecurityException("The deleted account is closed. Reopen Vanishr before starting another sign-in.");
        if (deleting) throw new DeletionPendingException(serverDeleted);
    }

    private ChatEngine.Account owner(boolean authenticated) throws Exception {
        requireNotDeleting();
        ChatEngine.Account owner = engine.account();
        if (owner == null || owner.userId() == null
                || authenticated && (!owner.enrolled() || owner.deviceId() == null || !engine.authenticated()))
            throw new SecurityException("Sign in to manage account safety");
        return owner;
    }

    private static void target(UUID id, UUID owner) {
        if (id == null || id.equals(new UUID(0, 0)) || id.equals(owner))
            throw new IllegalArgumentException("Choose another account");
    }

    private void requireCurrentAccount(ChatEngine.Account expected) {
        ChatEngine.Account current = engine.account();
        if (current == null || !expected.userId().equals(current.userId()) || !Objects.equals(expected.deviceId(), current.deviceId())
                || !expected.origin().equals(current.origin()))
            throw new SecurityException("The active account changed");
    }

    boolean isBlocked(UUID peerId) {
        return peerId != null && engine.account() != null && vault.get(BLOCKED + peerId) != null;
    }

    List<UUID> blockedUsers() {
        if (engine.account() == null) return List.of();
        List<UUID> users = new ArrayList<>();
        for (String name : vault.names(BLOCKED)) {
            UUID id = UUID.fromString(name.substring(BLOCKED.length()));
            if (!name.equals(BLOCKED + id)) throw new SecurityException("Invalid blocked account marker");
            target(id, engine.account().userId());
            users.add(id);
        }
        if (users.size() > MAX_BLOCKS) throw new SecurityException("Blocked account limit exceeded");
        users.sort(Comparator.naturalOrder());
        return List.copyOf(users);
    }

    void requireAllowed(UUID peerId) throws Exception {
        requireNotDeleting();
        if (isBlocked(peerId)) throw new SecurityException("This account is blocked");
    }

    void authenticatedAccount() throws Exception {
        nextRefresh = 0;
        refreshReady = false;
        refresh();
    }

    void refresh() throws Exception {
        ChatEngine.Account owner = owner(true);
        long now = SystemClock.elapsedRealtime();
        if (owner.userId().equals(refreshedOwner) && now < nextRefresh) {
            if (!refreshReady) throw new IOException("Safety settings could not be refreshed. Try again shortly.");
            return;
        }
        refreshedOwner = owner.userId();
        nextRefresh = now + REFRESH_INTERVAL;
        refreshReady = false;
        UUID[] response = engine.groupApi().call("GET", "/account/blocks", null, UUID[].class);
        if (response == null || response.length > MAX_BLOCKS) throw new SecurityException("Invalid blocked account list");
        Set<UUID> blocked = new HashSet<>();
        for (UUID peer : response) {
            target(peer, owner.userId());
            if (!blocked.add(peer)) throw new SecurityException("Duplicate blocked account");
        }
        requireCurrentAccount(owner);
        for (UUID peer : blocked) PhotoSharingService.endFor(owner.userId(), peer);
        List<UUID> previous = blockedUsers();
        for (UUID peer : previous) if (!blocked.contains(peer)) engine.discardBlockedContact(peer);
        vault.transaction(() -> {
            for (UUID peer : previous) if (!blocked.contains(peer)) vault.remove(BLOCKED + peer);
            for (UUID peer : blocked) vault.put(BLOCKED + peer, new byte[]{1});
            return null;
        });
        for (UUID peer : blocked) engine.discardBlockedContact(peer);
        refreshReady = true;
    }

    void block(ChatEngine.Peer peer) throws Exception {
        ChatEngine.Account owner = owner(true);
        target(Objects.requireNonNull(peer).userId(), owner.userId());
        if (!isBlocked(peer.userId()) && blockedUsers().size() >= MAX_BLOCKS)
            throw new IllegalStateException("The blocked account list is full");
        PhotoSharingService.endFor(owner.userId(), peer.userId());
        refreshReady = false;
        nextRefresh = 0;
        engine.groupApi().call("PUT", "/account/blocks/" + peer.userId(), null, Void.class);
        requireCurrentAccount(owner);
        vault.transaction(() -> { vault.put(BLOCKED + peer.userId(), new byte[]{1}); return null; });
        engine.discardBlockedContact(peer.userId());
    }

    void unblock(UUID peerId) throws Exception {
        ChatEngine.Account owner = owner(true);
        target(peerId, owner.userId());
        if (isBlocked(peerId)) engine.discardBlockedContact(peerId);
        engine.groupApi().call("DELETE", "/account/blocks/" + peerId, null, Void.class);
        requireCurrentAccount(owner);
        vault.transaction(() -> { vault.remove(BLOCKED + peerId); return null; });
        nextRefresh = 0;
        refreshReady = false;
    }

    ReportReceipt report(UUID targetId, Reason reason, ChatEngine.Entry entry) throws Exception {
        ChatEngine.Account owner = owner(true);
        target(targetId, owner.userId());
        if (reason == null) throw new IllegalArgumentException("Choose a report reason");
        UUID messageId = null;
        UUID groupId = null;
        if (entry != null) {
            if (entry.id() == null || entry.peerId() == null || entry.outgoing()
                    || owner.userId().equals(entry.senderId())
                    || entry.keyOwner() != null && !owner.userId().equals(entry.keyOwner())
                    || !targetId.equals(entry.groupEpoch() == null ? entry.peerId() : entry.senderId()))
                throw new SecurityException("Only this account's received messages can be reported");
            messageId = entry.id();
            groupId = entry.groupEpoch() == null ? null : entry.peerId();
        }
        ReportReceipt receipt = engine.groupApi().call("POST", "/safety/reports",
                new ReportRequest(targetId, reason, messageId, groupId), ReportReceipt.class);
        long now = System.currentTimeMillis();
        if (receipt == null || !validReportId(receipt.id()) || receipt.expiresAt() <= now
                || receipt.expiresAt() > now + REPORT_LIFETIME + CLOCK_SKEW)
            throw new SecurityException("Invalid safety report receipt");
        return receipt;
    }

    private static boolean validReportId(UUID id) { return id != null && id.version() == 4 && id.variant() == 2; }

    Report[] reports() throws Exception {
        owner(true);
        Report[] reports;
        try { reports = engine.groupApi().call("GET", "/safety/reports", null, Report[].class); }
        catch (RelayApi.ApiFailure failure) {
            if (failure.status == 403) throw new SecurityException("Only the current administrator can review safety reports");
            throw failure;
        }
        if (reports == null || reports.length > MAX_REPORTS) throw new SecurityException("Invalid safety report queue");
        long now = System.currentTimeMillis();
        Set<UUID> ids = new HashSet<>();
        for (Report report : reports) {
            if (report == null || !validReportId(report.id()) || !ids.add(report.id())
                    || report.reporterId() == null || report.targetId() == null || report.reporterId().equals(report.targetId())
                    || report.reason() == null
                    || report.createdAt() <= 0 || report.createdAt() > now + CLOCK_SKEW || report.expiresAt() <= now
                    || report.expiresAt() <= report.createdAt() || report.expiresAt() > now + REPORT_LIFETIME + CLOCK_SKEW
                    || report.expiresAt() - report.createdAt() > REPORT_LIFETIME)
                throw new SecurityException("Invalid safety report metadata");
        }
        return reports;
    }

    void resolveReport(UUID id) throws Exception {
        owner(true);
        if (!validReportId(id)) throw new IllegalArgumentException("Invalid safety report");
        try { engine.groupApi().call("DELETE", "/safety/reports/" + id, null, Void.class); }
        catch (RelayApi.ApiFailure failure) {
            if (failure.status == 403) throw new SecurityException("Only the current administrator can resolve safety reports");
            throw failure;
        }
    }

    boolean termsAccepted(String version) {
        validateVersion(version);
        byte[] accepted = vault.get("terms-version");
        try {
            return engine.account() != null && accepted != null
                    && version.equals(new String(accepted, StandardCharsets.US_ASCII));
        } finally { if (accepted != null) Arrays.fill(accepted, (byte) 0); }
    }

    void acceptTerms(String version) throws Exception {
        owner(true);
        validateVersion(version);
        vault.transaction(() -> { vault.put("terms-version", version.getBytes(StandardCharsets.US_ASCII)); return null; });
    }

    private static void validateVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Invalid terms version");
    }

    void deleteWithPassword(String password) throws Exception {
        ChatEngine.Account owner = owner(false);
        if (engine.usesGoogle()) throw new SecurityException("Confirm deletion with Google");
        if (password == null || password.length() < 16 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Enter your account password");
        try (RelayApi temporary = temporaryApis.apply(owner.origin())) {
            ChatEngine.Token authorization = temporary.call("POST", "/auth/login",
                    new ChatEngine.Login(owner.handle(), password, null), ChatEngine.Token.class);
            deleteAuthorized(owner, temporary, authorization);
        }
    }

    void deleteWithGoogle(GoogleSignIn.Challenge challenge, String idToken) throws Exception {
        ChatEngine.Account owner = owner(false);
        if (!engine.usesGoogle()) throw new SecurityException("Confirm deletion with your password");
        long now = System.currentTimeMillis();
        if (challenge == null || challenge.id() == null || !challenge.id().matches("[A-Za-z0-9_-]{43}")
                || challenge.nonce() == null || !challenge.nonce().matches("[A-Za-z0-9_-]{43}")
                || !BuildConfig.GOOGLE_WEB_CLIENT_ID.equals(challenge.clientId())
                || challenge.expiresAt() <= now || challenge.expiresAt() > now + 300_000
                || idToken == null || idToken.isBlank() || idToken.length() > 16_384)
            throw new SecurityException("Obtain a fresh Google sign-in challenge to confirm deletion");
        try (RelayApi temporary = temporaryApis.apply(owner.origin())) {
            ChatEngine.GoogleResponse response = temporary.call("POST", "/auth/google",
                    new ChatEngine.GoogleRequest(challenge.id(), idToken), ChatEngine.GoogleResponse.class);
            if (response == null) throw new SecurityException("Invalid deletion authorization");
            deleteAuthorized(owner, temporary, response.session());
        }
    }

    private void deleteAuthorized(ChatEngine.Account owner, RelayApi temporary, ChatEngine.Token token) throws Exception {
        long now = System.currentTimeMillis();
        if (token == null || !owner.userId().equals(token.userId()) || token.deviceId() != null
                || token.accessToken() == null || !token.accessToken().matches("[A-Za-z0-9_-]{43}")
                || token.accessToken().equals(owner.accessToken()) || token.accessToken().equals(owner.refreshToken())
                || token.refreshToken() != null || token.refreshExpiresAt() != 0
                || token.expiresAt() <= now || token.expiresAt() > now + 300_000
                || !temporary.origin().equals(owner.origin()) || engine.account() == null
                || !owner.userId().equals(engine.account().userId()) || !Objects.equals(owner.deviceId(), engine.account().deviceId()))
            throw new SecurityException("Deletion requires fresh authorization for this account, without an enrolled device");
        serverDeleted = false;
        byte[] entropy = new byte[32];
        new java.security.SecureRandom().nextBytes(entropy);
        String proof;
        try { proof = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); }
        finally { Arrays.fill(entropy, (byte) 0); }
        if (proof.equals(owner.accessToken()) || proof.equals(owner.refreshToken()) || proof.equals(token.accessToken()))
            throw new SecurityException("Deletion recovery needs a separate proof");
        vault.beginAccountDeletion(owner.userId(), owner.origin(), token.accessToken(), token.expiresAt(),
                proof, System.currentTimeMillis() + DELETION_PROOF_LIFETIME);
        deleting = true;
        engine.pauseForAccountDeletion();
        try { requestDeletion(temporary.origin(), token.accessToken(), proof); }
        catch (RelayApi.ApiFailure failure) {
            if (failure.status < 400 || failure.status >= 500) throw new DeletionPendingException(false, failure);
            vault.cancelAccountDeletion(owner.userId());
            deleting = false;
            throw failure;
        } catch (IOException failure) { throw new DeletionPendingException(false); }
        serverDeleted = true;
        finishLocalDeletion(owner.userId());
    }

    void retryPendingDeletion() throws Exception {
        pendingFailure = null;
        if (deletionCompleted) return;
        vault.expireDeletionAuthorization();
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        if (pending == null) throw new SecurityException("No account deletion is pending");
        deleting = true;
        PhotoSharingService.endFor(pending.userId(), null);
        engine.pauseForAccountDeletion();
        serverDeleted |= pending.confirmed();
        if (!serverDeleted) {
            try (RelayApi temporary = temporaryApis.apply(pending.origin())) {
                if (!pending.origin().equals(temporary.origin())) throw new SecurityException("The deletion relay changed");
                try { serverDeleted = recoverDeletion(temporary.origin(), pending); }
                catch (RelayApi.ApiFailure failure) { throw new DeletionPendingException(false, failure); }
                catch (IOException failure) { throw new DeletionPendingException(false); }
            }
            if (!serverDeleted) throw new DeletionPendingException(false);
        }
        finishLocalDeletion(pending.userId());
    }

    private boolean recoverDeletion(String origin, AndroidVault.AccountDeletion pending) throws Exception {
        if (pending.deletionProof() != null && pending.proofExpiresAt() > System.currentTimeMillis()) {
            DeletionStatus status;
            try {
                status = deletionCall(origin, null, "POST", "/account/deletion/status",
                        new DeletionProof(pending.deletionProof()), 200, DeletionStatus.class, 4096);
            } catch (RelayApi.ApiFailure failure) {
                if (failure.status != 404 || pending.token() == null || pending.expiresAt() <= System.currentTimeMillis()) throw failure;
                retryInitialDeletion(origin, pending);
                return true;
            }
            validateDeletionStatus(pending, status);
            if (status.state() == DeletionState.DELETED) return true;
            DeletionStatus retried = deletionCall(origin, null, "POST", "/account/deletion/retry",
                    new DeletionProof(pending.deletionProof()), 200, DeletionStatus.class, 4096);
            validateDeletionStatus(pending, retried);
            return retried.state() == DeletionState.DELETED;
        }
        retryInitialDeletion(origin, pending);
        return true;
    }

    private void retryInitialDeletion(String origin, AndroidVault.AccountDeletion pending) throws IOException {
        if (pending.token() == null || pending.expiresAt() <= System.currentTimeMillis())
            throw new DeletionPendingException(false);
        requestDeletion(origin, pending.token(), pending.deletionProof());
    }

    private void validateDeletionStatus(AndroidVault.AccountDeletion pending, DeletionStatus status) throws Exception {
        long now = System.currentTimeMillis();
        if (status == null || !pending.userId().equals(status.userId()) || status.state() == null
                || pending.proofExpiresAt() <= now || status.expiresAt() <= now || status.expiresAt() > now + DELETION_PROOF_LIFETIME + CLOCK_SKEW)
            throw new SecurityException("Deletion confirmation does not match the pending account");
        vault.boundDeletionProof(pending.userId(), status.expiresAt());
    }

    private void requestDeletion(String origin, String token, String proof) throws IOException {
        deletionCall(origin, token, "DELETE", "/account", new DeletionRequest("DELETE", proof), 204, Void.class, 0);
    }

    private <Result> Result deletionCall(String origin, String token, String method, String path, Object body,
                                        int expectedStatus, Class<Result> type, int maximum) throws IOException {
        // RelayApi.call discards success status; irreversible erasure must reject incomplete 202 replies.
        OkHttpClient client = deletionClients.get();
        try {
            Request.Builder request = new Request.Builder().url(Objects.requireNonNull(HttpUrl.get(origin).resolve(path)))
                    .header("Cache-Control", "no-store")
                    .method(method, RequestBody.create(JSON.toJson(body), MediaType.get("application/json")));
            if (token != null) request.header("Authorization", "Bearer " + token);
            try (Response response = client.newCall(request.build()).execute()) {
                if (!response.isSuccessful()) {
                    byte[] error = response.body() == null ? new byte[0]
                            : AndroidVault.boundedRead(response.body().byteStream(), 4096);
                    try { throw RelayApi.failure(response.code(), error); }
                    finally { Arrays.fill(error, (byte) 0); }
                }
                if (response.code() != expectedStatus) throw new IOException("The server did not confirm the deletion operation");
                byte[] result = response.body() == null ? new byte[0] : AndroidVault.boundedRead(response.body().byteStream(), maximum);
                try {
                    if (type == Void.class) return null;
                    return JSON.fromJson(new String(result, StandardCharsets.UTF_8), type);
                } catch (com.google.gson.JsonParseException failure) { throw new IOException("Invalid deletion confirmation"); }
                finally { Arrays.fill(result, (byte) 0); }
            }
        } finally {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private void finishLocalDeletion(UUID owner) throws Exception {
        try {
            vault.confirmAccountDeletion(owner);
            vault.stopAccountPush();
            vault.eraseAccount(owner);
        } catch (IOException | java.security.GeneralSecurityException | SecurityException | IllegalStateException failure) {
            throw new DeletionPendingException(true);
        }
        engine.finishAccountDeletion();
        deletionCompleted = true;
        deleting = false;
    }
}
