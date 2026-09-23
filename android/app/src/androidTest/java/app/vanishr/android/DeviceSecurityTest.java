package app.vanishr.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.security.NetworkSecurityPolicy;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.vanishr.crypto.*;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceSecurityTest {
    @Test public void notificationsAcceptOnlyGenericWakeEventsAndContainNoMessageContent() {
        var allowed = new com.google.firebase.messaging.RemoteMessage.Builder("synthetic-sender")
                .addData("event", "new_message").build();
        var unexpected = new com.google.firebase.messaging.RemoteMessage.Builder("synthetic-sender")
                .addData("event", "new_message").addData("body", "private test message").build();
        assertTrue(PushService.isWakeSignal(allowed));
        assertFalse(PushService.isWakeSignal(unexpected));
        var notification = PushService.genericNotification(ApplicationProvider.getApplicationContext());
        assertEquals("Vanishr", notification.extras.getString(android.app.Notification.EXTRA_TITLE));
        assertEquals("New message", notification.extras.getString(android.app.Notification.EXTRA_TEXT));
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertEquals(60_000L, notification.getTimeoutAfter());
        assertTrue(notification.contentIntent.isImmutable());
        String reference = "a".repeat(43);
        var routed = new com.google.firebase.messaging.RemoteMessage.Builder("synthetic-sender")
            .addData("event", "new_message").addData("reference", reference).build();
        assertTrue(PushService.isWakeSignal(routed));
        var malformed = new com.google.firebase.messaging.RemoteMessage.Builder("synthetic-sender")
            .addData("event", "new_message").addData("reference", UUID.randomUUID().toString()).build();
        assertFalse(PushService.isWakeSignal(malformed));
        Context context = ApplicationProvider.getApplicationContext();
        var first = PushService.genericNotification(context, reference);
        var second = PushService.genericNotification(context, "b".repeat(43));
        assertNotEquals("A later notification must not retarget an earlier tap", first.contentIntent, second.contentIntent);
        assertTrue(first.contentIntent.isImmutable());
        assertEquals("New message", first.extras.getString(android.app.Notification.EXTRA_TEXT));
        android.content.Intent intent = PushService.notificationIntent(context, reference);
        assertEquals(PushService.OPEN_NOTIFICATION, intent.getAction());
        assertEquals(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.getFlags());
        assertEquals(reference, intent.getStringExtra(PushService.REFERENCE));
        assertEquals(reference, intent.getData().getLastPathSegment());
        assertTrue(intent.getLongExtra(PushService.DEADLINE, 0) <= System.currentTimeMillis() + PushService.ROUTE_LIFETIME);
        assertEquals(2, intent.getExtras().size());
    }

    @Test public void launchedActivityProtectsItsWindowAndHasVisibleControls() {
        try (androidx.test.core.app.ActivityScenario<MainActivity> scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
                android.view.ViewGroup content = activity.findViewById(android.R.id.content);
                assertEquals(1, content.getChildCount());
                assertTrue(content.getChildAt(0) instanceof android.widget.LinearLayout);
                android.widget.LinearLayout layout = (android.widget.LinearLayout) content.getChildAt(0);
                assertTrue(layout.getChildCount() >= 2);
            });
        }
    }

    @Test public void backupsAndCleartextAreDisabledAndVaultStartsLocked() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted());
        assertThrows(IllegalArgumentException.class, () -> new RelayApi("http://localhost", null));
        assertThrows(SecurityException.class, () -> new AndroidVault(context).get("identity"));
    }

    @Test public void officialSignalAndWireSerializationWorkOnAndroid() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        SignalClient alice = new SignalClient(aliceId, new MemoryVault());
        SignalClient bob = new SignalClient(bobId, new MemoryVault());
        alice.verifyPeer(bobId, bob.publicIdentity());
        bob.verifyPeer(aliceId, alice.publicIdentity());
        PublicBundle bundle = RelayApi.JSON.fromJson(RelayApi.JSON.toJson(bob.generatePreKey(Instant.now())), PublicBundle.class);
        alice.establish(bobId, bundle, Instant.now());
        SignalClient.Packet encrypted = alice.encrypt(bobId, "hello".getBytes(StandardCharsets.UTF_8), Instant.now());
        assertEquals("hello", new String(bob.decrypt(aliceId, encrypted), StandardCharsets.UTF_8));
        ChatEngine.Account account = new ChatEngine.Account("https://localhost/", "test", aliceId, UUID.randomUUID(), "test-only", 1234, true);
        assertEquals(account, RelayApi.JSON.fromJson(RelayApi.JSON.toJson(account), ChatEngine.Account.class));
    }

    @Test public void vaultRejectsMissingDeviceCredentials() {
        Context context = ApplicationProvider.getApplicationContext();
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        org.junit.Assume.assumeFalse("This test requires a test device with no screen lock", keyguard.isDeviceSecure());
        assertThrows(SecurityException.class, () -> new AndroidVault(context).unlock());
    }

    @Test public void authenticatedKeystoreContentIsEncryptedAndUnrecoverableAfterKeyDeletion() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        org.junit.Assume.assumeTrue("Unlock a credential-protected test device first", keyguard.isDeviceSecure() && !keyguard.isDeviceLocked());
        AndroidVault vault = new AndroidVault(context);
        vault.unlock();
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore"); keyStore.load(null);
        var factory = javax.crypto.SecretKeyFactory.getInstance("AES", "AndroidKeyStore");
        var information = (android.security.keystore.KeyInfo) factory.getKeySpec(
            (javax.crypto.SecretKey) keyStore.getKey(AndroidVault.currentAlias(AndroidVault.MASTER), null), android.security.keystore.KeyInfo.class);
        assertFalse("Phone unlock must not require another timed in-app authentication", information.isUserAuthenticationRequired());
        String key = "test/" + UUID.randomUUID();
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        vault.transaction(() -> { vault.put(key, hello); return null; });
        vault.close();
        vault.unlock();
        assertArrayEquals(hello, vault.get(key));
        UUID messageId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 60_000;
        String alias = AndroidVault.contentAlias(deadline, messageId);
        byte[] encrypted = vault.seal(alias, hello);
        assertEquals(AndroidVault.RECORD_VERSION, encrypted[0]);
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("hello"));
        assertArrayEquals(hello, vault.unseal(alias, encrypted));
        AndroidVault.deleteContentKey(deadline, messageId);
        assertThrows(SecurityException.class, () -> vault.unseal(alias, encrypted));
        vault.transaction(() -> { vault.remove(key); return null; });
        vault.close();
    }

    static final class MemoryVault implements SecureVault {
        private Map<String, byte[]> values = new HashMap<>();
        public byte[] get(String name) { return values.get(name); }
        public void put(String name, byte[] value) { values.put(name, value.clone()); }
        public void remove(String name) { values.remove(name); }
        public List<String> names(String prefix) {
            List<String> names = new ArrayList<>();
            for (String name : values.keySet()) if (name.startsWith(prefix)) names.add(name);
            return names;
        }
        public <Result> Result transaction(Operation<Result> operation) throws Exception {
            Map<String, byte[]> previous = new HashMap<>(values);
            try { return operation.run(); }
            catch (Exception failure) { values = previous; throw failure; }
        }
    }
}