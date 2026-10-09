package app.vanishr.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.util.AtomicFile;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.vanishr.crypto.BackupCipher;
import app.vanishr.crypto.SignalClient;
import okhttp3.*;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Runs only in the isolated QA app on a dedicated, credential-protected emulator; no fixture contacts a real relay. */
@RunWith(AndroidJUnit4.class)
public class AccountBackupFlowTest {
    private static final String HOST = "account-backup.invalid";
    private static final String ORIGIN = "https://" + HOST + "/";
    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final List<RelayApi> transports = new ArrayList<>();
    private final List<okhttp3.Request> requests = new CopyOnWriteArrayList<>();
    private final List<Phone> phones = new ArrayList<>();
    private final Map<UUID, ChatEngine.Contact> relayContacts = new HashMap<>();
    private final Set<UUID> remoteBlocks = new LinkedHashSet<>();
    private volatile byte[] storedBlob;
    private volatile int uploadStatus = 200;
    private volatile int deleteStatus = 204;
    private Context application;

    private final class Phone {
        final String name = "account-backup-" + UUID.randomUUID();
        final File directory;
        final Context context;
        final AndroidVault vault;
        final ChatEngine engine;

        Phone() throws Exception {
            directory = new File(application.getNoBackupFilesDir(), name);
            assertTrue(directory.mkdir());
            context = new ContextWrapper(application) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getNoBackupFilesDir() { return directory; }
                @Override public android.content.SharedPreferences getSharedPreferences(String preferences, int mode) {
                    return application.getSharedPreferences(name + "-" + preferences, mode);
                }
            };
            vault = new AndroidVault(context);
            vault.unlock();
            ChatEngine.Account account = new ChatEngine.Account(ORIGIN, "fixture", userId, deviceId, opaque(), System.currentTimeMillis() + 3_600_000,
                    true, opaque(), System.currentTimeMillis() + 86_400_000L);
            vault.transaction(() -> { vault.put("account", RelayApi.JSON.toJson(account).getBytes(StandardCharsets.UTF_8)); return null; });
            engine = new ChatEngine(vault);
            if (engine.groupApi() != null) engine.groupApi().close();
            RelayApi api = synthetic(account.accessToken());
            set(engine, "api", api);
            engine.backup().transports = (origin, token) -> synthetic(token);
            phones.add(this);
        }

        ChatEngine.Peer contact(String handle, String nickname) throws Exception {
            UUID id = UUID.randomUUID(), device = UUID.randomUUID();
            SignalClient remote = new SignalClient(id, new DeviceSecurityTest.MemoryVault());
            String identity = Base64.getEncoder().encodeToString(remote.publicIdentity());
            relayContacts.put(id, new ChatEngine.Contact(id, device, identity));
            ChatEngine.Peer peer = new ChatEngine.Peer(id, device, identity, nickname == null ? handle : nickname, handle, "Profile " + handle);
            engine.groupSignal().verifyPeer(id, remote.publicIdentity());
            vault.transaction(() -> {
                write("contact/" + id, peer);
                if (nickname != null) write("contact-name/" + id, nickname);
                return null;
            });
            return peer;
        }

        void write(String key, Object value) { vault.put(key, RelayApi.JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
    }

    private static String opaque() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private RelayApi synthetic(String token) {
        RelayApi api = new RelayApi(ORIGIN, token);
        try {
            Field field = RelayApi.class.getDeclaredField("client");
            field.setAccessible(true);
            field.set(api, ((OkHttpClient) field.get(api)).newBuilder().addInterceptor(this::intercept).build());
        } catch (Exception failure) { throw new AssertionError(failure); }
        transports.add(api);
        return api;
    }

    private Response response(okhttp3.Request request, int status, Object json) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
                .body(ResponseBody.create(json == null ? "" : RelayApi.JSON.toJson(json), MediaType.get("application/json"))).build();
    }

    private Response intercept(Interceptor.Chain chain) throws IOException {
        okhttp3.Request request = chain.request();
        assertEquals("No fixture may contact a real relay", HOST, request.url().host());
        requests.add(request);
        String path = request.url().encodedPath(), method = request.method();
        if (path.equals("/account/blocks")) return response(request, 200, remoteBlocks);
        if (path.startsWith("/users/id/")) {
            ChatEngine.Contact contact = relayContacts.get(UUID.fromString(path.substring("/users/id/".length())));
            return contact == null ? response(request, 404, Map.of("error", "not_found")) : response(request, 200, contact);
        }
        if (path.equals("/account/backup") && method.equals("PUT")) {
            if (uploadStatus != 200) return response(request, uploadStatus, Map.of("error", "service_unavailable"));
            okio.Buffer buffer = new okio.Buffer();
            request.body().writeTo(buffer);
            storedBlob = buffer.readByteArray();
            return response(request, 200, new AccountBackup.Status(true, storedBlob.length, System.currentTimeMillis(), System.currentTimeMillis() + 86_400_000L));
        }
        if (path.equals("/account/backup") && method.equals("GET")) {
            byte[] blob = storedBlob;
            if (blob == null) return response(request, 404, Map.of("error", "not_found"));
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
                    .body(ResponseBody.create(blob, MediaType.get("application/octet-stream"))).build();
        }
        if (path.equals("/account/backup") && method.equals("DELETE")) {
            if (deleteStatus == 204) storedBlob = null;
            return response(request, deleteStatus, deleteStatus == 204 ? null : Map.of("error", "service_unavailable"));
        }
        throw new AssertionError("Unexpected synthetic backup route: " + method + " " + path);
    }

    @Before public void freshIsolatedQaVault() {
        application = ApplicationProvider.getApplicationContext();
        assertEquals("These tests may run only in the isolated QA application", "app.vanishr.android.qa", application.getPackageName());
        assertTrue("Use the isolated test emulator, never a user's phone", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"));
        KeyguardManager keyguard = application.getSystemService(KeyguardManager.class);
        assertNotNull(keyguard);
        assertTrue("Unlock the credential-protected test emulator first", keyguard.isDeviceSecure() && !keyguard.isDeviceLocked());
    }

    @After public void removeOnlyTheseSyntheticFixtures() {
        for (Phone phone : phones) {
            phone.engine.close();
            phone.vault.close();
            phone.context.getSharedPreferences("backup", Context.MODE_PRIVATE).edit().clear().commit();
            new AtomicFile(new File(phone.directory, "vault.bin")).delete();
            assertTrue("The fixture directory must be empty", phone.directory.delete());
        }
        for (RelayApi transport : transports) transport.close();
        BackupState.clear(application, userId);
    }

    private static String format(byte[] key) { return BackupCipher.formatRecoveryKey(key); }

    @Test public void backupRoundTripRestoresOnlyContactsWhoseVerifiedIdentityIsUnchanged() throws Exception {
        Phone first = new Phone();
        ChatEngine.Peer mum = first.contact("mum_1", "Mum");
        ChatEngine.Peer rotated = first.contact("rotated", null);
        ChatEngine.Peer blockedLater = first.contact("blocked-later", "Pal");
        ChatEngine.Peer automatic = first.contact("introduced", null);
        first.vault.transaction(() -> { first.vault.put("admin-contact/" + automatic.userId(), new byte[]{1}); return null; });
        UUID legacy = UUID.randomUUID();
        first.vault.transaction(() -> {
            first.write("contact/" + legacy, new ChatEngine.Peer(legacy, UUID.randomUUID(), Base64.getEncoder().encodeToString(new byte[33]), "Old contact"));
            return null;
        });

        byte[] key = BackupCipher.newRecoveryKey();
        assertFalse(first.engine.backup().enabled());
        first.engine.backup().enable(key);
        assertTrue(first.engine.backup().enabled());
        assertEquals(format(key), first.engine.backup().recoveryKey());
        AccountBackup.Prepared prepared = first.engine.backup().prepare();
        assertNotNull(prepared);
        prepared.upload();
        prepared.close();
        assertNotNull(storedBlob);
        String stored = new String(storedBlob, StandardCharsets.ISO_8859_1);
        for (String secret : List.of("mum_1", "Mum", "rotated", "Pal", mum.identityKey(), mum.userId().toString()))
            assertFalse("The relay must only ever see ciphertext", stored.contains(secret));
        okhttp3.Request upload = requests.stream().filter(request -> request.method().equals("PUT")).findFirst().orElseThrow();
        assertEquals("application/octet-stream", upload.body().contentType().toString());
        assertTrue(upload.header("Authorization").startsWith("Bearer "));
        AccountBackup.Snapshot sealed = AccountBackup.decode(BackupCipher.open(key, userId, storedBlob), userId);
        assertEquals(Set.of(mum.userId(), rotated.userId(), blockedLater.userId()),
                new HashSet<>(sealed.contacts().stream().map(AccountBackup.Contact::userId).toList()));

        SignalClient newIdentity = new SignalClient(rotated.userId(), new DeviceSecurityTest.MemoryVault());
        relayContacts.put(rotated.userId(), new ChatEngine.Contact(rotated.userId(), rotated.deviceId(),
                Base64.getEncoder().encodeToString(newIdentity.publicIdentity())));
        remoteBlocks.add(blockedLater.userId());

        Phone second = new Phone();
        assertFalse(second.engine.backup().enabled());
        AccountBackup.Result result = second.engine.backup().restore(format(key).toLowerCase(Locale.ROOT));
        assertEquals(new AccountBackup.Result(1, 0, 2), result);
        assertEquals(List.of("contact/" + mum.userId()), second.vault.names("contact/"));
        assertTrue(second.engine.independentlyVerified(mum.userId()));
        assertEquals("Mum", second.engine.privateName(mum));
        assertFalse(second.engine.independentlyVerified(rotated.userId()));
        assertFalse(second.engine.independentlyVerified(blockedLater.userId()));
        assertTrue(second.engine.backup().enabled());
        assertEquals(format(key), second.engine.backup().recoveryKey());
        assertTrue("A restored phone refreshes the backup right away", second.engine.backup().due(System.currentTimeMillis()));
        assertEquals(new AccountBackup.Result(0, 1, 2), second.engine.backup().restore(format(key)));
    }

    @Test public void wrongOrMalformedKeysAndMissingOrDamagedBackupsRestoreNothing() throws Exception {
        Phone first = new Phone();
        first.contact("someone", "Someone");
        Phone second = new Phone();
        AccountBackup.Rejected missing = assertThrows(AccountBackup.Rejected.class, () -> second.engine.backup().restore(format(BackupCipher.newRecoveryKey())));
        assertEquals("No backup was found for this account.", missing.getMessage());

        byte[] key = BackupCipher.newRecoveryKey();
        first.engine.backup().enable(key);
        AccountBackup.Prepared prepared = first.engine.backup().prepare();
        prepared.upload();
        prepared.close();
        for (String attempt : new String[]{format(BackupCipher.newRecoveryKey()), "not a recovery key", "", format(key).substring(5)})
            assertThrows(attempt, AccountBackup.Rejected.class, () -> second.engine.backup().restore(attempt));
        byte[] damaged = storedBlob.clone();
        damaged[damaged.length - 1] ^= 1;
        storedBlob = damaged;
        assertThrows(AccountBackup.Rejected.class, () -> second.engine.backup().restore(format(key)));
        assertTrue(second.vault.names("contact/").isEmpty());
        assertFalse(second.engine.backup().enabled());
    }

    @Test public void turningOffRemovesTheServerCopyFirstAndKeepsTheKeyWhenThatFails() throws Exception {
        Phone phone = new Phone();
        phone.contact("friend", null);
        phone.engine.backup().enable(BackupCipher.newRecoveryKey());
        AccountBackup.Prepared prepared = phone.engine.backup().prepare();
        prepared.upload();
        prepared.close();
        assertNotNull(storedBlob);
        deleteStatus = 503;
        assertThrows(RelayApi.ApiFailure.class, () -> phone.engine.backup().disable());
        assertTrue(phone.engine.backup().enabled());
        assertNotNull(storedBlob);
        deleteStatus = 204;
        phone.engine.backup().disable();
        assertFalse(phone.engine.backup().enabled());
        assertNull(storedBlob);
        assertEquals(0, BackupState.lastSuccess(phone.context, userId));
        assertThrows(IllegalStateException.class, () -> phone.engine.backup().recoveryKey());
    }

    @Test public void automaticBackupIsDueOnlyWhenOnAndNeitherRecentlyAttemptedNorUploaded() throws Exception {
        Phone phone = new Phone();
        long now = System.currentTimeMillis();
        assertFalse("Backup is opt-in", phone.engine.backup().due(now));
        phone.engine.backup().enable(BackupCipher.newRecoveryKey());
        assertTrue(phone.engine.backup().due(now));
        BackupState.attempted(phone.context, userId, now - 60_000);
        assertFalse("A recent attempt waits for the retry delay", phone.engine.backup().due(now));
        BackupState.attempted(phone.context, userId, now - AccountBackup.RETRY_DELAY - 1);
        assertTrue(phone.engine.backup().due(now));
        BackupState.succeeded(phone.context, userId, now - 3_600_000);
        assertFalse("A backup within the last day is current", phone.engine.backup().due(now));
        BackupState.succeeded(phone.context, userId, now - AccountBackup.INTERVAL - 1);
        assertTrue(phone.engine.backup().due(now));
    }

    @Test public void theDataSyncServiceUploadsTheSealedBackupInTheBackgroundAndRecordsOnlyTimestamps() throws Exception {
        Phone phone = new Phone();
        phone.contact("service-friend", "Friend");
        phone.engine.backup().enable(BackupCipher.newRecoveryKey());
        BackupState.clear(application, userId);

        BackupService.start(application, phone.engine.backup().prepare());
        waitForService();
        assertNotNull("The foreground service must have uploaded the ciphertext", storedBlob);
        assertTrue(BackupState.lastSuccess(application, userId) > 0);
        assertFalse(BackupState.lastFailed(application, userId));
        long uploaded = BackupState.lastSuccess(application, userId);

        uploadStatus = 503;
        BackupService.start(application, phone.engine.backup().prepare());
        waitForService();
        assertTrue("A failed upload is remembered so it can be retried", BackupState.lastFailed(application, userId));
        assertEquals("A failure never advances the last success", uploaded, BackupState.lastSuccess(application, userId));
        assertFalse(BackupService.busy());
    }

    private void waitForService() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (BackupService.busy() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertFalse("The service must stop itself when the upload finishes", BackupService.busy());
    }
}
