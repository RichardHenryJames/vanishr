package app.vanishr.android;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.security.keystore.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.vanishr.crypto.*;
import com.google.android.material.checkbox.MaterialCheckBox;
import org.junit.*;
import org.junit.runner.RunWith;

import javax.crypto.KeyGenerator;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScreenFlowTest {
    private Context context;
    private AndroidVault vault;
    private ChatEngine engine;
    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID peerId = UUID.randomUUID();
    private final UUID peerDevice = UUID.randomUUID();
    private ChatEngine.Peer peer;
    private ChatEngine.Entry once;
    private byte[] photo;

    @Before public void isolatedSyntheticFixture() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue("Screen fixtures must never touch the installed release app", context.getPackageName().equals("app.vanishr.android.qa"));
        assertTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"));
        resetQaFixture();
        assertTrue(context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear()
            .putLong("last-check", System.currentTimeMillis()).commit());
        createFixtureKey(AndroidVault.MASTER);
        vault = new AndroidVault(context); vault.unlock(); engine = new ChatEngine(vault);
        Bitmap image = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.rgb(191, 222, 234));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(235, 196, 91)); canvas.drawCircle(560, 100, 42, paint);
        paint.setColor(Color.rgb(82, 134, 124));
        Path mountain = new Path(); mountain.moveTo(0, 360); mountain.lineTo(220, 90); mountain.lineTo(470, 360); mountain.close(); canvas.drawPath(mountain, paint);
        paint.setColor(Color.rgb(42, 98, 84)); canvas.drawRect(0, 350, 720, 480, paint);
        photo = SafeImages.encode(image); image.recycle();
    }

    @After public void clearOnlySyntheticQaData() throws Exception {
        if (context == null || !context.getPackageName().equals("app.vanishr.android.qa")) return;
        if (engine != null) engine.close();
        if (photo != null) Arrays.fill(photo, (byte) 0);
        resetQaFixture();
        assertTrue(context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit());
    }

    private void resetQaFixture() throws Exception {
        new android.util.AtomicFile(new File(context.getNoBackupFilesDir(), "vault.bin")).delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        for (String alias : Collections.list(store.aliases())) if (alias.startsWith("vanishr.")) store.deleteEntry(alias);
    }

    private void createFixtureKey(String alias) throws Exception {
        createRawFixtureKey(AndroidVault.phoneAlias(alias));
    }

    private void createRawFixtureKey(String alias) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        generator.generateKey();
    }

    private byte[] legacySeal(String alias, byte[] plaintext) throws Exception {
        createRawFixtureKey(alias);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, store.getKey(alias, null));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        return java.nio.ByteBuffer.allocate(13 + encrypted.length).put((byte) 1).put(cipher.getIV()).put(encrypted).array();
    }

    private void legacyVaultFile() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            List<String> names = vault.names("");
            output.writeInt(names.size());
            for (String name : names) {
                byte[] value = vault.get(name);
                try { output.writeUTF(name); output.writeInt(value.length); output.write(value); }
                finally { Arrays.fill(value, (byte) 0); }
            }
        }
        byte[] plaintext = bytes.toByteArray();
        byte[] encrypted;
        try { encrypted = legacySeal(AndroidVault.MASTER, plaintext); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        vault.close();
        java.nio.file.Files.write(new File(context.getNoBackupFilesDir(), "vault.bin").toPath(), encrypted);
    }

    private void write(String name, Object value) { vault.put(name, RelayApi.JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }

    private void signedInFixture() throws Exception {
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "alex", userId, deviceId, "synthetic-fixture", System.currentTimeMillis() + 3_600_000, true));
            return null;
        });
        engine = new ChatEngine(vault); engine.online = true;
    }

    private void conversationsFixture() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "Aarav Mehta");
        vault.transaction(() -> {
            new SignalClient(userId, vault).verifyPeer(peerId, remote.publicIdentity());
            write("contact/" + peerId, peer);
            write("contact/" + UUID.randomUUID(), new ChatEngine.Peer(UUID.randomUUID(), UUID.randomUUID(), peer.identityKey(), "Maya Chen"));
            write("contact/" + UUID.randomUUID(), new ChatEngine.Peer(UUID.randomUUID(), UUID.randomUUID(), peer.identityKey(), "Noah Williams"));
            return null;
        });
        entry(false, "Coffee at 4?", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -240_000);
        entry(true, "Sounds good. Same place?", null, ChatEnvelope.Expiry.HOUR_1, "READ", -210_000);
        entry(false, "Yes, see you there.", null, ChatEnvelope.Expiry.HOUR_1, "READ", -180_000);
        entry(true, "Here's the view from this morning.", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -150_000);
        entry(true, null, photo, ChatEnvelope.Expiry.HOUR_1, "QUEUED", -120_000);
        once = entry(false, "This message is visible once.", null, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -90_000);
        entry(true, "A longer message should wrap naturally on a narrow display without covering the timestamp or the send controls.", null, ChatEnvelope.Expiry.HOUR_1, "PENDING", -60_000);
    }

    private ChatEngine.Entry entry(boolean outgoing, String text, byte[] image, ChatEnvelope.Expiry expiry, String state, long offset) throws Exception {
        UUID id = UUID.randomUUID(); long created = System.currentTimeMillis() + offset; long deadline = created + expiry.milliseconds;
        createFixtureKey(AndroidVault.contentAlias(deadline, id));
        ChatEnvelope envelope = new ChatEnvelope(1, id, outgoing ? userId : peerId, outgoing ? deviceId : peerDevice,
                outgoing ? peerId : userId, outgoing ? peerDevice : deviceId, created, deadline, expiry, text, null);
        ChatEngine.Entry entry = new ChatEngine.Entry(id, peerId, deadline, expiry, outgoing, image != null, state);
        vault.transaction(() -> {
            write("entry/" + id, entry);
            byte[] content = RelayApi.JSON.toJson(new ChatEngine.Content(envelope, image)).getBytes(StandardCharsets.UTF_8);
            try { vault.put("body/" + id, vault.seal(AndroidVault.contentAlias(deadline, id), content)); }
            finally { Arrays.fill(content, (byte) 0); }
            return null;
        });
        return entry;
    }

    private static Object readField(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void setField(Object owner, String name, Object value) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args); }
        catch (Exception failure) { throw new AssertionError(failure.getCause() == null ? failure : failure.getCause()); }
    }

    private static Object invoke(Object owner, String name) { return invoke(owner, name, new Class<?>[0]); }

    private View root(MainActivity activity) { return (View) readField(activity, "root"); }
    private AlertDialog dialog(MainActivity activity) { return (AlertDialog) readField(activity, "openDialog"); }

    private static List<View> descendants(View view) {
        List<View> result = new ArrayList<>(); result.add(view);
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) result.addAll(descendants(group.getChildAt(index)));
        return result;
    }

    private View text(View root, String value) {
        return descendants(root).stream().filter(view -> view instanceof TextView label && label.getText().toString().equals(value)).findFirst().orElseThrow(() -> new AssertionError("Missing control: " + value));
    }

    private void tapText(ActivityScenario<MainActivity> scenario, String value) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation);
        Rect bounds = new Rect();
        var ready = new java.util.concurrent.CountDownLatch(1);
        View[] target = new View[1];
        ViewTreeObserver.OnGlobalLayoutListener[] listener = new ViewTreeObserver.OnGlobalLayoutListener[1];
        scenario.onActivity(activity -> {
            View control = text(root(activity), value);
            assertTrue("Touch target must be enabled: " + value, control.isEnabled());
            target[0] = control;
            listener[0] = () -> {
                if (control.getWidth() == 0 || control.getHeight() == 0) return;
                control.requestRectangleOnScreen(new Rect(0, 0, control.getWidth(), control.getHeight()), true);
                if (control.getGlobalVisibleRect(bounds) && bounds.height() >= control.getHeight() / 2) ready.countDown();
            };
            control.getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
            control.post(listener[0]::onGlobalLayout);
        });
        try { assertTrue("Visible touch target is missing: " + value, ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { scenario.onActivity(activity -> target[0].getViewTreeObserver().removeOnGlobalLayoutListener(listener[0])); }
        assertTrue("Touch injection failed: " + value, device.click(bounds.centerX(), bounds.centerY()));
        instrumentation.waitForIdleSync();
    }

    private void inject(MainActivity activity) {
        setField(activity, "screenGeneration", (int) readField(activity, "screenGeneration") + 1);
        ChatEngine loaded = (ChatEngine) readField(activity, "engine");
        if (loaded != null && loaded != engine && readField(loaded, "vault") != vault) loaded.close();
        setField(activity, "engine", engine); invoke(activity, "render");
    }

    private void snapshot(ActivityScenario<MainActivity> scenario, String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            AlertDialog current = dialog(activity);
            View view = current != null && current.isShowing() ? current.getWindow().getDecorView() : activity.getWindow().getDecorView();
            if (current != null && current.isShowing()) assertTrue((current.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertTrue("The screen must be laid out", view.getWidth() > 0 && view.getHeight() > 0);
            Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(image));
            File folder = new File(context.getExternalFilesDir(null), "ui-qa"); assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            catch (IOException failure) { throw new AssertionError(failure); }
            finally { image.recycle(); }
            for (View child : descendants(view)) {
                if (!(child instanceof TextView label) || child instanceof EditText || !child.isShown() || label.getLayout() == null) continue;
                int available = label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight();
                for (int line = 0; line < label.getLineCount(); line++) {
                    assertTrue("Text overflows in " + name, label.getLayout().getLineMax(line) <= available + 4 || label.getLayout().getEllipsisCount(line) > 0);
                    if (label instanceof Button) assertEquals("Button label is truncated in " + name, 0, label.getLayout().getEllipsisCount(line));
                }
            }
        });
    }

    @Test public void sendTapClearsComposerAndShowsPendingBeforeWorkerStarts() throws Exception {
        signedInFixture(); conversationsFixture();
        var workerStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseWorker = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    inject(activity); setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                    var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
                    worker.execute(() -> {
                        workerStarted.countDown();
                        try { releaseWorker.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    });
                });
                assertTrue("The worker fixture must be blocked", workerStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    EditText input = (EditText) readField(activity, "composer");
                    input.setText("Immediate send fixture");
                    View send = descendants(root(activity)).stream()
                        .filter(view -> "Send".equals(view.getContentDescription())).findFirst().orElseThrow();
                    assertTrue(send.performClick());
                    assertEquals("Sending must clear the composer before background work runs", "", input.getText().toString());
                    assertNotNull(text(root(activity), "Immediate send fixture"));
                    assertNotNull(text(root(activity), "Sending"));
                    input.setText("Second immediate send"); send.performClick();
                    assertEquals("", input.getText().toString());
                    assertNotNull(text(root(activity), "Second immediate send"));
                    assertEquals(2, ((List<?>) readField(activity, "pendingSends")).size());
                    send.performClick();
                    assertEquals("An empty second tap must not duplicate a message", 2, ((List<?>) readField(activity, "pendingSends")).size());
                    input.setText("Keep the next draft");
                    assertEquals("Keep the next draft", input.getText().toString());
                });
            } finally { releaseWorker.countDown(); }
        }
    }

    @Test public void failedSendKeepsNewDraftAndRetryKeepsItsDeadline() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Object[] pending = new Object[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                EditText input = (EditText) readField(activity, "composer"); input.setText("Offline first send");
                descendants(root(activity)).stream().filter(view -> "Send".equals(view.getContentDescription())).findFirst().orElseThrow().performClick();
                pending[0] = ((List<?>) readField(activity, "pendingSends")).get(0);
                input.setText("Do not replace this new draft");
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Not sent")), 10_000));
            Object id = readField(pending[0], "id"); Object createdAt = readField(pending[0], "createdAt");
            scenario.onActivity(activity -> {
                assertEquals("Do not replace this new draft", ((EditText) readField(activity, "composer")).getText().toString());
                descendants(root(activity)).stream().filter(view -> "Retry send".equals(view.getContentDescription())).findFirst().orElseThrow().performClick();
                assertNotNull(text(root(activity), "Sending"));
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Not sent")), 10_000));
            scenario.onActivity(activity -> {
                assertEquals(1, ((List<?>) readField(activity, "pendingSends")).size());
                assertEquals(id, readField(pending[0], "id")); assertEquals(createdAt, readField(pending[0], "createdAt"));
                assertEquals("Do not replace this new draft", ((EditText) readField(activity, "composer")).getText().toString());
                assertTrue(vault.names("outbox/").isEmpty());
                descendants(root(activity)).stream().filter(view -> "Discard unsent message".equals(view.getContentDescription())).findFirst().orElseThrow().performClick();
                assertTrue(((List<?>) readField(activity, "pendingSends")).isEmpty());
                assertNull(readField(pending[0], "text"));
            });
        }
    }

    @Test public void backgroundingClearsAndCancelsUnstoredSends() throws Exception {
        signedInFixture(); conversationsFixture();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService[] worker = new java.util.concurrent.ExecutorService[1];
        Object[] pending = new Object[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    inject(activity); setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                    worker[0] = (java.util.concurrent.ExecutorService) readField(activity, "work");
                    worker[0].execute(() -> {
                        started.countDown();
                        try { release.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    });
                });
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    assertTrue((boolean) invoke(activity, "enqueueSend", new Class<?>[]{ChatEngine.Peer.class, String.class, byte[].class, ChatEnvelope.Expiry.class},
                        peer, null, photo, ChatEnvelope.Expiry.HOUR_1));
                    pending[0] = ((List<?>) readField(activity, "pendingSends")).get(0);
                });
                scenario.moveToState(Lifecycle.State.STARTED);
                assertEquals(true, readField(pending[0], "cancelled"));
                assertNull(readField(pending[0], "text")); assertNull(readField(pending[0], "image"));
            } finally { release.countDown(); }
            worker[0].submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        vault.unlock();
        assertTrue("Cancelled in-memory sends must not enter the durable outbox", vault.names("outbox/").isEmpty());
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    public void composerRemainsAccessibleWithKeyboardAndExpiryRemovesVisibleContent() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            int[] composerCenter = new int[2];
            java.util.concurrent.CountDownLatch keyboardVisible = new java.util.concurrent.CountDownLatch(1);
            ViewTreeObserver.OnGlobalLayoutListener[] layoutListener = new ViewTreeObserver.OnGlobalLayoutListener[1];
            scenario.onActivity(activity -> {
                EditText input = (EditText) readField(activity, "composer"); input.requestFocus(); input.setText("A keyboard-safe draft");
                input.getLocationOnScreen(composerCenter);
                composerCenter[0] += input.getWidth() / 2; composerCenter[1] += input.getHeight() / 2;
                View decor = activity.getWindow().getDecorView();
                layoutListener[0] = () -> {
                    WindowInsets insets = decor.getRootWindowInsets();
                    if (insets != null && insets.isVisible(WindowInsets.Type.ime())) keyboardVisible.countDown();
                };
                decor.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener[0]);
                input.post(() -> androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), input)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime()));
            });
            androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.click(composerCenter[0], composerCenter[1]);
            try { assertTrue("Keyboard should open", keyboardVisible.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { scenario.onActivity(activity -> activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener[0])); }
            snapshot(scenario, "20-keyboard");
            scenario.onActivity(activity -> {
                View input = (View) readField(activity, "composer");
                android.graphics.Rect bounds = new android.graphics.Rect(); assertTrue(input.getGlobalVisibleRect(bounds));
                int keyboardHeight = activity.getWindow().getDecorView().getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
                assertTrue("Composer must stay above the keyboard", bounds.bottom <= activity.getWindow().getDecorView().getHeight() - keyboardHeight + 4);
                activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
            });
            ChatEngine.Entry expires = entry(false, "Expiring test content", null, ChatEnvelope.Expiry.HOUR_1, "READ", -3_599_000);
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            assertTrue(device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.text("Expiring test content")), 5000));
            engine.purge(); assertNull(vault.get("body/" + expires.id()));
            snapshot(scenario, "21-expired");
        }
    }

    @Test public void loginModesRemainActionableAndConnectionSettingsAreAbsent() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            scenario.onActivity(activity -> {
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                    && ("Connection settings".contentEquals(label.getText()) || android.text.TextUtils.equals("Relay address", label.getHint()))));
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label && "Replace previous device".contentEquals(label.getText())));
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
            });
            snapshot(scenario, "22a-login-before-tap");
            tapText(scenario, "Sign up");
            scenario.onActivity(activity -> {
                assertTrue(((com.google.android.material.button.MaterialButton) text(root(activity), "Sign up")).isChecked());
                assertTrue(text(root(activity), "Create account").isEnabled());
                invoke(activity, "render");
                assertTrue(((com.google.android.material.button.MaterialButton) text(root(activity), "Sign up")).isChecked());
            });
            tapText(scenario, "Create account");
            scenario.onActivity(activity -> {
                assertTrue(descendants(root(activity)).stream().anyMatch(view -> view instanceof com.google.android.material.textfield.TextInputLayout field && field.getError() != null));
            });
            tapText(scenario, "Sign in");
            scenario.onActivity(activity -> {
                assertFalse((boolean) readField(activity, "signingUp"));
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof com.google.android.material.textfield.TextInputLayout field && field.getError() != null));
            });
            snapshot(scenario, "22-login-controls");
        }
    }

    @Test public void googleFailuresRemainVisibleAfterReturningFromThePicker() throws Exception {
        assertEquals(GoogleSignIn.Failure.CANCELLED, GoogleSignIn.failureFor(new androidx.credentials.exceptions.GetCredentialCancellationException("synthetic-private-detail")));
        assertEquals(GoogleSignIn.Failure.NO_ACCOUNT, GoogleSignIn.failureFor(new androidx.credentials.exceptions.NoCredentialException("synthetic-private-detail")));
        assertEquals(GoogleSignIn.Failure.UNAVAILABLE, GoogleSignIn.failureFor(new androidx.credentials.exceptions.GetCredentialProviderConfigurationException("synthetic-private-detail")));
        assertEquals(GoogleSignIn.Failure.UNAVAILABLE, GoogleSignIn.failureFor(new androidx.credentials.exceptions.GetCredentialUnsupportedException("synthetic-private-detail")));
        assertEquals(GoogleSignIn.Failure.FAILED, GoogleSignIn.failureFor(new androidx.credentials.exceptions.GetCredentialUnknownException("synthetic-private-detail")));
        for (GoogleSignIn.Failure failure : GoogleSignIn.Failure.values()) assertFalse(failure.message.contains("synthetic-private-detail"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> {
                invoke(activity, "googleFailed", new Class<?>[]{GoogleSignIn.Failure.class}, GoogleSignIn.Failure.NO_ACCOUNT);
                assertNull(readField(activity, "engine"));
                assertEquals(GoogleSignIn.Failure.NO_ACCOUNT, readField(activity, "googleFailure"));
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(GoogleSignIn.Failure.NO_ACCOUNT.message)), 10_000));
            scenario.onActivity(activity -> {
                assertNotNull("The unlocked phone must resume without an extra app prompt", readField(activity, "engine"));
                assertEquals(GoogleSignIn.Failure.NO_ACCOUNT.message, ((TextView) readField(activity, "actionError")).getText().toString());
                assertEquals(View.VISIBLE, ((View) readField(activity, "actionError")).getVisibility());
                assertNull(readField(activity, "googleFailure"));
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
            });
            snapshot(scenario, "23-google-error");
        }
    }

    @Test public void returningAccountsShowOnlyTheirOwnSignInMethod() throws Exception {
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "alex", userId, deviceId, "synthetic-expired", 0, true));
            return null;
        });
        engine = new ChatEngine(vault);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                        && ("Sign up".contentEquals(label.getText()) || "Continue with Google".contentEquals(label.getText()))));
                assertTrue(text(root(activity), "Sign in").isEnabled());
            });
            vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
            scenario.onActivity(activity -> {
                invoke(activity, "render");
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof EditText));
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label && "Sign up".contentEquals(label.getText())));
            });
            snapshot(scenario, "24-google-returning");
        }
    }

    @Test public void signingOutPreservesEncryptedAccountStateAndKeysUntilExpiry() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.applyProfile(new ChatEngine.Profile(userId, "alex", "Local test profile"));
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String previousIdentity = engine.identityCode();
        byte[] previousBody = vault.get("body/" + once.id());
        long previousDeadline = once.expiresAt();
        engine.logout();
        assertNull(engine.account());
        assertFalse(engine.authenticated());
        assertThrows(SecurityException.class, () -> vault.get("account"));
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertTrue(store.containsAlias(AndroidVault.phoneAlias(AndroidVault.MASTER)));
        assertTrue(store.containsAlias(AndroidVault.phoneAlias(AndroidVault.contentAlias(previousDeadline, once.id()))));
        assertTrue(new File(context.getNoBackupFilesDir(), "vault.bin").exists());
        vault.unlock(); engine = new ChatEngine(vault);
        assertFalse(engine.usesGoogle());
        assertTrue(engine.peers().isEmpty());
        assertTrue(engine.entries(null).isEmpty());
        assertNull(vault.get("account"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                assertTrue(text(root(activity), "Sign up").isEnabled());
                assertTrue(text(root(activity), "Sign in").isEnabled());
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
            });
            snapshot(scenario, "25-signed-out");
        }
        vault.unlock();
        assertTrue(vault.restoreAccount(userId));
        engine = new ChatEngine(vault);
        assertEquals(previousIdentity, engine.identityCode());
        assertEquals(deviceId, engine.activeDeviceId());
        assertEquals("", engine.account().accessToken());
        assertFalse(engine.authenticated());
        assertTrue(engine.usesGoogle());
        assertEquals("Local test profile", engine.displayName());
        assertFalse(engine.peers().isEmpty());
        assertArrayEquals(previousBody, vault.get("body/" + once.id()));
        assertEquals(previousDeadline, engine.entries(peerId).stream().filter(entry -> entry.id().equals(once.id())).findFirst().orElseThrow().expiresAt());
        Arrays.fill(previousBody, (byte) 0);
    }

    @Test public void incompleteGoogleEnrollmentDoesNotClaimAnActiveDevice() throws Exception {
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        assertFalse("An orphan provider marker must not prevent a new password login", engine.usesGoogle());
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "google_test", userId, deviceId, "synthetic-expired", 0, false));
            return null;
        });
        engine = new ChatEngine(vault);
        assertTrue(engine.usesGoogle());
        assertNull("A device not yet enrolled must request a fresh enrollment token", engine.activeDeviceId());
        assertFalse(engine.authenticated());
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "google_test", userId, deviceId, "synthetic-expired", 0, true));
            return null;
        });
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertEquals(deviceId, engine.activeDeviceId());
        assertTrue(engine.usesGoogle());
    }

    @Test public void signedOutExpiryAndViewOnceConsumptionDoNotResetOnReturn() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.content(once, true);
        ChatEngine.Entry expired = entry(false, "expired while signed out", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -3_601_000);
        vault.transaction(() -> {
            write("ack/" + expired.id(), new ChatEngine.Ack(expired.id(), expired.expiresAt(), "delivered"));
            vault.put("seen/" + expired.id(), Long.toString(expired.expiresAt()).getBytes(StandardCharsets.US_ASCII));
            return null;
        });
        engine.logout();
        vault.unlock(); engine = new ChatEngine(vault);
        String prefix = AndroidVault.savedAccountPrefix(userId);
        assertNull(vault.get(prefix + "body/" + once.id()));
        assertNotNull("An offline read acknowledgement must survive sign-out for later retry", vault.get(prefix + "ack/" + once.id()));
        assertNull(vault.get(prefix + "entry/" + expired.id()));
        assertNull(vault.get(prefix + "body/" + expired.id()));
        assertNull(vault.get(prefix + "ack/" + expired.id()));
        assertNull(vault.get(prefix + "seen/" + expired.id()));
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse(store.containsAlias(AndroidVault.phoneAlias(AndroidVault.contentAlias(expired.expiresAt(), expired.id()))));
        assertTrue(vault.restoreAccount(userId));
        engine = new ChatEngine(vault);
        assertTrue(engine.entries(peerId).stream().noneMatch(value -> value.id().equals(once.id()) || value.id().equals(expired.id())));
    }

    @Test public void parkedAccountsCannotMixIdentitiesContactsOrPendingSends() throws Exception {
        signedInFixture(); conversationsFixture();
        String firstIdentity = engine.identityCode();
        UUID pendingId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 60_000;
        ChatEngine.Send pending = new ChatEngine.Send(pendingId, peerId, peerDevice, ChatEnvelope.Expiry.HOUR_1, deadline, 2, new byte[32], null);
        vault.transaction(() -> { write("outbox/" + pendingId, new ChatEngine.Outbox(pending, null)); return null; });
        engine.logout();
        vault.unlock(); engine = new ChatEngine(vault);
        UUID secondUser = UUID.randomUUID();
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "another_account", secondUser, UUID.randomUUID(), "", 0, true));
            return null;
        });
        engine = new ChatEngine(vault);
        String secondIdentity = engine.identityCode();
        assertNotEquals(firstIdentity, secondIdentity);
        assertTrue(engine.peers().isEmpty());
        assertTrue(vault.names("outbox/").isEmpty());
        assertThrows(SecurityException.class, () -> vault.restoreAccount(userId));
        assertEquals(secondIdentity, engine.identityCode());
        engine.logout();
        vault.unlock();
        assertTrue(vault.restoreAccount(userId));
        engine = new ChatEngine(vault);
        assertEquals(firstIdentity, engine.identityCode());
        assertFalse(engine.peers().isEmpty());
        assertEquals(deadline, RelayApi.JSON.fromJson(new String(vault.get("outbox/" + pendingId), StandardCharsets.UTF_8), ChatEngine.Outbox.class).message().expiresAt());
        engine.logout();
        vault.unlock();
        assertTrue(vault.restoreAccount(secondUser));
        engine = new ChatEngine(vault);
        assertEquals(secondIdentity, engine.identityCode());
        assertTrue(engine.peers().isEmpty());
        assertTrue(vault.names("outbox/").isEmpty());
    }

    @Test public void profileNamePersistsAndSignOutRequiresConfirmation() throws Exception {
        signedInFixture();
        engine.applyProfile(new ChatEngine.Profile(userId, "alex", "Alex Rivera"));
        assertEquals("Alex Rivera", engine.displayName());
        assertThrows(IllegalArgumentException.class, () -> engine.renameProfile("  "));
        assertThrows(IllegalArgumentException.class, () -> engine.renameProfile("x".repeat(41)));
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertEquals("Alex Rivera", engine.displayName());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                int edge = Math.round(20 * context.getResources().getDisplayMetrics().density);
                assertTrue(root(activity).getPaddingLeft() >= edge);
                assertTrue(root(activity).getPaddingRight() >= edge);
                assertTrue(descendants(root(activity)).stream().anyMatch(view -> "Profile".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())));
                invoke(activity, "accountDialog");
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Alex Rivera"));
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "@alex"));
                text(dialog(activity).getWindow().getDecorView(), "Sign out").performClick();
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Sign out of this device?"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertTrue(engine.authenticated());
                assertEquals("Alex Rivera", engine.displayName());
            });
            scenario.onActivity(activity -> invoke(activity, "accountDialog"));
            snapshot(scenario, "26-profile");
        }
    }

    @Test public void ownUsernameChangesRetainIdentityAndRejectAnotherAccountsProfile() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        ChatEngine.Account original = engine.account();
        byte[] content = vault.get("body/" + once.id());
        assertEquals("new_alex", ChatEngine.validUsername(" @NEW_ALEX "));
        assertThrows(IllegalArgumentException.class, () -> ChatEngine.validUsername("not a username"));
        assertThrows(IllegalArgumentException.class, () -> ChatEngine.validUsername("a"));
        assertThrows(IllegalArgumentException.class, () -> ChatEngine.validUsername("x".repeat(33)));
        assertThrows(SecurityException.class, () -> engine.applyProfile(new ChatEngine.Profile(UUID.randomUUID(), "attacker", "Other account")));
        assertEquals(original, engine.account());
        engine.applyProfile(new ChatEngine.Profile(userId, "new_alex", "Alex Public"));
        assertEquals("new_alex", engine.account().handle());
        assertEquals("Alex Public", engine.displayName());
        assertEquals(original.userId(), engine.account().userId());
        assertEquals(original.deviceId(), engine.account().deviceId());
        assertEquals(original.accessToken(), engine.account().accessToken());
        assertEquals(original.expiresAt(), engine.account().expiresAt());
        assertEquals(identity, engine.identityCode());
        assertArrayEquals(content, vault.get("body/" + once.id()));
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertEquals("new_alex", engine.account().handle());
        assertEquals(identity, engine.identityCode());
        Arrays.fill(content, (byte) 0);
    }

    @Test public void profileInlineSavesPreserveOtherDraftAndRecoverFromFailure() throws Exception {
        signedInFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity, "accountDialog"); });
            snapshot(scenario, "31-profile-inline-save");
            scenario.onActivity(activity -> {
                View decor = dialog(activity).getWindow().getDecorView();
                var fields = descendants(decor).stream().filter(view -> view instanceof com.google.android.material.textfield.TextInputLayout)
                    .map(view -> (com.google.android.material.textfield.TextInputLayout) view).toList();
                assertEquals(2, fields.size());
                for (var field : fields) {
                    View save = field.findViewById(com.google.android.material.R.id.text_input_end_icon);
                    assertEquals(com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM, field.getEndIconMode());
                    assertTrue(save.getWidth() >= Math.round(48 * context.getResources().getDisplayMetrics().density));
                    assertTrue(save.getHeight() >= Math.round(48 * context.getResources().getDisplayMetrics().density));
                    assertEquals(save.getContentDescription(), save.getTooltipText());
                }
                fields.get(1).getEditText().setText("unsaved_username");
                fields.get(0).getEditText().setText("Changed profile");
                descendants(decor).stream().filter(view -> "Save name".equals(view.getContentDescription())).findFirst().orElseThrow().performClick();
                assertFalse(fields.get(0).isEnabled());
                assertEquals("unsaved_username", fields.get(1).getEditText().getText().toString());
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Could not save. Check your connection and try again.")), 10_000));
            scenario.onActivity(activity -> {
                View decor = dialog(activity).getWindow().getDecorView();
                assertNotNull(text(decor, "My profile"));
                List<EditText> inputs = descendants(decor).stream().filter(view -> view instanceof EditText).map(view -> (EditText) view).toList();
                assertTrue(inputs.get(0).isEnabled());
                assertEquals("Changed profile", inputs.get(0).getText().toString());
                assertEquals("unsaved_username", inputs.get(1).getText().toString());
                assertEquals("alex", engine.account().handle());
                inputs.get(0).setText("Try another name");
                assertTrue(descendants(decor).stream().filter(view -> view instanceof com.google.android.material.textfield.TextInputLayout)
                    .map(view -> (com.google.android.material.textfield.TextInputLayout) view).allMatch(field -> field.getError() == null && field.getHelperText() == null));
            });
        }
    }

    @Test public void privateNamesStayLocalWhenSharedProfileOrUsernameChanges() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.applyProfile(new ChatEngine.Profile(userId, "alex", "Owner Alpha"));
        engine.applyContactProfile(peerId, new ChatEngine.Profile(peerId, "friend_handle", "Friend Public"));
        ChatEngine.Peer friend = engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow();
        String pinned = friend.identityKey();
        engine.renameContact(friend, "  abc  ");
        engine.applyContactProfile(peerId, new ChatEngine.Profile(peerId, "friend_renamed", "Friend Updated"));
        friend = engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow();
        assertEquals("abc", friend.name());
        assertEquals("friend_renamed", friend.username());
        assertEquals("Friend Updated", friend.profileName());
        assertEquals(pinned, friend.identityKey());
        assertEquals(peerDevice, friend.deviceId());
        assertEquals("Owner Alpha", engine.displayName());
        assertThrows(SecurityException.class, () -> engine.applyContactProfile(peerId, new ChatEngine.Profile(userId, "alex", "Wrong account")));
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertEquals("abc", engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow().name());
        engine.logout(); vault.unlock();
        UUID secondUser = UUID.randomUUID();
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "other_owner", secondUser, UUID.randomUUID(), "", 0, true));
            write("contact/" + peerId, new ChatEngine.Peer(peerId, peerDevice, pinned, "friend_renamed"));
            return null;
        });
        engine = new ChatEngine(vault);
        engine.applyProfile(new ChatEngine.Profile(secondUser, "other_owner", "Owner Beta"));
        ChatEngine.Peer secondContact = engine.peers().get(0);
        assertEquals("", engine.privateName(secondContact));
        engine.renameContact(secondContact, "xyz");
        assertEquals("friend_renamed", engine.peers().get(0).username());
        assertEquals("xyz", engine.peers().get(0).name());
        engine.logout(); vault.unlock(); assertTrue(vault.restoreAccount(userId)); engine = new ChatEngine(vault);
        friend = engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow();
        assertEquals("abc", friend.name());
        assertEquals("Owner Alpha", engine.displayName());
        engine.renameContact(friend, "");
        friend = engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow();
        assertEquals("Friend Updated", friend.name());
        assertEquals("friend_renamed", friend.username());
        assertEquals("", engine.privateName(friend));
    }

    @Test public void conversationProfileEditsContactNameButNeverTheirUsername() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.applyProfile(new ChatEngine.Profile(userId, "alex", "My own profile"));
        engine.applyContactProfile(peerId, new ChatEngine.Profile(peerId, "friend_name", "Friend Public"));
        ChatEngine.Peer friend = engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow();
        engine.renameContact(friend, "abc");
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                assertNotNull(text(root(activity), "abc"));
                assertNotNull(text(root(activity), "@friend_name"));
                invoke(activity, "accountDialog");
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> "Save username".equals(view.getContentDescription())));
                assertEquals(2, descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof EditText).count());
            });
            snapshot(scenario, "28-profile-editable-username");
            scenario.onActivity(activity -> {
                dialog(activity).dismiss();
                setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                View options = descendants(root(activity)).stream().filter(view -> "Conversation options".contentEquals(
                        view.getContentDescription() == null ? "" : view.getContentDescription())).findFirst().orElseThrow();
                assertTrue(options.performClick());
            });
            var profileItem = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Profile")), 5000);
            assertNotNull("The conversation must offer the contact's profile", profileItem);
            assertFalse("The duplicate name action must be removed", device.hasObject(androidx.test.uiautomator.By.text("Private contact name")));
            profileItem.click();
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Contact profile")), 5000));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View decor = dialog(activity).getWindow().getDecorView();
                assertNotNull(text(decor, "Friend Public"));
                assertFalse(text(decor, "@friend_name") instanceof EditText);
                assertFalse(descendants(decor).stream().anyMatch(view -> view instanceof TextView label &&
                        ("Save username".contentEquals(label.getText()) || "@alex".contentEquals(label.getText()) || "Sign out".contentEquals(label.getText()))));
                List<EditText> inputs = descendants(decor).stream().filter(view -> view instanceof EditText).map(view -> (EditText) view).toList();
                assertEquals(1, inputs.size());
                assertEquals("Display name", inputs.get(0).getHint().toString());
                assertEquals("abc", inputs.get(0).getText().toString());
                inputs.get(0).setText("");
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(descendants(decor).stream().anyMatch(view -> view instanceof com.google.android.material.textfield.TextInputLayout field && field.getError() != null));
            });
            scenario.onActivity(activity -> {
                descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof EditText)
                        .map(view -> (EditText) view).findFirst().orElseThrow().setText("Saved contact");
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.text("Contact profile")), 5000));
            scenario.onActivity(activity -> {
                ChatEngine.Peer updated = engine.peers().stream().filter(value -> value.userId().equals(peerId)).findFirst().orElseThrow();
                assertEquals("Saved contact", updated.name());
                assertEquals("friend_name", updated.username());
                assertEquals("Friend Public", updated.profileName());
                assertEquals(friend.identityKey(), updated.identityKey());
                assertEquals("alex", engine.account().handle());
                assertEquals("My own profile", engine.displayName());
                invoke(activity, "contactProfileDialog", new Class<?>[]{ChatEngine.Peer.class}, updated);
            });
            snapshot(scenario, "29-contact-profile");
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        }
    }

    @Test public void emptyConversationGreetingIsHorizontallyCentered() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "Friend");
        vault.transaction(() -> { write("contact/" + peerId, peer); return null; });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            snapshot(scenario, "30-centered-empty-conversation");
            scenario.onActivity(activity -> {
                TextView greeting = (TextView) text(root(activity), "Just the two of you");
                View viewport = (View) readField(activity, "messageScroll");
                Rect labelBounds = new Rect(); Rect viewportBounds = new Rect();
                assertTrue(greeting.getGlobalVisibleRect(labelBounds));
                assertTrue(viewport.getGlobalVisibleRect(viewportBounds));
                assertTrue("Empty chat text must align with the horizontal center of the conversation",
                        Math.abs(labelBounds.exactCenterX() - viewportBounds.exactCenterX()) <= 2);
                assertEquals(Gravity.CENTER_HORIZONTAL, greeting.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
            });
        }
    }

    @Test public void legacyProtectionMigratesRetainedContentWithoutConsumingIt() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        String alias = AndroidVault.contentAlias(once.expiresAt(), once.id());
        byte[] plaintext = vault.unseal(alias, vault.get("body/" + once.id()));
        byte[] encrypted;
        try { encrypted = legacySeal(alias, plaintext); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        vault.transaction(() -> { vault.put("body/" + once.id(), encrypted); vault.saveAccount(userId); return null; });
        legacyVaultFile();
        vault.unlock(); engine = new ChatEngine(vault);
        String prefix = AndroidVault.savedAccountPrefix(userId);
        assertNull(engine.account());
        assertEquals(2, java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath())[0]);
        assertEquals(2, vault.get(prefix + "body/" + once.id())[0]);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse(store.containsAlias(AndroidVault.MASTER));
        assertFalse(store.containsAlias(alias));
        assertTrue(store.containsAlias(AndroidVault.phoneAlias(alias)));
        assertTrue(vault.restoreAccount(userId)); engine = new ChatEngine(vault);
        assertEquals(identity, engine.identityCode());
        ChatEngine.Entry retained = engine.entries(peerId).stream().filter(value -> value.id().equals(once.id())).findFirst().orElseThrow();
        assertEquals(once.expiresAt(), retained.expiresAt());
        assertEquals("DELIVERED", retained.state());
        assertEquals("This message is visible once.", engine.content(retained, true).envelope().text());
        assertNull(vault.get("body/" + once.id()));
        assertFalse(store.containsAlias(AndroidVault.phoneAlias(alias)));
    }

    @Test public void failedLegacyMigrationDoesNotOverwriteCiphertextOrDeleteKeys() throws Exception {
        signedInFixture(); conversationsFixture();
        String alias = AndroidVault.contentAlias(once.expiresAt(), once.id());
        byte[] legacy = legacySeal(alias, "synthetic old content".getBytes(StandardCharsets.UTF_8));
        legacy[legacy.length - 1] ^= 1;
        vault.transaction(() -> { vault.put("body/" + once.id(), legacy); return null; });
        byte[] before = java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath());
        assertThrows(Exception.class, () -> new ChatEngine(vault));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath()));
        assertArrayEquals(legacy, vault.get("body/" + once.id()));
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertTrue(store.containsAlias(alias));
    }

    @Test public void perAccountContentKeysKeepTheOtherCopyReadable() throws Exception {
        UUID messageId = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + 60_000;
        String senderAlias = AndroidVault.contentAlias(deadline, messageId, userId);
        String recipientAlias = AndroidVault.contentAlias(deadline, messageId, peerId);
        createFixtureKey(senderAlias); createFixtureKey(recipientAlias);
        byte[] plaintext = "same message, separate local accounts".getBytes(StandardCharsets.UTF_8);
        byte[] senderCopy = vault.seal(senderAlias, plaintext);
        byte[] recipientCopy = vault.seal(recipientAlias, plaintext);
        assertThrows(Exception.class, () -> vault.unseal(recipientAlias, senderCopy));
        AndroidVault.deleteContentKey(deadline, messageId, userId);
        assertThrows(SecurityException.class, () -> vault.unseal(senderAlias, senderCopy));
        assertArrayEquals(plaintext, vault.unseal(recipientAlias, recipientCopy));
        ChatEngine.Entry scoped = new ChatEngine.Entry(messageId, peerId, deadline, ChatEnvelope.Expiry.HOUR_1, false, false, "DELIVERED", userId);
        assertEquals(userId, scoped.withState("READ").keyOwner());
        ChatEngine.Entry legacy = RelayApi.JSON.fromJson("{\"id\":\"" + messageId + "\",\"peerId\":\"" + peerId
                + "\",\"expiresAt\":" + deadline + ",\"expiry\":\"HOUR_1\",\"outgoing\":true,\"image\":false,\"state\":\"QUEUED\"}", ChatEngine.Entry.class);
        assertNull(legacy.keyOwner());
        assertEquals(AndroidVault.contentAlias(deadline, messageId), AndroidVault.contentAlias(deadline, messageId, legacy.keyOwner()));
    }

    @Test public void relaySignInErrorsExplainConflictsWithoutEchoingInputs() {
        RelayApi.ApiFailure conflict = RelayApi.failure(409, "{\"error\":\"device_already_registered\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("device_already_registered", conflict.code);
        assertTrue(conflict.userMessage().contains("different registered device"));
        assertFalse(conflict.userMessage().contains("ciphertext"));
        assertTrue(RelayApi.failure(429, new byte[0]).userMessage().contains("Too many requests"));
        assertTrue(RelayApi.failure(503, new byte[0]).userMessage().contains("temporarily unavailable"));
        RelayApi.ApiFailure unknown = RelayApi.failure(400, "{\"error\":\"synthetic-private-detail\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("", unknown.code);
        assertFalse(unknown.userMessage().contains("synthetic-private-detail"));
        assertFalse(unknown.getMessage().contains("synthetic-private-detail"));
        assertEquals("", RelayApi.failure(409, "not json".getBytes(StandardCharsets.UTF_8)).code);
        assertEquals("", RelayApi.failure(409, new byte[4097]).code);
    }

    @Test public void usernameContactFormIsSimpleButDoesNotAutoTrustIdentities() throws Exception {
        signedInFixture();
        assertThrows(IllegalArgumentException.class, () -> engine.findPeer("../account"));
        assertThrows(IllegalArgumentException.class, () -> engine.findPeer("ab"));
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        ChatEngine.Peer candidate = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "test_friend");
        String number = ChatEngine.safetyNumber(candidate.userId(), candidate.identityKey());
        assertTrue(number.matches("[0-9A-F]{8}( [0-9A-F]{8}){7}"));
        assertNotEquals(number, ChatEngine.safetyNumber(UUID.randomUUID(), candidate.identityKey()));
        assertThrows(SecurityException.class, () -> ChatEngine.safetyNumber(peerId, Base64.getEncoder().encodeToString(new byte[32])));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity, "addContactDialog"); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(1, descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof EditText).count());
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> view instanceof com.google.android.material.textfield.TextInputLayout field && field.getError() != null));
                dialog(activity).dismiss();
                invoke(activity, "verifyContactDialog", new Class<?>[]{ChatEngine.Peer.class}, candidate);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertTrue(engine.peers().isEmpty());
                assertTrue(vault.names("peer/").isEmpty());
            });
            snapshot(scenario, "27-verify-username-contact");
            scenario.onActivity(activity -> {
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertTrue(engine.peers().isEmpty());
                assertTrue(vault.names("peer/").isEmpty());
            });
        }
    }

    @Test public void allPrimaryScreensControlsAndPrivacyStates() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            snapshot(scenario, "01-opening");
            scenario.onActivity(this::inject); snapshot(scenario, "02-sign-in");
            scenario.onActivity(activity -> text(root(activity), "Continue with Google").performClick());
            snapshot(scenario, "03-google-not-configured");
            scenario.onActivity(activity -> {
                text(root(activity), "Sign up").performClick();
                descendants(root(activity)).stream().filter(view -> view instanceof Button label && label.getText().toString().equals("Create account")).reduce((first, second) -> second).orElseThrow().performClick();
            });
            snapshot(scenario, "04-sign-up-validation");
            signedInFixture(); scenario.onActivity(this::inject); snapshot(scenario, "05-empty-chats");
            scenario.onActivity(activity -> invoke(activity, "addContactDialog")); snapshot(scenario, "06-add-contact");
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()); snapshot(scenario, "07-verification-required");
            scenario.onActivity(activity -> dialog(activity).dismiss());
            conversationsFixture(); scenario.onActivity(this::inject); snapshot(scenario, "08-chats");
            scenario.onActivity(activity -> ((EditText) readField(activity, "contactSearch")).setText("Maya")); snapshot(scenario, "09-contact-search");
            scenario.onActivity(activity -> { ((EditText) readField(activity, "contactSearch")).setText(""); setField(activity, "selectedPeer", peerId); invoke(activity, "render"); });
            snapshot(scenario, "10-conversation");
            scenario.onActivity(activity -> {
                EditText composer = (EditText) readField(activity, "composer"); composer.setText("Draft stays while receipts update");
                View original = composer; invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer);
                assertSame(original, readField(activity, "composer")); assertEquals("Draft stays while receipts update", composer.getText().toString());
                composer.setText(""); invoke(activity, "expiryDialog");
            });
            snapshot(scenario, "11-expiry-modes");
            scenario.onActivity(activity -> {
                AlertDialog choices = dialog(activity); choices.getListView().performItemClick(choices.getListView().getChildAt(2), 2, 2);
                assertEquals(ChatEnvelope.Expiry.HOURS_6, readField(activity, "expiry"));
                invoke(activity, "attachmentDialog");
            });
            snapshot(scenario, "12-attachments");
            scenario.onActivity(activity -> { dialog(activity).dismiss(); invoke(activity, "previewImage", new Class<?>[]{byte[].class}, photo.clone()); });
            snapshot(scenario, "13-photo-preview");
            scenario.onActivity(activity -> dialog(activity).dismiss());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> { assertNull(readField(activity, "displayedBitmap")); invoke(activity, "accountDialog"); });
            snapshot(scenario, "14-account");
            scenario.onActivity(activity -> { dialog(activity).dismiss(); invoke(activity, "viewContent", new Class<?>[]{ChatEngine.Entry.class}, once); });
                snapshot(scenario, "15a-view-once-confirmation");
                assertNotNull("A confirmation must not consume the message", vault.get("body/" + once.id()));
                scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertNotNull("Cancelling must preserve the message", vault.get("body/" + once.id()));
                scenario.onActivity(activity -> invoke(activity, "viewContent", new Class<?>[]{ChatEngine.Entry.class}, once));
                scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("This message is visible once.")), 5000);
            snapshot(scenario, "15-view-once");
            assertNull(vault.get("body/" + once.id()));
            scenario.onActivity(activity -> { dialog(activity).dismiss(); engine.online = false; ((TextView) readField(activity, "connection")).setText("Offline"); });
            snapshot(scenario, "16-offline");
            scenario.onActivity(activity -> { invoke(activity, "forgetDialog", new Class<?>[]{ChatEngine.Peer.class}, peer); });
            snapshot(scenario, "17-remove-contact");
            scenario.onActivity(activity -> { dialog(activity).dismiss(); invoke(activity, "legalNotices"); }); snapshot(scenario, "18-licenses");
            scenario.onActivity(activity -> { dialog(activity).dismiss(); ((EditText) readField(activity, "composer")).setText("must disappear on pause"); });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> { assertNull(readField(activity, "engine")); assertNull(readField(activity, "composer")); });
            scenario.moveToState(Lifecycle.State.RESUMED);
            var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("Send")), 10_000));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.text("Unlock Vanishr")));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.text("must disappear on pause")));
            snapshot(scenario, "19-background-return");
        }
    }

    @Test public void updatePromptDefersToOpenDialogsAndLaterIsRemembered() throws Exception {
        signedInFixture();
        AppUpdates.Release release = new AppUpdates.Release(BuildConfig.VERSION_CODE + 1, "0.2.7", AppUpdates.ORIGIN + "/vanishr-0.2.7.apk");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "accountDialog");
                AlertDialog profile = dialog(activity);
                text(profile.getWindow().getDecorView(), "Check for updates");
                setField(activity, "availableUpdate", release);
                invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, false);
                assertSame("An update must not replace an open account or content dialog", profile, dialog(activity));
                invoke(activity, "dismissContent");
                invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, false);
                assertNotSame(profile, dialog(activity));
                text(dialog(activity).getWindow().getDecorView(), "Update available");
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(release.versionCode(), context.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("dismissed-version", 0));
                setField(activity, "availableUpdate", release);
                invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, false);
                assertFalse(dialog(activity).isShowing());
                invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, true);
                assertTrue("A manual check may offer a previously dismissed update", dialog(activity).isShowing());
            });
            snapshot(scenario, "32-update-prompt");
        }
    }

    @Test public void anUnlockedPhoneOpensAndResumesWithoutAnAppLock() throws Exception {
        signedInFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Chats")), 10_000));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.text("Unlock Vanishr")));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.desc("Lock")));
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> assertNull(readField(activity, "engine")));
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Chats")), 10_000));
            scenario.onActivity(activity -> assertNotNull(readField(activity, "engine")));
            snapshot(scenario, "31-no-app-lock");
        }
    }
}