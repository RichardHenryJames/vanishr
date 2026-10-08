package app.vanishr.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.util.AtomicFile;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.vanishr.crypto.*;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AccountSafetyTest {
    private static final String ORIGIN = "https://account-safety.invalid/";
    private static final String PASSWORD = "synthetic-only-password";
    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID peerId = UUID.randomUUID();
    private final UUID peerDevice = UUID.randomUUID();
    private final List<RelayApi> transports = new ArrayList<>();
    private final List<okhttp3.Request> requests = new ArrayList<>();
    private final List<ChatEngine.Entry> keys = new ArrayList<>();
    private final Set<UUID> remoteBlocks = new LinkedHashSet<>();
    private File directory;
    private Context context;
    private AndroidVault vault;
    private ChatEngine engine;
    private SignalClient remote;
    private ChatEngine.Peer peer;
    private ChatEngine.Token authorization;
    private int deleteStatus = 204;
    private int blocksStatus = 200;
    private int reportStatus = 200;
    private int messageStatus = 200;
    private int proofStatus = 200;
    private int proofRetryStatus = 200;
    private boolean uncertainDelete;
    private boolean failPushStorage;
    private boolean backgroundBeforeConfirmation;
    private boolean recordDeletionProof = true;
    private String recordedProof;
    private long proofDeadline;
    private AccountSafety.DeletionState remoteDeletionState = AccountSafety.DeletionState.PENDING;
    private Object proofResponse;
    private Object proofRetryResponse;
    private Object blocksResponse;
    private Object receiptResponse;
    private Object reportsResponse = new AccountSafety.Report[0];
    private List<ChatEngine.Incoming> incoming = List.of();
    private AdminOnboarding.Page introductions;

    @Before public void freshIsolatedQaVault() throws Exception {
        Context application = ApplicationProvider.getApplicationContext();
        assertEquals("These tests may run only in the isolated QA application", "app.vanishr.android.qa", application.getPackageName());
        assertTrue("Use the isolated test emulator, never a user's phone",
                Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"));
        KeyguardManager keyguard = application.getSystemService(KeyguardManager.class);
        assertNotNull(keyguard);
        assertTrue("Unlock the credential-protected test emulator first", keyguard.isDeviceSecure() && !keyguard.isDeviceLocked());
        String fixture = "account-safety-" + UUID.randomUUID();
        directory = new File(application.getNoBackupFilesDir(), fixture);
        assertTrue(directory.mkdir());
        context = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                if (failPushStorage && name.equals("preferences")) throw new IllegalStateException("Synthetic local cleanup interruption");
                return application.getSharedPreferences(fixture + "-" + name, mode);
            }
        };
        vault = new AndroidVault(context);
        vault.unlock();
        ChatEngine.Account account = account(userId, deviceId, "fixture");
        vault.transaction(() -> { write("account", account); return null; });
        remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()),
                "Fixture peer", "peer", "Fixture peer");
        authorization = enrollment(userId);
        receiptResponse = new AccountSafety.ReportReceipt(UUID.randomUUID(), System.currentTimeMillis() + AccountSafety.REPORT_LIFETIME - 60_000);
        engine = new ChatEngine(vault);
        transport();
    }

    @After public void removeOnlyThisSyntheticFixture() throws Exception {
        if (context == null) return;
        failPushStorage = false;
        PhotoSharingService.endFor(userId, null);
        if (engine != null) engine.close();
        if (vault != null) vault.close();
        for (RelayApi transport : transports) transport.close();
        for (ChatEngine.Entry entry : keys) AndroidVault.deleteContentKey(entry.expiresAt(), entry.id(), entry.keyOwner());
        assertTrue(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().clear().commit());
        if (directory != null) {
            new AtomicFile(new File(directory, "vault.bin")).delete();
            assertTrue("The fixture directory must be empty", directory.delete());
        }
    }

    private static String opaque() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ChatEngine.Account account(UUID user, UUID device, String handle) {
        return new ChatEngine.Account(ORIGIN, handle, user, device, opaque(), System.currentTimeMillis() + 3_600_000,
                true, opaque(), System.currentTimeMillis() + 86_400_000L);
    }

    private static ChatEngine.Token enrollment(UUID user) {
        return new ChatEngine.Token(user, null, opaque(), System.currentTimeMillis() + 240_000);
    }

    private void write(String name, Object value) {
        vault.put(name, RelayApi.JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Class<?> type = owner instanceof Class<?> supplied ? supplied : owner.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner instanceof Class<?> ? null : owner, value);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void invoke(Object owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { method.invoke(owner, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }

    private RelayApi syntheticApi(String origin) {
        assertEquals(ORIGIN, origin);
        RelayApi api = new RelayApi(origin, null);
        try {
            OkHttpClient client = ((OkHttpClient) get(api, "client")).newBuilder().addInterceptor(this::intercept).build();
            set(api, "client", client);
        } catch (Exception failure) { throw new AssertionError(failure); }
        transports.add(api);
        return api;
    }

    private Response intercept(Interceptor.Chain chain) throws IOException {
        okhttp3.Request request = chain.request();
        assertEquals("No fixture may contact a real relay", "account-safety.invalid", request.url().host());
        requests.add(request);
        return dispatch(request);
    }

    private void transport() throws Exception {
        if (engine.groupApi() != null) engine.groupApi().close();
        RelayApi api = syntheticApi(ORIGIN);
        api.token(engine.account().accessToken());
        set(engine, "api", api);
        set(engine, "safety", new AccountSafety(engine, vault, this::syntheticApi,
                () -> new OkHttpClient.Builder().addInterceptor(this::intercept).build()));
    }

    private Response response(okhttp3.Request request, int status, Object body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
                .body(ResponseBody.create(body == null ? "" : RelayApi.JSON.toJson(body), MediaType.get("application/json"))).build();
    }

    private Response dispatch(okhttp3.Request request) throws IOException {
        String path = request.url().encodedPath();
        String method = request.method();
        if (path.equals("/auth/login")) return response(request, 200, authorization);
        if (path.equals("/auth/google")) return response(request, 200, new ChatEngine.GoogleResponse(authorization, "fixture"));
        if (path.equals("/account") && method.equals("DELETE")) {
            JsonObject deletion = body(request);
            if (recordDeletionProof && deletion.has("deletionProof") && (deleteStatus < 400 || deleteStatus >= 500)) {
                String proof = deletion.get("deletionProof").getAsString();
                if (recordedProof == null) {
                    recordedProof = proof;
                    proofDeadline = System.currentTimeMillis() + AccountSafety.DELETION_PROOF_LIFETIME - 30_000;
                }
                assertEquals(recordedProof, proof);
                remoteDeletionState = deleteStatus == 204 && !uncertainDelete
                        ? AccountSafety.DeletionState.DELETED : AccountSafety.DeletionState.PENDING;
            }
            if (uncertainDelete) throw new IOException("Synthetic connection interruption");
            if (backgroundBeforeConfirmation && deleteStatus == 204) engine.close();
            return response(request, deleteStatus, deleteStatus >= 500 ? Map.of("error", "account_cleanup_failed") : null);
        }
        if (path.equals("/account/deletion/status") || path.equals("/account/deletion/retry")) {
            assertNull("A recovery proof must never be accompanied by an account session", request.header("Authorization"));
            JsonObject proof = body(request);
            assertEquals(Set.of("deletionProof"), proof.keySet());
            boolean retry = path.endsWith("/retry");
            int status = retry ? proofRetryStatus : proofStatus;
            if (status != 200) return response(request, status, proofResponse);
            if (recordedProof == null || !recordedProof.equals(proof.get("deletionProof").getAsString()))
                return response(request, 404, Map.of("error", "deletion_proof_unavailable"));
            Object overridden = retry ? proofRetryResponse : proofResponse;
            if (overridden != null) return response(request, 200, overridden);
            if (retry) remoteDeletionState = AccountSafety.DeletionState.DELETED;
            return response(request, 200, new AccountSafety.DeletionStatus(userId, remoteDeletionState, proofDeadline));
        }
        if (path.equals("/account/blocks"))
            return response(request, blocksStatus, blocksResponse == null ? remoteBlocks : blocksResponse);
        if (path.startsWith("/account/blocks/")) {
            UUID blocked = UUID.fromString(path.substring("/account/blocks/".length()));
            if (method.equals("PUT")) remoteBlocks.add(blocked);
            else if (method.equals("DELETE")) remoteBlocks.remove(blocked);
            else throw new AssertionError("Unexpected block operation");
            return response(request, 204, null);
        }
        if (path.equals("/safety/reports"))
            return response(request, method.equals("POST") ? 201 : reportStatus, method.equals("POST") ? receiptResponse : reportsResponse);
        if (path.startsWith("/safety/reports/")) return response(request, reportStatus == 200 ? 204 : reportStatus, null);
        if (path.equals("/users/peer") || path.equals("/users/id/" + peerId))
            return response(request, 200, RemotePhotoSession.contact(peer));
        if (path.equals("/users/id/" + peerId + "/profile"))
            return response(request, 200, new ChatEngine.Profile(peerId, "peer", "Fixture peer"));
        if (path.equals("/account/profile")) return response(request, 200, new ChatEngine.Profile(userId, "fixture", null));
        if (path.equals("/messages/pending")) return response(request, 200, incoming);
        if (path.equals("/messages") && method.equals("POST")) {
            ChatEngine.Send message = RelayApi.JSON.fromJson(body(request), ChatEngine.Send.class);
            return response(request, messageStatus, new ChatEngine.Status(message.id(), "QUEUED", message.expiresAt()));
        }
        if (path.matches("/messages/[0-9a-f-]+(/(delivered|read))?")) return response(request, 204, null);
        if (path.equals("/keys")) return response(request, 200, new ChatEngine.KeyCount(224));
        if (Set.of("/groups", "/profile/packets", "/messages/status").contains(path)) return response(request, 200, List.of());
        if (path.equals("/account/admin-contacts") || path.equals("/account/admin-contacts/" + peerId)) {
            if (introductions == null) throw new AssertionError("Unexpected admin discovery");
            return response(request, 200, introductions);
        }
        throw new AssertionError("Unexpected synthetic safety route: " + path);
    }

    private static JsonObject body(okhttp3.Request request) throws IOException {
        assertNotNull(request.body());
        okio.Buffer bytes = new okio.Buffer();
        request.body().writeTo(bytes);
        return RelayApi.JSON.fromJson(bytes.readUtf8(), JsonObject.class);
    }

    private long requests(String path) {
        return requests.stream().filter(request -> request.url().encodedPath().equals(path)).count();
    }

    private void verifiedPeer() throws Exception {
        engine.groupSignal().verifyPeer(peerId, remote.publicIdentity());
        vault.transaction(() -> { write("contact/" + peerId, peer); return null; });
        assertTrue(engine.independentlyVerified(peerId));
    }

    private ChatEngine.Entry stored(UUID owner, UUID messageId, boolean outgoing, UUID group) throws Exception {
        long created = System.currentTimeMillis();
        UUID epoch = group == null ? null : UUID.randomUUID();
        ChatEngine.Entry entry = new ChatEngine.Entry(messageId, group == null ? peerId : group,
                created + ChatEnvelope.Expiry.HOUR_1.milliseconds, ChatEnvelope.Expiry.HOUR_1, outgoing, false,
                outgoing ? "PENDING" : "DELIVERED", owner, epoch, group == null ? 0 : 1, group == null ? null : peerId);
        ChatEnvelope envelope = new ChatEnvelope(1, messageId, outgoing ? owner : peerId, outgoing ? deviceId : peerDevice,
                group == null ? (outgoing ? peerId : owner) : group, group == null ? (outgoing ? peerDevice : deviceId) : epoch,
                created, entry.expiresAt(), entry.expiry(), "Synthetic private body, never report evidence", null);
        String prefix = owner.equals(userId) ? "" : AndroidVault.savedAccountPrefix(owner);
        byte[] content = RelayApi.JSON.toJson(new ChatEngine.Content(envelope, null)).getBytes(StandardCharsets.UTF_8);
        keys.add(entry);
        try {
            vault.transaction(() -> {
                write(prefix + "entry/" + messageId, entry);
                vault.put(prefix + "body/" + messageId, vault.seal(AndroidVault.contentAlias(entry.expiresAt(), messageId, owner), content));
                return null;
            });
        } finally { Arrays.fill(content, (byte) 0); }
        return entry;
    }

    private void queue(ChatEngine.Entry entry) throws Exception {
        ChatEngine.Send message = new ChatEngine.Send(entry.id(), peerId, peerDevice, entry.expiry(), entry.expiresAt(), 2, new byte[]{1}, null);
        vault.transaction(() -> { write("outbox/" + entry.id(), new ChatEngine.Outbox(message, null)); return null; });
    }

    @Test public void deletionUsesFreshEnrollmentWithoutChangingSessionOrRegisteringDevice() throws Exception {
        String currentToken = engine.account().accessToken();
        SignalClient previousSignal = engine.groupSignal();
        stored(userId, UUID.randomUUID(), false, null);
        engine.safety().deleteWithPassword(PASSWORD);
        assertEquals(1, requests("/auth/login"));
        assertEquals(1, requests("/account"));
        assertEquals(0, requests("/devices"));
        okhttp3.Request login = requests.stream().filter(request -> request.url().encodedPath().equals("/auth/login")).findFirst().orElseThrow();
        JsonObject loginBody = body(login);
        assertEquals("fixture", loginBody.get("handle").getAsString());
        assertTrue(!loginBody.has("deviceId") || loginBody.get("deviceId").isJsonNull());
        assertNull(login.header("Authorization"));
        okhttp3.Request deletion = requests.stream().filter(request -> request.url().encodedPath().equals("/account")).findFirst().orElseThrow();
        assertEquals(Set.of("confirmation", "deletionProof"), body(deletion).keySet());
        assertEquals("DELETE", body(deletion).get("confirmation").getAsString());
        String proof = body(deletion).get("deletionProof").getAsString();
        assertTrue(proof.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(authorization.accessToken(), proof);
        assertNotEquals(currentToken, proof);
        assertEquals("Bearer " + authorization.accessToken(), deletion.header("Authorization"));
        assertNotEquals("Bearer " + currentToken, deletion.header("Authorization"));
        assertNull(engine.account());
        assertNull(vault.get("account"));
        assertNull(vault.get("identity"));
        assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        assertTrue(engine.safety().deletionCompleted());
        assertFalse(engine.safety().deletionPending());
        assertThrows(SecurityException.class, engine::sync);
        assertThrows(SecurityException.class, () -> engine.login(ORIGIN, "fixture", PASSWORD, false, false));
        assertThrows(SecurityException.class, () -> previousSignal.verifyPeer(peerId, remote.publicIdentity()));
        assertTrue(vault.names("peer/").isEmpty());
        assertNull(vault.get("identity"));
        int sent = requests.size();
        engine.safety().retryPendingDeletion();
        assertEquals(sent, requests.size());
        assertTrue(engine.safety().deletionCompleted());
    }

    @Test public void retryWithoutAnOriginalDeletionCannotClaimSuccessForAnActiveAccount() throws Exception {
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        assertThrows(SecurityException.class, () -> engine.safety().retryPendingDeletion());
        assertTrue(engine.authenticated());
        assertFalse(engine.safety().deletionPending());
        assertFalse(engine.safety().deletionCompleted());
        assertArrayEquals(account, vault.get("account"));
        assertArrayEquals(identity, vault.get("identity"));
        assertTrue(requests.isEmpty());
    }

    @Test public void deletionDoesNotRequireEnrollmentOrAUsableOldSession() throws Exception {
        ChatEngine.Account retained = new ChatEngine.Account(ORIGIN, "fixture", userId, null, "", 0, false);
        vault.transaction(() -> { write("account", retained); return null; });
        set(engine, "account", retained);
        assertFalse(engine.authenticated());
        assertNull(engine.activeDeviceId());
        engine.safety().deleteWithPassword(PASSWORD);
        assertNull(engine.account());
        assertNull(vault.get("identity"));
        assertEquals(0, requests("/devices"));
        assertEquals(0, requests("/auth/refresh"));
    }

    @Test public void wrongAccountDeviceTokensAndUnboundedCredentialsCannotAuthorizeDeletion() throws Exception {
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        List<ChatEngine.Token> invalid = Arrays.asList(
                enrollment(UUID.randomUUID()),
                new ChatEngine.Token(userId, deviceId, opaque(), System.currentTimeMillis() + 240_000),
                new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() - 1),
                new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() + 600_000),
                new ChatEngine.Token(userId, null, "not-a-random-token", System.currentTimeMillis() + 240_000),
                new ChatEngine.Token(userId, null, engine.account().accessToken(), System.currentTimeMillis() + 240_000),
                new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() + 240_000, opaque(), System.currentTimeMillis() + 86_400_000));
        for (ChatEngine.Token token : invalid) {
            authorization = token;
            assertThrows(SecurityException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
            assertArrayEquals(account, vault.get("account"));
            assertArrayEquals(identity, vault.get("identity"));
            assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        }
        assertEquals(0, requests("/account"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void serverFailurePreservesAllLocalAccountCredentialsAndContent() throws Exception {
        ChatEngine.Entry entry = stored(userId, UUID.randomUUID(), false, null);
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        byte[] content = vault.get("body/" + entry.id());
        for (int status : List.of(400, 401, 403, 409, 429)) {
            deleteStatus = status;
            RelayApi.ApiFailure failure = assertThrows(RelayApi.ApiFailure.class, () -> engine.safety().deleteWithPassword(PASSWORD));
            assertEquals(status, failure.status);
            assertTrue(engine.authenticated());
            assertArrayEquals(account, vault.get("account"));
            assertArrayEquals(identity, vault.get("identity"));
            assertArrayEquals(content, vault.get("body/" + entry.id()));
            assertNotNull(vault.unseal(AndroidVault.contentAlias(entry.expiresAt(), entry.id(), userId), content));
            assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        }
        assertEquals(0, requests("/devices"));
    }

    @Test public void acceptedButIncompleteServerDeletionCannotEraseLocalAccount() throws Exception {
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        deleteStatus = 202;
        AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithPassword(PASSWORD));
        assertFalse(failure.serverConfirmed);
        assertArrayEquals(account, vault.get("account"));
        assertArrayEquals(identity, vault.get("identity"));
        assertFalse(engine.authenticated());
        assertNotNull(vault.pendingAccountDeletion());
    }
    @Test public void deletionErasesOnlyActiveOwnerAndPreservesSavedAccountAndContentKeys() throws Exception {
        UUID savedUser = UUID.randomUUID();
        UUID savedDevice = UUID.randomUUID();
        DeviceSecurityTest.MemoryVault savedVault = new DeviceSecurityTest.MemoryVault();
        new SignalClient(savedUser, savedVault);
        byte[] savedIdentity = savedVault.get("identity").clone();
        vault.transaction(() -> {
            write(AndroidVault.savedAccountPrefix(savedUser) + "account", account(savedUser, savedDevice, "saved"));
            for (String name : savedVault.names("")) vault.put(AndroidVault.savedAccountPrefix(savedUser) + name, savedVault.get(name));
            return null;
        });
        UUID sharedMessageId = UUID.randomUUID();
        ChatEngine.Entry active = stored(userId, sharedMessageId, false, null);
        ChatEngine.Entry saved = stored(savedUser, sharedMessageId, false, null);
        String savedBody = AndroidVault.savedAccountPrefix(savedUser) + "body/" + sharedMessageId;
        byte[] activeEncrypted = vault.get("body/" + sharedMessageId);
        byte[] savedEncrypted = vault.get(savedBody);
        SignalClient previousSignal = engine.groupSignal();
        engine.safety().deleteWithPassword(PASSWORD);
        assertNull(vault.get("identity"));
        assertNull(vault.get("account"));
        assertArrayEquals(savedIdentity, vault.get(AndroidVault.savedAccountPrefix(savedUser) + "identity"));
        assertArrayEquals(savedEncrypted, vault.get(savedBody));
        assertThrows(SecurityException.class, () -> vault.unseal(AndroidVault.contentAlias(active.expiresAt(), active.id(), userId), activeEncrypted));
        assertNotNull(vault.unseal(AndroidVault.contentAlias(saved.expiresAt(), saved.id(), savedUser), savedEncrypted));
        engine = new ChatEngine(vault);
        assertNull(engine.account());
        assertNull(vault.get("identity"));
        assertTrue(vault.restoreAccount(savedUser));
        engine = new ChatEngine(vault);
        assertEquals(savedUser, engine.account().userId());
        assertArrayEquals(savedIdentity, vault.get("identity"));
        assertThrows(SecurityException.class, () -> previousSignal.verifyPeer(peerId, remote.publicIdentity()));
        assertTrue(vault.names("peer/").isEmpty());
    }

    @Test public void ownerSpecificEraseRejectsAnotherOwnerAndRequiresConfirmedDeletion() throws Exception {
        byte[] identity = vault.get("identity");
        assertThrows(SecurityException.class, () -> vault.eraseAccount(userId));
        vault.beginAccountDeletion(userId, ORIGIN, authorization.accessToken(), authorization.expiresAt());
        assertThrows(SecurityException.class, () -> vault.eraseAccount(userId));
        vault.confirmAccountDeletion(userId);
        assertThrows(SecurityException.class, () -> vault.eraseAccount(UUID.randomUUID()));
        assertArrayEquals(identity, vault.get("identity"));
        assertNotNull(vault.get("account"));
    }

    @Test public void confirmedDeletionResumesBeforeSignalIdentityOrSessionsCanReopen() throws Exception {
        ChatEngine.Entry entry = stored(userId, UUID.randomUUID(), false, null);
        byte[] encrypted = vault.get("body/" + entry.id());
        vault.beginAccountDeletion(userId, ORIGIN, authorization.accessToken(), authorization.expiresAt());
        vault.confirmAccountDeletion(userId);
        engine.close();
        vault.unlock();
        engine = new ChatEngine(vault);
        assertNull(engine.account());
        assertNull(engine.groupSignal());
        assertNull(vault.get("identity"));
        assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        assertTrue(engine.safety().deletionCompleted());
        assertThrows(SecurityException.class, () -> vault.unseal(AndroidVault.contentAlias(entry.expiresAt(), entry.id(), userId), encrypted));
        assertTrue(requests.isEmpty());
    }

    @Test public void localCleanupFailureKeepsConfirmedTombstoneAndRestartFinishesWithoutAuthentication() throws Exception {
        ChatEngine.Entry entry = stored(userId, UUID.randomUUID(), false, null);
        byte[] encrypted = vault.get("body/" + entry.id());
        failPushStorage = true;
        AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithPassword(PASSWORD));
        assertTrue(failure.serverConfirmed);
        assertFalse(engine.authenticated());
        assertTrue(vault.pendingAccountDeletion().confirmed());
        assertNull(vault.pendingAccountDeletion().token());
        assertNull(vault.pendingAccountDeletion().deletionProof());
        assertNotNull(vault.get("identity"));
        engine.close();
        failPushStorage = false;
        vault.unlock();
        engine = new ChatEngine(vault);
        assertNull(engine.account());
        assertNull(engine.groupSignal());
        assertNull(vault.get("identity"));
        assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        assertThrows(SecurityException.class, () -> vault.unseal(AndroidVault.contentAlias(entry.expiresAt(), entry.id(), userId), encrypted));
        assertEquals(1, requests("/auth/login"));
        assertEquals(1, requests("/account"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void uncertainDeletionNeverErasesOrReauthenticatesAndRetriesOnlyOriginalProof() throws Exception {
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        uncertainDelete = true;
        AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithPassword(PASSWORD));
        assertFalse(failure.serverConfirmed);
        assertFalse(engine.authenticated());
        assertArrayEquals(account, vault.get("account"));
        assertArrayEquals(identity, vault.get("identity"));
        assertNotNull(vault.pendingAccountDeletion());
        assertThrows(AccountSafety.DeletionPendingException.class, engine::sync);
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.login(ORIGIN, "fixture", PASSWORD, false, false));
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        assertEquals(recordedProof, pending.deletionProof());
        assertTrue(pending.proofExpiresAt() <= System.currentTimeMillis() + AccountSafety.DELETION_PROOF_LIFETIME);
        expireEnrollmentProof();
        uncertainDelete = false;
        proofStatus = 503;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertArrayEquals(identity, vault.get("identity"));
        assertNotNull(vault.pendingAccountDeletion());
        assertNull(vault.pendingAccountDeletion().token());
        proofStatus = 200;
        proofRetryStatus = 503;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertArrayEquals(identity, vault.get("identity"));
        assertArrayEquals(account, vault.get("account"));
        proofRetryStatus = 200;
        engine.safety().retryPendingDeletion();
        assertNull(engine.account());
        assertEquals(1, requests("/auth/login"));
        assertEquals(1, requests("/account"));
        assertEquals(3, requests("/account/deletion/status"));
        assertEquals(2, requests("/account/deletion/retry"));
        assertEquals(0, requests("/devices"));
    }

    private void expireEnrollmentProof() throws Exception {
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        vault.transaction(() -> {
            write(AndroidVault.ACCOUNT_DELETION, new AndroidVault.AccountDeletion(pending.userId(), pending.origin(),
                    pending.legacyAliases(), false, pending.token(), System.currentTimeMillis() - 1,
                    pending.deletionProof(), pending.proofExpiresAt()));
            return null;
        });
    }

    @Test public void failedServerCleanupPreservesAllLocalDataAndItsSeparateRecoveryProof() throws Exception {
        ChatEngine.Entry entry = stored(userId, UUID.randomUUID(), false, null);
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        byte[] body = vault.get("body/" + entry.id());
        deleteStatus = 503;
        AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithPassword(PASSWORD));
        assertEquals(503, failure.status);
        assertEquals("account_cleanup_failed", failure.code);
        assertArrayEquals(account, vault.get("account"));
        assertArrayEquals(identity, vault.get("identity"));
        assertArrayEquals(body, vault.get("body/" + entry.id()));
        assertEquals(recordedProof, vault.pendingAccountDeletion().deletionProof());
        assertFalse(engine.authenticated());
        proofRetryResponse = new AccountSafety.DeletionStatus(userId, AccountSafety.DeletionState.PENDING,
                System.currentTimeMillis() + 60_000);
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertNotNull(vault.get("identity"));
        long bounded = vault.pendingAccountDeletion().proofExpiresAt();
        proofRetryResponse = null;
        proofRetryStatus = 503;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertEquals("Polling must never extend the recovery deadline", bounded, vault.pendingAccountDeletion().proofExpiresAt());
        assertArrayEquals(body, vault.get("body/" + entry.id()));
    }

    @Test public void restartRecoversLostSuccessByProofBeforeOpeningAnySignalIdentity() throws Exception {
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        expireEnrollmentProof();
        remoteDeletionState = AccountSafety.DeletionState.DELETED;
        engine.close();
        vault.unlock();
        engine = recoveryEngine();
        assertNull(engine.account());
        assertNull(engine.groupSignal());
        assertNull(vault.get("identity"));
        assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        assertTrue(engine.safety().deletionCompleted());
        assertEquals(1, requests("/auth/login"));
        assertEquals(1, requests("/account"));
        assertEquals(1, requests("/account/deletion/status"));
        assertEquals(0, requests("/account/deletion/retry"));
        assertEquals(0, requests("/devices"));
    }

    private ChatEngine recoveryEngine() throws Exception {
        return new ChatEngine(vault, AdminOnboarding.OFFICIAL, (current, storage) ->
                new AccountSafety(current, storage, this::syntheticApi,
                        () -> new OkHttpClient.Builder().addInterceptor(this::intercept).build()));
    }

    private GoogleSignIn.Challenge deletionChallenge() {
        return new GoogleSignIn.Challenge(opaque(), opaque(), BuildConfig.GOOGLE_WEB_CLIENT_ID,
                System.currentTimeMillis() + 240_000);
    }

    @Test public void googleDeletionAcceptsTheSameBoundedClockSkewAsChallengePreparation() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        GoogleSignIn.Challenge challenge = new GoogleSignIn.Challenge(opaque(), opaque(), BuildConfig.GOOGLE_WEB_CLIENT_ID,
                System.currentTimeMillis() + 310_000);
        engine.safety().deleteWithGoogle(challenge, "synthetic-provider-proof");
        assertTrue(engine.safety().deletionCompleted());
        assertEquals(1, requests("/auth/google"));
        assertEquals(1, requests("/account"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void googleDeletionBoundsFreshServerAuthorizationToFiveLocalMinutesAcrossRecovery() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        authorization = new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() + 310_000);
        uncertainDelete = true;
        long started = System.currentTimeMillis();
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "synthetic-provider-proof"));
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        assertNotNull(pending);
        assertTrue(pending.expiresAt() >= started + 299_000);
        assertTrue(pending.expiresAt() <= System.currentTimeMillis() + 300_000);
        assertTrue(pending.expiresAt() < authorization.expiresAt());
        assertEquals(authorization.accessToken(), pending.token());
        assertFalse(engine.safety().deletionCompleted());
        assertNotNull(vault.get("identity"));
        uncertainDelete = false;
        engine.safety().retryPendingDeletion();
        assertTrue(engine.safety().deletionCompleted());
        assertEquals(1, requests("/auth/google"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void passwordDeletionUsesTheSameBoundedFreshAuthorizationWindow() throws Exception {
        authorization = new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() + 310_000);
        engine.safety().deleteWithPassword(PASSWORD);
        assertTrue(engine.safety().deletionCompleted());
        assertEquals(1, requests("/auth/login"));
        assertEquals(1, requests("/account"));
    }

    @Test public void deletionRejectsBeyondThirtySecondsClockToleranceAndKeepsOriginalDeadlines() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        byte[] account = vault.get("account"), identity = vault.get("identity");
        GoogleSignIn.Challenge excessive = new GoogleSignIn.Challenge(opaque(), opaque(), BuildConfig.GOOGLE_WEB_CLIENT_ID,
                System.currentTimeMillis() + 331_000);
        assertThrows(AccountSafety.DeletionAuthorizationException.class,
                () -> engine.safety().deleteWithGoogle(excessive, "synthetic-provider-proof"));
        assertEquals(0, requests("/auth/google"));
        authorization = new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() + 331_000);
        assertThrows(AccountSafety.DeletionAuthorizationException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "synthetic-provider-proof"));
        assertEquals(0, requests("/account"));
        assertArrayEquals(account, vault.get("account")); assertArrayEquals(identity, vault.get("identity"));
        assertNull(vault.pendingAccountDeletion());

        authorization = new ChatEngine.Token(userId, null, opaque(), System.currentTimeMillis() + 240_000);
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "synthetic-provider-proof"));
        long deadline = vault.pendingAccountDeletion().expiresAt();
        assertEquals(authorization.expiresAt(), deadline);
        proofStatus = 503;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertEquals(deadline, vault.pendingAccountDeletion().expiresAt());
        assertEquals(2, requests("/auth/google"));
    }

    @Test public void backgroundBeforeConfirmationRetainsProofAndNeverRepeatsGoogleAuthentication() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        byte[] identity = vault.get("identity");
        backgroundBeforeConfirmation = true;
        AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "synthetic-provider-proof"));
        assertTrue(failure.serverConfirmed);
        assertThrows(SecurityException.class, () -> vault.get("identity"));
        vault.unlock();
        assertArrayEquals(identity, vault.get("identity"));
        assertFalse(vault.pendingAccountDeletion().confirmed());
        assertEquals(recordedProof, vault.pendingAccountDeletion().deletionProof());
        assertEquals(AccountSafety.DeletionState.DELETED, remoteDeletionState);
        expireEnrollmentProof();
        engine = recoveryEngine();
        assertNull(engine.account());
        assertNull(engine.groupSignal());
        assertNull(vault.get("identity"));
        assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
        assertTrue(engine.safety().deletionCompleted());
        assertThrows(SecurityException.class,
                () -> engine.loginGoogle(ORIGIN, deletionChallenge(), "must-not-be-exchanged", false));
        assertThrows(SecurityException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "must-not-be-exchanged"));
        assertEquals(1, requests("/auth/google"));
        assertEquals(0, requests("/auth/login"));
        assertEquals(1, requests("/account"));
        assertEquals(1, requests("/account/deletion/status"));
        assertEquals(0, requests("/account/deletion/retry"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void unauthorizedAndMissingRecoveryReceiptsAreNeverDeletionConfirmation() throws Exception {
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        expireEnrollmentProof();
        for (int status : List.of(401, 404)) {
            proofStatus = status;
            AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                    () -> engine.safety().retryPendingDeletion());
            assertEquals(status, failure.status);
            assertFalse(failure.serverConfirmed);
            assertArrayEquals(account, vault.get("account"));
            assertArrayEquals(identity, vault.get("identity"));
            assertFalse(vault.pendingAccountDeletion().confirmed());
            assertEquals(recordedProof, vault.pendingAccountDeletion().deletionProof());
        }
        assertEquals(1, requests("/account"));
        assertEquals(1, requests("/auth/login"));
        assertEquals(0, requests("/auth/google"));
        assertEquals(0, requests("/account/deletion/retry"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void rejectedOriginalDeletionReplayAlsoRetainsThePendingAccount() throws Exception {
        byte[] account = vault.get("account");
        byte[] identity = vault.get("identity");
        recordDeletionProof = false;
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        uncertainDelete = false;
        for (int status : List.of(401, 404)) {
            deleteStatus = status;
            AccountSafety.DeletionPendingException failure = assertThrows(AccountSafety.DeletionPendingException.class,
                    () -> engine.safety().retryPendingDeletion());
            assertEquals(status, failure.status);
            assertFalse(failure.serverConfirmed);
            assertArrayEquals(account, vault.get("account"));
            assertArrayEquals(identity, vault.get("identity"));
            assertEquals(pending, vault.pendingAccountDeletion());
        }
        assertEquals(3, requests("/account"));
        assertEquals(2, requests("/account/deletion/status"));
        assertEquals(0, requests("/account/deletion/retry"));
        assertEquals(1, requests("/auth/login"));
        assertEquals(0, requests("/auth/google"));
        assertEquals(0, requests("/devices"));
        for (okhttp3.Request request : requests) if (request.url().encodedPath().equals("/account")) {
            assertEquals("Bearer " + pending.token(), request.header("Authorization"));
            assertEquals(pending.deletionProof(), body(request).get("deletionProof").getAsString());
        }
    }

    @Test public void coldStartPendingEngineAllowsCleanupRetryButCannotStartGoogleOrPasswordLogin() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        byte[] identity = vault.get("identity");
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "synthetic-provider-proof"));
        expireEnrollmentProof();
        engine.close();
        vault.unlock();
        proofStatus = 503;
        engine = recoveryEngine();
        assertTrue(engine.safety().deletionPending());
        assertFalse(engine.safety().deletionCompleted());
        assertFalse(engine.authenticated());
        assertNull(engine.account());
        assertNull(engine.groupApi());
        assertNull(engine.groupSignal());
        assertEquals(503, engine.safety().pendingFailure().status);
        assertArrayEquals(identity, vault.get("identity"));
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.loginGoogle(ORIGIN, deletionChallenge(), "must-not-be-exchanged", false));
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.safety().deleteWithGoogle(deletionChallenge(), "must-not-be-exchanged"));
        assertThrows(AccountSafety.DeletionPendingException.class,
                () -> engine.login(ORIGIN, "fixture", PASSWORD, false, false));
        proofStatus = 200;
        remoteDeletionState = AccountSafety.DeletionState.DELETED;
        engine.safety().retryPendingDeletion();
        assertFalse(engine.safety().deletionPending());
        assertTrue(engine.safety().deletionCompleted());
        assertNull(engine.safety().pendingFailure());
        assertNull(engine.account());
        assertNull(engine.groupSignal());
        assertNull(vault.get("identity"));
        assertEquals(1, requests("/auth/google"));
        assertEquals(0, requests("/auth/login"));
        assertEquals(1, requests("/account"));
        assertEquals(2, requests("/account/deletion/status"));
        assertEquals(0, requests("/account/deletion/retry"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void invalidOrIncompleteRecoveryMetadataNeverErasesThePendingAccount() throws Exception {
        byte[] identity = vault.get("identity");
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        expireEnrollmentProof();
        long now = System.currentTimeMillis();
        for (Object invalid : List.of(
                new AccountSafety.DeletionStatus(UUID.randomUUID(), AccountSafety.DeletionState.DELETED, proofDeadline),
                new AccountSafety.DeletionStatus(userId, null, proofDeadline),
                new AccountSafety.DeletionStatus(userId, AccountSafety.DeletionState.DELETED, now - 1),
                new AccountSafety.DeletionStatus(userId, AccountSafety.DeletionState.DELETED, now + AccountSafety.DELETION_PROOF_LIFETIME + 60_000),
                Map.of("userId", userId, "state", "UNKNOWN", "expiresAt", proofDeadline))) {
            proofResponse = invalid;
            assertThrows(SecurityException.class, () -> engine.safety().retryPendingDeletion());
            assertArrayEquals(identity, vault.get("identity"));
            assertNotNull(vault.pendingAccountDeletion());
        }
        proofResponse = new AccountSafety.DeletionStatus(userId, AccountSafety.DeletionState.DELETED, proofDeadline);
        proofStatus = 202;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertArrayEquals(identity, vault.get("identity"));
        assertEquals(1, requests("/auth/login"));
        assertEquals(0, requests("/account/deletion/retry"));
    }

    @Test public void expiredRecoveryProofCannotSignInOrEraseDataAndIsRemovedFromTheTombstone() throws Exception {
        byte[] identity = vault.get("identity");
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        vault.transaction(() -> {
            write(AndroidVault.ACCOUNT_DELETION, new AndroidVault.AccountDeletion(pending.userId(), pending.origin(),
                    pending.legacyAliases(), false, pending.token(), System.currentTimeMillis() - 1,
                    pending.deletionProof(), System.currentTimeMillis() - 1));
            return null;
        });
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().retryPendingDeletion());
        assertArrayEquals(identity, vault.get("identity"));
        assertNull(vault.pendingAccountDeletion().token());
        assertNull(vault.pendingAccountDeletion().deletionProof());
        assertFalse(vault.pendingAccountDeletion().confirmed());
        assertEquals(0, requests("/account/deletion/status"));
        assertEquals(0, requests("/account/deletion/retry"));
        assertEquals(1, requests("/auth/login"));
        assertEquals(0, requests("/devices"));
    }

    @Test public void unavailableReceiptCanRetryOnlyTheOriginalUnexpiredEnrollmentAndSameProof() throws Exception {
        recordDeletionProof = false;
        uncertainDelete = true;
        assertThrows(AccountSafety.DeletionPendingException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        AndroidVault.AccountDeletion pending = vault.pendingAccountDeletion();
        recordDeletionProof = true;
        uncertainDelete = false;
        engine.safety().retryPendingDeletion();
        assertNull(engine.account());
        assertEquals(2, requests("/account"));
        assertEquals(1, requests("/auth/login"));
        assertEquals(1, requests("/account/deletion/status"));
        assertEquals(0, requests("/account/deletion/retry"));
        for (okhttp3.Request request : requests) if (request.url().encodedPath().equals("/account")) {
            assertEquals("Bearer " + pending.token(), request.header("Authorization"));
            assertEquals(pending.deletionProof(), body(request).get("deletionProof").getAsString());
        }
    }

    @Test public void completeRelayBoundsOf512BlocksAnd50ReportsAreSupported() throws Exception {
        assertEquals(512, AccountSafety.MAX_BLOCKS);
        assertEquals(50, AccountSafety.MAX_REPORTS);
        for (int index = 0; index < 512; index++) remoteBlocks.add(UUID.randomUUID());
        engine.safety().refresh();
        assertEquals(512, engine.safety().blockedUsers().size());
        assertThrows(IllegalStateException.class, () -> engine.safety().block(peer));
        assertEquals(0, requests("/account/blocks/" + peerId));
        long now = System.currentTimeMillis();
        List<AccountSafety.Report> reports = new ArrayList<>();
        for (int index = 0; index < 50; index++) reports.add(new AccountSafety.Report(UUID.randomUUID(), userId, peerId,
                AccountSafety.Reason.SPAM, null, null, now, now + AccountSafety.REPORT_LIFETIME - 60_000));
        reportsResponse = reports;
        assertEquals(50, engine.safety().reports().length);
    }

    @Test public void completeBlockSnapshotReplacesRatherThanAccumulatesAtTheCapacityBoundary() throws Exception {
        for (int index = 0; index < 512; index++) remoteBlocks.add(UUID.randomUUID());
        engine.safety().refresh();
        Set<UUID> previous = new HashSet<>(remoteBlocks);
        remoteBlocks.clear();
        for (int index = 0; index < 512; index++) remoteBlocks.add(UUID.randomUUID());
        set(engine.safety(), "nextRefresh", 0L);
        engine.safety().refresh();
        assertEquals(remoteBlocks, new HashSet<>(engine.safety().blockedUsers()));
        for (UUID former : previous) assertFalse(engine.safety().isBlocked(former));
        assertEquals(512, vault.names("blocked/").size());
    }

    @Test public void remoteUnblockAfterInterruptedLocalCleanupDoesNotRestoreIndependentVerification() throws Exception {
        verifiedPeer();
        vault.transaction(() -> { vault.put("blocked/" + peerId, new byte[]{1}); return null; });
        assertFalse(engine.independentlyVerified(peerId));
        engine.safety().refresh();
        assertFalse(engine.safety().isBlocked(peerId));
        assertNull(vault.get("contact/" + peerId));
        assertFalse(engine.independentlyVerified(peerId));
        assertTrue(engine.peers().isEmpty());
        assertThrows(SecurityException.class, () -> engine.prepareConversation(peer));
    }

    @Test public void googleDeletionRequiresSameAccountFreshChallengeAndEnrollmentToken() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        byte[] account = vault.get("account");
        GoogleSignIn.Challenge challenge = new GoogleSignIn.Challenge(opaque(), opaque(), BuildConfig.GOOGLE_WEB_CLIENT_ID,
                System.currentTimeMillis() + 240_000);
        assertThrows(SecurityException.class, () -> engine.safety().deleteWithPassword(PASSWORD));
        authorization = enrollment(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> engine.safety().deleteWithGoogle(challenge, "synthetic-provider-proof"));
        assertArrayEquals(account, vault.get("account"));
        authorization = new ChatEngine.Token(userId, deviceId, opaque(), System.currentTimeMillis() + 240_000);
        assertThrows(SecurityException.class, () -> engine.safety().deleteWithGoogle(challenge, "synthetic-provider-proof"));
        GoogleSignIn.Challenge expired = new GoogleSignIn.Challenge(challenge.id(), challenge.nonce(), challenge.clientId(), System.currentTimeMillis() - 1);
        assertThrows(SecurityException.class, () -> engine.safety().deleteWithGoogle(expired, "synthetic-provider-proof"));
        authorization = enrollment(userId);
        engine.safety().deleteWithGoogle(challenge, "synthetic-provider-proof");
        assertNull(engine.account());
        assertEquals(0, requests("/auth/login"));
        assertEquals(0, requests("/devices"));
        for (okhttp3.Request request : requests) if (request.url().encodedPath().equals("/auth/google"))
            assertEquals(Set.of("challengeId", "idToken"), body(request).keySet());
    }

    @Test public void blockErasesQueuesPhotosAndContentStopsPhotoKeysAndRequiresReverificationAfterUnblock() throws Exception {
        verifiedPeer();
        ChatEngine.Entry sent = stored(userId, UUID.randomUUID(), true, null);
        ChatEngine.Entry received = stored(userId, UUID.randomUUID(), false, null);
        ChatEngine.Entry grouped = stored(userId, UUID.randomUUID(), false, UUID.randomUUID());
        queue(sent);
        long deadline = System.currentTimeMillis() + 120_000;
        vault.transaction(() -> {
            write("profile-photo-cache/" + peerId, new ProfilePhotos.Cached(peerDevice, peer.identityKey(), 1, new byte[]{1, 2}, deadline));
            write("profile-photo-request/" + peerId, new ProfilePhotos.Request(UUID.randomUUID(), peerDevice, peer.identityKey(), deadline, deadline));
            write("profile-photo-grant/" + peerId, new ProfilePhotos.Grant(UUID.randomUUID(), peerDevice, peer.identityKey(), deadline, 1));
            UUID packet = UUID.randomUUID();
            write("profile-photo-out/" + packet, new ProfilePhotos.Queued(
                    new ProfilePhotos.Send(packet, peerId, peerDevice, deadline, 2, new byte[]{1}),
                    ProfileEnvelope.Action.UPDATE, packet, peer.identityKey(), 1));
            return null;
        });
        assertFalse("Do not disturb another test's photo session", PhotoSharingService.busy());
        ChatEngine.Contact own = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()));
        RemotePhotoSession.Session request = new RemotePhotoSession.Session(UUID.randomUUID(), RemotePhotoSession.contact(peer), own,
                false, deadline, remote.generatePreKey(Instant.now()));
        RemotePhotoSession.Prepared prepared = new RemotePhotoSession.Prepared(engine, peer, request);
        set(PhotoSharingService.class, "pending", prepared);
        engine.safety().block(peer);
        assertFalse(PhotoSharingService.busy());
        assertThrows(SecurityException.class, () -> prepared.vault.get("identity"));
        assertTrue(engine.safety().isBlocked(peerId));
        assertEquals(List.of(peerId), engine.safety().blockedUsers());
        assertFalse(engine.independentlyVerified(peerId));
        assertTrue(engine.peers().isEmpty());
        for (ChatEngine.Entry entry : List.of(sent, received, grouped)) {
            assertNull(vault.get("entry/" + entry.id()));
            assertNull(vault.get("body/" + entry.id()));
            assertNull(vault.get("outbox/" + entry.id()));
        }
        assertTrue(vault.names("profile-photo-cache/").isEmpty());
        assertTrue(vault.names("profile-photo-request/").isEmpty());
        assertTrue(vault.names("profile-photo-grant/").isEmpty());
        assertTrue(vault.names("profile-photo-out/").isEmpty());
        assertThrows(SecurityException.class, () -> engine.prepareConversation(peer));
        assertThrows(SecurityException.class, () -> engine.send(peer, "Not sent", null, ChatEnvelope.Expiry.HOUR_1));
        assertThrows(SecurityException.class, () -> new RemotePhotoSession.Prepared(engine, peer, null));
        engine.safety().unblock(peerId);
        assertFalse(engine.safety().isBlocked(peerId));
        assertFalse(engine.independentlyVerified(peerId));
        assertTrue(engine.peers().isEmpty());
        assertThrows(SecurityException.class, () -> engine.prepareConversation(peer));
        engine.addPeer(peer);
        assertTrue(engine.independentlyVerified(peerId));
    }

    @Test public void freshRemoteBlockIsAppliedBeforeSyncUploadsAndBlockedInboxNeverDecryptsOrRoutes() throws Exception {
        verifiedPeer();
        ChatEngine.Entry outgoing = stored(userId, UUID.randomUUID(), true, null);
        queue(outgoing);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        incoming = List.of(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1,
                now, now + 3_600_000, 2, new byte[]{1}, UUID.randomUUID()));
        remoteBlocks.add(peerId);
        engine.sync();
        assertTrue(engine.safety().isBlocked(peerId));
        assertNull(vault.get("outbox/" + outgoing.id()));
        assertNull(vault.get("entry/" + id));
        assertNotNull(vault.get("seen/" + id));
        assertEquals(0, requests("/messages"));
        assertEquals(1, requests("/messages/" + id));
        assertNull(engine.notificationConversation(new ChatEngine.NotificationDestination(userId, deviceId, peerId, id, now + 60_000)));
    }

    @Test public void blockedOfficialAdminCannotBeSilentlyRestoredByDiscoveryOrIncoming() throws Exception {
        AdminOnboarding.Pin pin = new AdminOnboarding.Pin(ORIGIN, peerId, peerDevice,
                ChatEngine.safetyNumber(peerId, peer.identityKey()).replace(" ", "").toLowerCase(Locale.ROOT));
        engine.close();
        vault.unlock();
        engine = new ChatEngine(vault, pin);
        transport();
        introductions = new AdminOnboarding.Page(userId, RemotePhotoSession.contact(peer),
                List.of(new AdminOnboarding.Introduction(peerId, peerDevice, peer.identityKey(), "peer", "Fixture peer")), null);
        engine.onboarding().refresh();
        assertEquals(1, engine.peers().size());
        assertFalse(engine.independentlyVerified(peerId));
        engine.safety().block(peer);
        set(engine.onboarding(), "nextRefresh", 0L);
        engine.onboarding().refresh();
        assertFalse(engine.onboarding().acceptIncoming(peerId, peerDevice));
        assertNull(vault.get("contact/" + peerId));
        assertTrue(engine.peers().isEmpty());
        engine.safety().unblock(peerId);
        set(engine.onboarding(), "nextRefresh", 0L);
        engine.onboarding().refresh();
        assertTrue(engine.peers().isEmpty());
        assertFalse(engine.independentlyVerified(peerId));
    }

    @Test public void blockedGroupContentAdvancesSenderRatchetButNeverDownloadsStoresOrNotifies() throws Exception {
        verifiedPeer();
        UUID groupId = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        GroupChat.Snapshot snapshot = new GroupChat.Snapshot(groupId, userId, 1, epoch, false, List.of(
                new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()), "fixture", null, "ACTIVE", 0),
                new GroupChat.Member(peerId, peerDevice, peer.identityKey(), "peer", null, "ACTIVE", 0)));
        SignalGroup sender = new SignalGroup(new DeviceSecurityTest.MemoryVault(), groupId, epoch, peerId);
        SignalGroup recipient = new SignalGroup(vault, groupId, epoch, userId);
        recipient.accept(peerId, sender.distribution());
        long now = System.currentTimeMillis();
        vault.transaction(() -> {
            write("group/" + groupId, new GroupChat.Conversation(snapshot, "Fixture group", null));
            write("group-approval/" + groupId + "/" + epoch, new GroupChat.Approval(snapshot.roster(), "Fixture group", now + 86_400_000L));
            write("group-allowed/" + groupId, snapshot.roster().members());
            vault.put("group-key/" + groupId + "/" + epoch + "/" + peerId, Long.toString(now + 86_400_000L).getBytes(StandardCharsets.US_ASCII));
            return null;
        });
        String ratchetKey = vault.names("group-signal/" + groupId + "/" + epoch + "/").get(0);
        byte[] before = vault.get(ratchetKey);
        engine.safety().block(peer);
        UUID messageId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        ChatEnvelope content = new ChatEnvelope(1, messageId, peerId, peerDevice, groupId, epoch, now, now + 3_600_000,
                ChatEnvelope.Expiry.HOUR_1, null, new ChatEnvelope.Attachment(mediaId, new byte[32], new byte[12], "image/jpeg"));
        byte[] encrypted = sender.encrypt(RelayApi.JSON.toJson(new GroupEnvelope(1, groupId, epoch, 1, content)).getBytes(StandardCharsets.UTF_8));
        GroupChat.Message message = new GroupChat.Message(messageId, groupId, epoch, 1, peerId, peerDevice,
                content.expiry(), content.expiresAt(), encrypted, mediaId);
        invoke(engine.groups(), "receive", new Class<?>[]{GroupChat.Snapshot.class, GroupChat.Message.class}, snapshot, message);
        assertFalse(Arrays.equals(before, vault.get(ratchetKey)));
        assertNull(vault.get("entry/" + messageId));
        assertNull(vault.get("body/" + messageId));
        assertNotNull(vault.get("seen/" + messageId));
        assertEquals("delete", RelayApi.JSON.fromJson(new String(vault.get("group-ack/" + messageId), StandardCharsets.UTF_8), GroupChat.Ack.class).action());
        assertNull(engine.notificationConversation(new ChatEngine.NotificationDestination(userId, deviceId, groupId, messageId, now + 60_000)));
        assertThrows(SecurityException.class, () -> engine.groups().invite(groupId, List.of(peer)));
        assertEquals(0, requests("/groups/" + groupId + "/invitations"));
        engine.safety().unblock(peerId);
        assertFalse(engine.independentlyVerified(peerId));
        UUID nextId = UUID.randomUUID();
        ChatEnvelope next = new ChatEnvelope(1, nextId, peerId, peerDevice, groupId, epoch, now, now + 3_600_000,
                ChatEnvelope.Expiry.HOUR_1, "Subsequent group content", null);
        byte[] nextEncrypted = sender.encrypt(RelayApi.JSON.toJson(new GroupEnvelope(1, groupId, epoch, 1, next)).getBytes(StandardCharsets.UTF_8));
        GroupChat.Message nextMessage = new GroupChat.Message(nextId, groupId, epoch, 1, peerId, peerDevice,
                next.expiry(), next.expiresAt(), nextEncrypted, null);
        keys.add(new ChatEngine.Entry(nextId, groupId, next.expiresAt(), next.expiry(), false, false, "DELIVERED", userId, epoch, 1, peerId));
        invoke(engine.groups(), "receive", new Class<?>[]{GroupChat.Snapshot.class, GroupChat.Message.class}, snapshot, nextMessage);
        assertEquals(1, engine.entries(groupId).size());
    }

    @Test public void blockRefreshIsBoundedAndFailureCannotAuthorizeQueuedTransmission() throws Exception {
        engine.safety().refresh();
        engine.safety().refresh();
        assertEquals(1, requests("/account/blocks"));
        verifiedPeer();
        ChatEngine.Entry outgoing = stored(userId, UUID.randomUUID(), true, null);
        queue(outgoing);
        set(engine.safety(), "nextRefresh", 0L);
        blocksStatus = 503;
        assertThrows(RelayApi.ApiFailure.class, engine::sync);
        assertThrows(IOException.class, engine::sync);
        assertNotNull(vault.get("outbox/" + outgoing.id()));
        assertEquals(0, requests("/messages"));
        assertTrue(engine.authenticated());
        assertEquals(2, requests("/account/blocks"));
    }

    @Test public void blockingAnExistingGroupOwnerPreservesOnlyApprovedGroupTrustNotDirectVerification() throws Exception {
        verifiedPeer();
        UUID groupId = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        GroupChat.Snapshot snapshot = new GroupChat.Snapshot(groupId, peerId, 1, epoch, false, List.of(
                new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()), "fixture", null, "ACTIVE", 0),
                new GroupChat.Member(peerId, peerDevice, peer.identityKey(), "peer", null, "ACTIVE", 0)));
        GroupChat.Conversation conversation = new GroupChat.Conversation(snapshot, "Previously approved group", null);
        vault.transaction(() -> {
            write("group/" + groupId, conversation);
            write("group-approval/" + groupId + "/" + epoch, new GroupChat.Approval(snapshot.roster(), conversation.name(), System.currentTimeMillis() + 86_400_000L));
            return null;
        });
        engine.safety().block(peer);
        assertTrue(engine.groups().ready(conversation));
        assertFalse(engine.independentlyVerified(peerId));
        assertNull(vault.get("contact/" + peerId));
        assertThrows(SecurityException.class, () -> new RemotePhotoSession.Prepared(engine, peer, null));
        UUID unapprovedId = UUID.randomUUID();
        GroupChat.Snapshot unapproved = new GroupChat.Snapshot(unapprovedId, peerId, 1, UUID.randomUUID(), false, snapshot.members());
        assertFalse(engine.groups().ownerVerified(new GroupChat.Conversation(unapproved, "Unapproved", null)));
        SignalClient replacement = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        GroupChat.Snapshot changed = new GroupChat.Snapshot(groupId, peerId, 2, UUID.randomUUID(), false, List.of(
                snapshot.member(userId),
                new GroupChat.Member(peerId, peerDevice, Base64.getEncoder().encodeToString(replacement.publicIdentity()), "peer", null, "ACTIVE", 0)));
        assertFalse(engine.groups().ownerVerified(new GroupChat.Conversation(changed, "Changed identity", null)));
    }

    @Test public void malformedOrOverCapacityBlockSnapshotsDoNotClearExistingBlockMarkers() throws Exception {
        verifiedPeer();
        engine.safety().block(peer);
        for (Object invalid : List.of(List.of(peerId, peerId), List.of(userId),
                Collections.nCopies(AccountSafety.MAX_BLOCKS + 1, peerId))) {
            blocksResponse = invalid;
            set(engine.safety(), "nextRefresh", 0L);
            assertThrows(Exception.class, () -> engine.safety().refresh());
            assertTrue(engine.safety().isBlocked(peerId));
            assertFalse(engine.independentlyVerified(peerId));
        }
    }

    @Test public void forbiddenOrUnavailableDirectDeliveryStopsRetriesButDoesNotLogOut() throws Exception {
        verifiedPeer();
        byte[] account = vault.get("account");
        for (int status : List.of(403, 404)) {
            ChatEngine.Entry outgoing = stored(userId, UUID.randomUUID(), true, null);
            queue(outgoing);
            messageStatus = status;
            invoke(engine, "flushOutgoing", new Class<?>[0]);
            assertTrue(engine.authenticated());
            assertArrayEquals(account, vault.get("account"));
            assertNull(vault.get("outbox/" + outgoing.id()));
            ChatEngine.Entry result = engine.entries(peerId).stream().filter(entry -> outgoing.id().equals(entry.id())).findFirst().orElseThrow();
            assertEquals("Not sent: contact unavailable", result.state());
            invoke(engine, "flushOutgoing", new Class<?>[0]);
        }
        assertEquals(2, requests("/messages"));
    }

    @Test public void reportSendsOnlySelectedMetadataAndRejectsOwnOrWrongSenderContext() throws Exception {
        ChatEngine.Entry direct = stored(userId, UUID.randomUUID(), false, null);
        engine.safety().report(peerId, AccountSafety.Reason.SPAM, direct);
        JsonObject directRequest = body(requests.get(requests.size() - 1));
        assertEquals(Set.of("targetId", "reason", "messageId"), directRequest.keySet());
        assertEquals(peerId.toString(), directRequest.get("targetId").getAsString());
        UUID groupId = UUID.randomUUID();
        ChatEngine.Entry group = stored(userId, UUID.randomUUID(), false, groupId);
        engine.safety().report(peerId, AccountSafety.Reason.CHILD_SAFETY, group);
        JsonObject groupRequest = body(requests.get(requests.size() - 1));
        assertEquals(Set.of("targetId", "reason", "messageId", "groupId"), groupRequest.keySet());
        assertEquals(peerId.toString(), groupRequest.get("targetId").getAsString());
        assertEquals(groupId.toString(), groupRequest.get("groupId").getAsString());
        engine.safety().report(peerId, AccountSafety.Reason.OTHER, null);
        assertEquals(Set.of("targetId", "reason"), body(requests.get(requests.size() - 1)).keySet());
        assertThrows(IllegalArgumentException.class, () -> engine.safety().report(userId, AccountSafety.Reason.SPAM, null));
        assertThrows(SecurityException.class, () -> engine.safety().report(UUID.randomUUID(), AccountSafety.Reason.SPAM, group));
        ChatEngine.Entry own = new ChatEngine.Entry(UUID.randomUUID(), peerId, direct.expiresAt(), direct.expiry(), true, false, "PENDING", userId);
        assertThrows(SecurityException.class, () -> engine.safety().report(peerId, AccountSafety.Reason.SPAM, own));
        assertEquals(3, requests("/safety/reports"));
    }

    @Test public void reportReceiptsAndAdminQueueMustHaveBoundedLifetimeAndValidIds() throws Exception {
        for (AccountSafety.ReportReceipt invalid : List.of(
                new AccountSafety.ReportReceipt(new UUID(0, 0), System.currentTimeMillis() + 60_000),
                new AccountSafety.ReportReceipt(UUID.randomUUID(), System.currentTimeMillis() - 1),
                new AccountSafety.ReportReceipt(UUID.randomUUID(), System.currentTimeMillis() + AccountSafety.REPORT_LIFETIME + 60_000))) {
            receiptResponse = invalid;
            assertThrows(SecurityException.class, () -> engine.safety().report(peerId, AccountSafety.Reason.OTHER, null));
        }
        long now = System.currentTimeMillis();
        AccountSafety.Report valid = new AccountSafety.Report(UUID.randomUUID(), userId, peerId, AccountSafety.Reason.THREATS,
                UUID.randomUUID(), null, now, now + AccountSafety.REPORT_LIFETIME - 60_000);
        reportsResponse = new AccountSafety.Report[]{valid};
        assertArrayEquals(new AccountSafety.Report[]{valid}, engine.safety().reports());
        reportsResponse = new AccountSafety.Report[]{valid, valid};
        assertThrows(SecurityException.class, () -> engine.safety().reports());
        reportsResponse = Collections.nCopies(AccountSafety.MAX_REPORTS + 1, valid);
        assertThrows(SecurityException.class, () -> engine.safety().reports());
        AccountSafety.Report groupOnly = new AccountSafety.Report(UUID.randomUUID(), userId, peerId, AccountSafety.Reason.OTHER,
                null, UUID.randomUUID(), now, now + 60_000);
        reportsResponse = new AccountSafety.Report[]{groupOnly};
        assertArrayEquals("A group-only report does not claim expired message evidence",
                new AccountSafety.Report[]{groupOnly}, engine.safety().reports());
    }

    @Test public void boundedClockSkewDoesNotRejectAValidReportOrExtendItsDeadline() throws Exception {
        long ahead = System.currentTimeMillis() + 1000;
        AccountSafety.ReportReceipt receipt = new AccountSafety.ReportReceipt(UUID.randomUUID(), ahead + AccountSafety.REPORT_LIFETIME);
        receiptResponse = receipt;
        assertEquals(receipt, engine.safety().report(peerId, AccountSafety.Reason.SPAM, null));
        AccountSafety.Report report = new AccountSafety.Report(UUID.randomUUID(), userId, peerId, AccountSafety.Reason.OTHER,
                null, null, ahead, ahead + AccountSafety.REPORT_LIFETIME);
        reportsResponse = new AccountSafety.Report[]{report};
        assertEquals(report.expiresAt(), engine.safety().reports()[0].expiresAt());
        reportsResponse = new AccountSafety.Report[]{new AccountSafety.Report(UUID.randomUUID(), userId, peerId, AccountSafety.Reason.OTHER,
                null, null, ahead, ahead + AccountSafety.REPORT_LIFETIME + 1)};
        assertThrows(SecurityException.class, () -> engine.safety().reports());
    }

    @Test public void adminQueueForbiddenDoesNotInvalidateAccountCredentials() throws Exception {
        byte[] account = vault.get("account");
        reportStatus = 403;
        assertThrows(SecurityException.class, () -> engine.safety().reports());
        assertThrows(SecurityException.class, () -> engine.safety().resolveReport(UUID.randomUUID()));
        assertTrue(engine.authenticated());
        assertArrayEquals(account, vault.get("account"));
        reportStatus = 200;
        UUID report = UUID.randomUUID();
        engine.safety().resolveReport(report);
        okhttp3.Request request = requests.get(requests.size() - 1);
        assertEquals("DELETE", request.method());
        assertEquals("/safety/reports/" + report, request.url().encodedPath());
        assertNull(request.body());
    }

    @Test public void blockAndTermsSettingsStayInsideTheirEncryptedAccountPartition() throws Exception {
        verifiedPeer();
        engine.safety().acceptTerms("candidate0.4.7");
        engine.safety().block(peer);
        vault.saveAccount(userId);
        engine.close();
        vault.unlock();
        UUID other = UUID.randomUUID();
        vault.transaction(() -> { write("account", account(other, UUID.randomUUID(), "other")); return null; });
        engine = new ChatEngine(vault);
        assertFalse(engine.safety().isBlocked(peerId));
        assertFalse(engine.safety().termsAccepted("candidate0.4.7"));
        engine.safety().acceptTerms("other-version");
        vault.saveAccount(other);
        engine.close();
        vault.unlock();
        assertTrue(vault.restoreAccount(userId));
        engine = new ChatEngine(vault);
        assertTrue(engine.safety().isBlocked(peerId));
        assertTrue(engine.safety().termsAccepted("candidate0.4.7"));
        assertFalse(engine.safety().termsAccepted("other-version"));
        assertNotNull(vault.get(AndroidVault.savedAccountPrefix(other) + "terms-version"));
    }
}
