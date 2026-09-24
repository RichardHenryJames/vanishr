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
        assertTrue(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().clear().commit());
        new android.util.AtomicFile(new File(context.getNoBackupFilesDir(), "vault.bin")).delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        for (String alias : Collections.list(store.aliases())) if (alias.startsWith("vanishr.")) store.deleteEntry(alias);
    }

    private void createFixtureKey(String alias) throws Exception {
        createRawFixtureKey(AndroidVault.currentAlias(alias));
    }

    private void createRawFixtureKey(String alias) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        generator.generateKey();
    }

    private byte[] legacySeal(String alias, byte[] plaintext) throws Exception {
        return legacySeal(alias, plaintext, 1);
    }

    private byte[] legacySeal(String alias, byte[] plaintext, int version) throws Exception {
        String keyAlias = version == 1 ? alias : AndroidVault.phoneAlias(alias);
        createRawFixtureKey(keyAlias);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, store.getKey(keyAlias, null));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        return java.nio.ByteBuffer.allocate(13 + encrypted.length).put((byte) version).put(cipher.getIV()).put(encrypted).array();
    }

    private void legacyVaultFile() throws Exception {
        legacyVaultFile(1);
    }

    private void legacyVaultFile(int version) throws Exception {
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
        try { encrypted = legacySeal(AndroidVault.MASTER, plaintext, version); }
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
    private SecureSheet dialog(MainActivity activity) { return (SecureSheet) readField(activity, "openDialog"); }

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
            SecureSheet current = dialog(activity);
            View view = current != null && current.isShowing() ? current.getWindow().getDecorView() : activity.getWindow().getDecorView();
            if (current != null && current.isShowing()) assertTrue((current.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertTrue("The screen must be laid out", view.getWidth() > 0 && view.getHeight() > 0);
            Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            if (current != null && current.isShowing()) {
                activity.getWindow().getDecorView().draw(canvas);
                canvas.drawColor(Color.argb(69,24,39,36));
            }
            view.draw(canvas);
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

    @Test public void compactClassicHomeUsesOneToolbarAnd116DpBeforeTheList() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            snapshot(scenario, "50-compact-classic-home");
            scenario.onActivity(activity -> {
                View root = root(activity); View rows = (View) readField(activity, "contactRows");
                int[] origin = new int[2]; int[] list = new int[2]; root.getLocationOnScreen(origin); rows.getLocationOnScreen(list);
                float density = context.getResources().getDisplayMetrics().density;
                assertEquals("List must begin after A's toolbar and search row", Math.round(116 * density), list[1] - origin[1] - root.getPaddingTop(), 2);
                assertFalse(descendants(root).stream().anyMatch(view -> view instanceof TextView label && "Chats".contentEquals(label.getText())));
                assertTrue(descendants(root).stream().anyMatch(view -> "My profile".equals(view.getContentDescription())));
                assertTrue(descendants(root).stream().anyMatch(view -> "New conversation".equals(view.getContentDescription())));
                View filter = descendants(root).stream().filter(view -> "Unread chats".equals(view.getContentDescription())).findFirst().orElseThrow();
                filter.performClick();
                assertTrue(filter.isSelected());
                assertFalse(descendants((View) readField(activity, "contactRows")).stream().anyMatch(view -> view instanceof TextView label && "Maya Chen".contentEquals(label.getText())));
                EditText search = (EditText) readField(activity, "contactSearch"); search.setText("absent");
                assertNotNull(text(root, "No matching conversations"));
                descendants(root).stream().filter(view -> "Clear search".equals(view.getContentDescription())).findFirst().orElseThrow().performClick();
                assertEquals("", search.getText().toString());
                assertNotNull(text(root, peer.name()));
            });
        }
    }

    private void assertHorizontallyCentered(TextView message, View viewport) {
        Rect labelBounds = new Rect(); Rect viewportBounds = new Rect();
        assertTrue(message.getGlobalVisibleRect(labelBounds));
        assertTrue(viewport.getGlobalVisibleRect(viewportBounds));
        assertEquals("Empty-state label must be centered in its viewport", viewportBounds.exactCenterX(), labelBounds.exactCenterX(), 2);
        assertEquals(Gravity.CENTER_HORIZONTAL, message.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
        int[] position = new int[2]; message.getLocationOnScreen(position);
        android.text.Layout layout = message.getLayout(); assertNotNull(layout);
        for (int line = 0; line < layout.getLineCount(); line++) {
            float center = position[0] + message.getTotalPaddingLeft() - message.getScrollX()
                    + (layout.getLineLeft(line) + layout.getLineRight(line)) / 2;
            assertEquals("Each empty-state text line must be horizontally centered", viewportBounds.exactCenterX(), center, 2);
        }
    }

    @Test public void allEmptyHomeMessagesAndIconsAreHorizontallyCentered() throws Exception {
        signedInFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            List<String> messages = List.of("No conversations yet", "All caught up", "No matching conversations");
            for (int index = 0; index < messages.size(); index++) {
                String message = messages.get(index);
                snapshot(scenario, "55-empty-home-" + index);
                scenario.onActivity(activity -> {
                    View viewport = (View) readField(activity, "contactRows");
                    TextView label = (TextView) text(root(activity), message);
                    assertHorizontallyCentered(label, viewport);
                    View icon = ((ViewGroup) label.getParent()).getChildAt(0);
                    Rect iconBounds = new Rect(); Rect viewportBounds = new Rect();
                    assertTrue(icon.getGlobalVisibleRect(iconBounds)); assertTrue(viewport.getGlobalVisibleRect(viewportBounds));
                    assertEquals("Empty-state icon must be centered with its message", viewportBounds.exactCenterX(), iconBounds.exactCenterX(), 2);
                });
                if (index == 0) scenario.onActivity(activity -> ((View) readField(activity, "unreadFilter")).performClick());
                else if (index == 1) scenario.onActivity(activity -> ((EditText) readField(activity, "contactSearch")).setText("missing"));
            }
        }
    }

    @Test public void keyPolicyAcceptsAllSystemUnlockMethodsWithoutTimedAuthentication() {
        for (int androidVersion : List.of(28, 30, 31, 32, 33, 34, 35, 36)) {
            KeyGenParameterSpec policy = AndroidVault.keyPolicy("synthetic-policy", false, androidVersion);
            assertFalse(policy.isUserAuthenticationRequired());
            assertEquals(androidVersion >= 35, policy.isUnlockedDeviceRequired());
            assertEquals(256, policy.getKeySize());
            assertArrayEquals(new String[]{KeyProperties.BLOCK_MODE_GCM}, policy.getBlockModes());
            assertTrue(policy.isRandomizedEncryptionRequired());
        }
    }

    @Test public void freshVaultChecksKeyUsabilityBeforeAllowingSignIn() throws Exception {
        vault.close();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = AndroidVault.currentAlias(AndroidVault.MASTER);
        store.deleteEntry(alias);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeyValidityStart(new Date(System.currentTimeMillis() + 3_600_000)).build());
        generator.generateKey();
        try (AndroidVault fresh = new AndroidVault(context)) {
            assertThrows(KeyNotYetValidException.class, fresh::unlock);
            assertThrows(SecurityException.class, () -> fresh.get("account"));
            assertTrue("An unusable key must not be erased or replaced", store.containsAlias(alias));
            assertFalse("The fresh-install probe must not create a vault file", new File(context.getNoBackupFilesDir(), "vault.bin").exists());
        }
    }

    @Test public void freshKeystoreAuthenticationFailureDoesNotClaimAStorageMigration() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "hideContent");
                invoke(activity, "storageFailure", new Class<?>[]{Exception.class},
                        new android.security.keystore.UserNotAuthenticatedException());
                assertNotNull(text(root(activity), "Android could not open protected storage. Unlock your phone normally and try again. Your encrypted data has not been cleared."));
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                        && label.getText().toString().contains("storage update")));
            });
        }
    }

    @Test public void legacyKeyUnlockFailureExplainsOnlyTheOneTimeUpgrade() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "hideContent");
                invoke(activity, "storageFailure", new Class<?>[]{Exception.class}, new AndroidVault.MigrationUnlockRequiredException());
                assertNotNull(text(root(activity), "One-time secure storage upgrade: unlock your phone with its PIN, pattern or password, then reopen Vanishr. Later opens can use any phone unlock method. Your encrypted data has not been cleared."));
            });
        }
    }

    @Test public void startupMessagesAreHorizontallyCentered() throws Exception {
        signedInFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            for (String message : List.of("Opening your chats...", "Secure storage is unavailable. Your encrypted data has not been cleared.")) {
                scenario.onActivity(activity -> {
                    setField(activity, "storageError", message.startsWith("Opening") ? null : message);
                    invoke(activity, "storageState");
                });
                snapshot(scenario, message.startsWith("Opening") ? "56-centered-opening" : "56-centered-storage-error");
                scenario.onActivity(activity -> assertHorizontallyCentered((TextView) text(root(activity), message), root(activity)));
            }
        }
    }

    @Test public void compactClassicProfilesOpenAsSecureBottomSheetsAndKeepTheChatDraft() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.applyProfile(new ChatEngine.Profile(userId,"alex","Alex Rivera"));
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); setField(activity,"selectedPeer",peerId); invoke(activity,"render");
                ((EditText)readField(activity,"composer")).setText("Keep my unsent message");
                invoke(activity,"contactProfileDialog",new Class<?>[]{ChatEngine.Peer.class},peer);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            snapshot(scenario,"51-compact-contact-profile");
            scenario.onActivity(activity -> {
                SecureSheet sheet=dialog(activity);
                View panel=sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                assertNotNull(panel); assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
                assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,sheet.getBehavior().getState());
                int[] position=new int[2]; panel.getLocationInWindow(position);
                View container=(View)panel.getParent();
                int[] containerPosition=new int[2]; container.getLocationInWindow(containerPosition);
                int panelBottom=position[1]+panel.getHeight(), containerBottom=containerPosition[1]+container.getHeight();
                assertTrue("The profile must be bottom anchored: panel="+panelBottom+", container="+containerBottom,
                    Math.abs(panelBottom-containerBottom)<=4);
                var cancel=(com.google.android.material.button.MaterialButton)sheet.getButton(AlertDialog.BUTTON_NEGATIVE);
                assertEquals(Ui.SURFACE,cancel.getBackgroundTintList().getDefaultColor());
                cancel.clearFocus(); cancel.setPressed(false); cancel.jumpDrawablesToCurrentState();
                View decor=sheet.getWindow().getDecorView();
                int[] buttonPosition=new int[2]; cancel.getLocationInWindow(buttonPosition);
                Bitmap sheetImage=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
                try {
                    decor.draw(new Canvas(sheetImage));
                    assertEquals("Secondary sheet buttons must render white",Ui.SURFACE,
                            sheetImage.getPixel(buttonPosition[0]+cancel.getWidth()/2,buttonPosition[1]+new Ui(activity).dp(8)));
                } finally { sheetImage.recycle(); }
                assertNotNull(text(decor,"Use profile name")); assertNotNull(text(decor,"Cancel")); assertNotNull(text(decor,"Save"));
                assertEquals(1,descendants(decor).stream().filter(view -> view instanceof EditText).count());
                assertFalse(descendants(decor).stream().anyMatch(view -> "Save username".equals(view.getContentDescription())));
                descendants(decor).stream().filter(view -> view instanceof EditText).map(view -> (EditText)view).findFirst().orElseThrow().setText("Local friend");
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            var device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.text("Contact profile")),10000));
            scenario.onActivity(activity -> {
                assertEquals("Keep my unsent message",((EditText)readField(activity,"composer")).getText().toString());
                assertNotNull(text(root(activity),"Local friend"));
                invoke(activity,"attachmentDialog");
            });
            snapshot(scenario,"52-compact-attachments");
            scenario.onActivity(activity -> {
                assertNotNull(text(dialog(activity).getWindow().getDecorView(),"Choose image"));
                assertNotNull(text(dialog(activity).getWindow().getDecorView(),"Take photo"));
                invoke(activity,"dismissContent"); invoke(activity,"accountDialog");
            });
            snapshot(scenario,"53-compact-my-profile");
            scenario.onActivity(activity -> {
                View decor=dialog(activity).getWindow().getDecorView();
                for (String action : List.of("Share username","Verify identity","Check for updates","Licenses & source","Sign out")) assertNotNull(text(decor,action));
                assertTrue(descendants(decor).stream().anyMatch(view -> "Save name".equals(view.getContentDescription())));
                assertTrue(descendants(decor).stream().anyMatch(view -> "Save username".equals(view.getContentDescription())));
                assertNotNull(dialog(activity).findViewById(com.google.android.material.R.id.design_bottom_sheet));
            });
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    public void compactClassicProfileFieldAndSaveRemainReachableWithKeyboard() throws Exception {
        signedInFixture();
        var keyboard=new java.util.concurrent.CountDownLatch(1);
        ViewTreeObserver.OnGlobalLayoutListener[] listener=new ViewTreeObserver.OnGlobalLayoutListener[1];
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity,"accountDialog"); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View decor=dialog(activity).getWindow().getDecorView();
                EditText username=descendants(decor).stream().filter(view -> view instanceof EditText field && "Username".contentEquals(field.getHint()))
                        .map(view -> (EditText)view).findFirst().orElseThrow();
                username.setText("keyboard_safe_draft"); username.requestFocus();
                listener[0]=() -> { WindowInsets insets=decor.getRootWindowInsets(); if (insets!=null && insets.isVisible(WindowInsets.Type.ime())) keyboard.countDown(); };
                decor.getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
                username.post(() -> androidx.core.view.WindowCompat.getInsetsController(dialog(activity).getWindow(),username).show(androidx.core.view.WindowInsetsCompat.Type.ime()));
            });
            assertTrue("Profile keyboard must open",keyboard.await(10,java.util.concurrent.TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View decor=dialog(activity).getWindow().getDecorView(); decor.getViewTreeObserver().removeOnGlobalLayoutListener(listener[0]);
                View save=descendants(decor).stream().filter(view -> "Save username".equals(view.getContentDescription())).findFirst().orElseThrow();
                save.requestRectangleOnScreen(new Rect(0,0,save.getWidth(),save.getHeight()),true);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            snapshot(scenario,"54-compact-profile-keyboard");
            scenario.onActivity(activity -> {
                SecureSheet sheet=dialog(activity); View decor=sheet.getWindow().getDecorView();
                View save=descendants(decor).stream().filter(view -> "Save username".equals(view.getContentDescription())).findFirst().orElseThrow();
                Rect target=new Rect(); Rect visible=new Rect(); assertTrue(save.getGlobalVisibleRect(target)); decor.getWindowVisibleDisplayFrame(visible);
                assertTrue("Inline profile save must remain above the keyboard",target.bottom<=visible.bottom+4);
                assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
                assertEquals("alex",engine.account().handle());
                activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(save.getWindowToken(),0);
            });
        }
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
            boolean failedVisible = device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Not sent")), 10_000);
            if (!failedVisible) snapshot(scenario, "65-send-retry-state");
            assertTrue("Failure state must be visible; pending.failed=" + readField(pending[0], "failed"), failedVisible);
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

    private Object syntheticGoogleAttempt() throws Exception {
        Class<?> type = Class.forName(MainActivity.class.getName() + "$GoogleAttempt");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, boolean.class, GoogleSignIn.Challenge.class);
        constructor.setAccessible(true);
        return constructor.newInstance("https://127.0.0.1:1/", false, new GoogleSignIn.Challenge("a".repeat(43),
                "b".repeat(43), BuildConfig.GOOGLE_WEB_CLIENT_ID, System.currentTimeMillis() + 300_000));
    }

    private void assertGoogleProgress(MainActivity activity) {
        assertNotNull(text(root(activity), "Signing in with Google..."));
        assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                && "Continue with Google".contentEquals(label.getText())));
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    @Test public void googlePickerReturnKeepsProgressAndCancellationRestoresLogin() throws Exception {
        Object attempt = syntheticGoogleAttempt();
        var loginDrawn = new java.util.concurrent.atomic.AtomicBoolean();
        ViewTreeObserver.OnPreDrawListener[] listener = new ViewTreeObserver.OnPreDrawListener[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); setField(activity, "googleAttempt", attempt); invoke(activity, "render");
                assertGoogleProgress(activity);
                listener[0] = () -> {
                    if (descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                            && "Continue with Google".contentEquals(label.getText()))) loginDrawn.set(true);
                    return true;
                };
                root(activity).getViewTreeObserver().addOnPreDrawListener(listener[0]);
                setField(activity, "availableUpdate", new AppUpdates.Release(BuildConfig.VERSION_CODE + 1,
                        "0.3.3", AppUpdates.ORIGIN + "/vanishr-0.3.3.apk"));
                invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, false);
                assertNull("An update prompt must not interrupt Google sign-in", dialog(activity));
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            java.util.concurrent.Future<?>[] barrier = new java.util.concurrent.Future<?>[1];
            scenario.onActivity(activity -> barrier[0] = ((java.util.concurrent.ExecutorService) readField(activity, "work")).submit(() -> { }));
            barrier[0].get(10, java.util.concurrent.TimeUnit.SECONDS);
            snapshot(scenario, "58-google-picker-progress");
            scenario.onActivity(activity -> {
                assertNotNull(readField(activity, "engine")); assertGoogleProgress(activity);
                assertFalse("Login must not be drawn while the provider result is outstanding", loginDrawn.get());
                root(activity).getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                setField(activity, "availableUpdate", null);
                invoke(activity, "googleFailed", new Class<?>[]{GoogleSignIn.Failure.class}, GoogleSignIn.Failure.CANCELLED);
                assertNull(readField(activity, "googleAttempt"));
                assertFalse((boolean) readField(activity, "completingGoogle"));
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
                assertNotNull(text(root(activity), GoogleSignIn.Failure.CANCELLED.message));
            });
        }
    }

    @Test public void googleExchangeKeepsProgressAndNetworkFailureRestoresLogin() throws Exception {
        Object attempt = syntheticGoogleAttempt(); setField(attempt, "idToken", "synthetic-google-token");
        var workerStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseWorker = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    inject(activity);
                    ((java.util.concurrent.ExecutorService) readField(activity, "work")).execute(() -> {
                        workerStarted.countDown();
                        try { releaseWorker.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    });
                });
                assertTrue(workerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    setField(activity, "googleAttempt", attempt); invoke(activity, "completeGoogle");
                    assertTrue((boolean) readField(activity, "busy"));
                    assertTrue((boolean) readField(activity, "completingGoogle"));
                    invoke(activity, "render"); assertGoogleProgress(activity);
                });
                snapshot(scenario, "59-google-exchange-progress");
                releaseWorker.countDown();
                var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(
                        "Cannot connect right now. Check your connection and try again.")), 10_000));
                scenario.onActivity(activity -> {
                    assertFalse((boolean) readField(activity, "completingGoogle"));
                    assertFalse((boolean) readField(activity, "busy"));
                    assertNull(readField(attempt, "idToken"));
                    assertTrue(text(root(activity), "Continue with Google").isEnabled());
                    assertNull(engine.account());
                });
            } finally { releaseWorker.countDown(); }
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

    @Test public void rejectedSessionReturnsToGoogleSignInWithoutSigningOut() throws Exception {
        signedInFixture(); conversationsFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        byte[] body = vault.get("body/" + once.id());
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "showFailure", new Class<?>[]{Exception.class}, new RelayApi.ApiFailure(401));
            });
            assertTrue("A rejected session must immediately offer a fresh Google sign-in",
                device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Continue with Google")), 5000));
            scenario.onActivity(activity -> {
                assertFalse(engine.authenticated());
                assertTrue(engine.usesGoogle());
                assertEquals(userId, engine.account().userId());
                assertEquals(deviceId, engine.account().deviceId());
                assertEquals("", engine.account().accessToken());
                assertEquals(identity, engine.identityCode());
                assertArrayEquals(body, vault.get("body/" + once.id()));
                assertFalse(engine.peers().isEmpty());
                assertEquals(false, readField(activity, "busy"));
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
                assertFalse("Reauthentication must not require the sign-out dialog", dialog(activity) != null && dialog(activity).isShowing());
            });
        } finally { Arrays.fill(body, (byte) 0); }
    }

    @Test public void expiredGoogleAccountReopensWithoutReusingItsBearerOrChangingKeys() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        byte[] body = vault.get("body/" + once.id());
        vault.transaction(() -> {
            vault.put("google-account", new byte[]{1});
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "alex", userId, deviceId, "expired-session", System.currentTimeMillis() - 1000, true));
            return null;
        });
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertFalse(engine.authenticated());
        assertTrue(engine.usesGoogle());
        assertEquals("", engine.account().accessToken());
        assertNull(readField(readField(engine, "api"), "token"));
        assertEquals(deviceId, engine.activeDeviceId());
        assertEquals(identity, engine.identityCode());
        assertArrayEquals(body, vault.get("body/" + once.id()));
        Arrays.fill(body, (byte) 0);
    }

    private void rememberFixture(boolean google, boolean expiredAccess) throws Exception {
        vault.transaction(() -> {
            if (google) vault.put("google-account", new byte[]{1}); else vault.remove("google-account");
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "alex", userId, deviceId, "a".repeat(43),
                    System.currentTimeMillis() + (expiredAccess ? -1000 : 3_600_000), true, "b".repeat(43), System.currentTimeMillis() + 2_592_000_000L));
                write("session-renewal", new ChatEngine.Renewal("b".repeat(43), "d".repeat(43), System.currentTimeMillis() + 2_592_000_000L));
            return null;
        });
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
    }

    private void sessionTransport(okhttp3.Interceptor interceptor) {
        RelayApi api = engine.groupApi();
        okhttp3.OkHttpClient original = (okhttp3.OkHttpClient) readField(api, "client");
        setField(api, "client", original.newBuilder().addInterceptor(interceptor).build());
    }

    private ChatEngine.Token renewedToken() {
        return new ChatEngine.Token(userId, deviceId, "c".repeat(43), System.currentTimeMillis() + 3_600_000,
                "d".repeat(43), System.currentTimeMillis() + 2_592_000_000L);
    }

    @Test public void rememberedGoogleAndPasswordAccountsReopenAndRenewWithoutSigningIn() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        for (boolean google : new boolean[]{false, true}) {
            rememberFixture(google, true);
            assertTrue(engine.authenticated()); assertEquals(google, engine.usesGoogle());
            java.util.concurrent.atomic.AtomicInteger renewals = new java.util.concurrent.atomic.AtomicInteger();
            sessionTransport(chain -> {
                if (chain.request().url().encodedPath().equals("/auth/refresh")) {
                    assertNull(chain.request().header("Authorization"));
                    okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer);
                    ChatEngine.Refresh request = RelayApi.JSON.fromJson(buffer.readUtf8(), ChatEngine.Refresh.class);
                    assertEquals("b".repeat(43), request.refreshToken()); assertEquals("d".repeat(43), request.nextRefreshToken()); renewals.incrementAndGet();
                    return syntheticResponse(chain.request(), 200, renewedToken());
                }
                assertEquals("/auth/me", chain.request().url().encodedPath());
                assertEquals("Bearer " + "c".repeat(43), chain.request().header("Authorization"));
                return syntheticResponse(chain.request(), 200, Map.of("userId", userId, "deviceId", deviceId));
            });
            engine.groupApi().call("GET", "/auth/me", null, Map.class);
            engine.groupApi().call("GET", "/auth/me", null, Map.class);
            assertEquals(1, renewals.get());
            engine.applyProfile(new ChatEngine.Profile(userId, "alex", "Alex"));
            engine.close(); vault.unlock(); engine = new ChatEngine(vault);
            assertTrue(engine.authenticated()); assertEquals(google, engine.usesGoogle());
            assertEquals("d".repeat(43), engine.account().refreshToken());
            assertEquals(identity, engine.identityCode());
            assertNotNull(vault.get("body/" + once.id()));
        }
    }

    @Test public void reopeningRememberedGoogleAndPasswordAccountsGoesStraightToChats() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (boolean google : new boolean[]{false, true}) {
            rememberFixture(google, true);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("My profile")), 15_000));
                scenario.onActivity(activity -> {
                    ChatEngine current = (ChatEngine) readField(activity, "engine");
                    assertTrue(current.authenticated()); assertEquals(google, current.usesGoogle());
                    assertEquals(identity, current.identityCode());
                    assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                            && ("Continue with Google".contentEquals(label.getText()) || "Welcome back.".contentEquals(label.getText()))));
                });
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.onActivity(activity -> assertNull(readField(activity, "engine")));
                scenario.moveToState(Lifecycle.State.RESUMED);
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("My profile")), 15_000));
                snapshot(scenario, google ? "65-remembered-google-reopen" : "64-remembered-password-reopen");
                scenario.onActivity(activity -> {
                    ChatEngine current = (ChatEngine) readField(activity, "engine");
                    assertTrue(current.authenticated()); assertEquals(identity, current.identityCode());
                    assertNotNull(readField(activity, "contactRows"));
                });
            }
            engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        }
    }

    @Test public void rememberedSessionSurvivesNetworkFailureButRejectsRevokedRenewal() throws Exception {
        signedInFixture(); conversationsFixture(); rememberFixture(true, true);
        String identity = engine.identityCode();
        sessionTransport(chain -> { throw new IOException("Synthetic offline transport"); });
        assertThrows(IOException.class, () -> engine.groupApi().call("GET", "/auth/me", null, Map.class));
        assertTrue(engine.authenticated()); assertEquals("b".repeat(43), engine.account().refreshToken());
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertTrue(engine.authenticated());
        sessionTransport(chain -> {
            assertEquals("/auth/refresh", chain.request().url().encodedPath());
            return syntheticResponse(chain.request(), 401, Map.of("error", "authentication_required"));
        });
        assertThrows(RelayApi.ApiFailure.class, () -> engine.groupApi().call("GET", "/auth/me", null, Map.class));
        assertFalse(engine.authenticated()); assertNull(engine.account().refreshToken());
        assertEquals("", engine.account().accessToken()); assertTrue(engine.usesGoogle());
        assertEquals(identity, engine.identityCode()); assertNotNull(vault.get("body/" + once.id()));
    }

    @Test public void interruptedRenewalRecoversAfterReopenWithoutGoogleOrPassword() throws Exception {
        signedInFixture(); conversationsFixture(); rememberFixture(true, true);
        String identity = engine.identityCode();
        sessionTransport(chain -> {
            assertEquals("/auth/refresh", chain.request().url().encodedPath());
            okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer);
            ChatEngine.Refresh request = RelayApi.JSON.fromJson(buffer.readUtf8(), ChatEngine.Refresh.class);
            assertEquals("b".repeat(43), request.refreshToken()); assertEquals("d".repeat(43), request.nextRefreshToken());
            throw new IOException("Synthetic lost renewal response after rotation");
        });
        assertThrows(IOException.class, () -> engine.groupApi().call("GET", "/auth/me", null, Map.class));
        assertNotNull(vault.get("session-renewal"));
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        sessionTransport(chain -> {
            if (!chain.request().url().encodedPath().equals("/auth/refresh")) {
                assertEquals("/auth/me", chain.request().url().encodedPath());
                assertEquals("Bearer " + "c".repeat(43), chain.request().header("Authorization"));
                return syntheticResponse(chain.request(), 200, Map.of("userId", userId));
            }
            assertNull(chain.request().header("Authorization"));
            okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer);
            ChatEngine.Refresh request = RelayApi.JSON.fromJson(buffer.readUtf8(), ChatEngine.Refresh.class);
            if (attempts.incrementAndGet() == 1) {
                assertEquals("b".repeat(43), request.refreshToken());
                return syntheticResponse(chain.request(), 401, Map.of("error", "authentication_required"));
            }
            assertEquals("d".repeat(43), request.refreshToken()); assertNotEquals(request.refreshToken(), request.nextRefreshToken());
            return syntheticResponse(chain.request(), 200, new ChatEngine.Token(userId, deviceId, "c".repeat(43), System.currentTimeMillis() + 3_600_000,
                    request.nextRefreshToken(), System.currentTimeMillis() + 2_592_000_000L));
        });
        engine.groupApi().call("GET", "/auth/me", null, Map.class);
        assertEquals(2, attempts.get()); assertTrue(engine.authenticated()); assertTrue(engine.usesGoogle());
        assertEquals(identity, engine.identityCode()); assertNull(vault.get("session-renewal"));
        assertNotNull(vault.get("body/" + once.id()));
    }

    @Test public void rejectedAccessTokenRenewsOnceWithoutReplayingAnAuthorizedRequest() throws Exception {
        signedInFixture(); rememberFixture(false, false);
        java.util.concurrent.atomic.AtomicInteger attempted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger renewals = new java.util.concurrent.atomic.AtomicInteger();
        sessionTransport(chain -> {
            if (chain.request().url().encodedPath().equals("/auth/refresh")) {
                assertNull(chain.request().header("Authorization")); renewals.incrementAndGet();
                return syntheticResponse(chain.request(), 200, renewedToken());
            }
            assertEquals("/account/profile", chain.request().url().encodedPath());
            if (attempted.incrementAndGet() == 1) return syntheticResponse(chain.request(), 401, Map.of("error", "authentication_required"));
            assertEquals("Bearer " + "c".repeat(43), chain.request().header("Authorization"));
            return syntheticResponse(chain.request(), 200, new ChatEngine.Profile(userId, "alex", "Alex"));
        });
        engine.renameProfile("Alex");
        assertEquals(2, attempted.get()); assertEquals(1, renewals.get());
        assertTrue(engine.authenticated()); assertEquals("d".repeat(43), engine.account().refreshToken());
    }

    @Test public void rememberedSessionCannotRestoreAnotherIdentityAndSignOutDropsRenewal() throws Exception {
        signedInFixture(); conversationsFixture(); rememberFixture(false, true);
        String identity = engine.identityCode();
        sessionTransport(chain -> syntheticResponse(chain.request(), 200, new ChatEngine.Token(UUID.randomUUID(), deviceId,
                "c".repeat(43), System.currentTimeMillis() + 3_600_000, "d".repeat(43), System.currentTimeMillis() + 2_592_000_000L)));
        assertThrows(SecurityException.class, () -> engine.groupApi().call("GET", "/auth/me", null, Map.class));
        assertFalse(engine.authenticated()); assertEquals(identity, engine.identityCode());
        rememberFixture(false, true);
        vault.transaction(() -> {
            ChatEngine.Account current = engine.account();
            write("account", new ChatEngine.Account(current.origin(), current.handle(), current.userId(), current.deviceId(), "", 0, true,
                current.refreshToken(), current.refreshExpiresAt()));
            return null;
        });
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        java.util.concurrent.atomic.AtomicBoolean revoked = new java.util.concurrent.atomic.AtomicBoolean();
        sessionTransport(chain -> {
            if (chain.request().url().encodedPath().equals("/auth/refresh")) return syntheticResponse(chain.request(), 200, renewedToken());
            assertEquals("/auth/logout", chain.request().url().encodedPath());
            assertEquals("Bearer " + "c".repeat(43), chain.request().header("Authorization")); revoked.set(true);
            return syntheticResponse(chain.request(), 204, null);
        });
        engine.logout(); vault.unlock(); engine = new ChatEngine(vault);
        assertTrue(revoked.get()); assertNull(engine.account());
        String prefix = AndroidVault.savedAccountPrefix(userId);
        ChatEngine.Account retained = RelayApi.JSON.fromJson(new String(vault.get(prefix + "account"), StandardCharsets.UTF_8), ChatEngine.Account.class);
        assertEquals("", retained.accessToken()); assertNull(retained.refreshToken()); assertEquals(0, retained.refreshExpiresAt());
        assertNotNull(vault.get(prefix + "body/" + once.id()));
    }

    @Test public void profilePhotoIsSmallSquareAndContainsNoSourceMetadata() throws Exception {
        byte[] encoded = SafeImages.profilePhoto(photo);
        try {
            assertTrue(encoded.length <= ProfileEnvelope.MAX_PHOTO_BYTES);
            Bitmap preview = SafeImages.displayProfilePhoto(encoded);
            try { assertEquals(256, preview.getWidth()); assertEquals(256, preview.getHeight()); }
            finally { preview.recycle(); }
            var metadata = new androidx.exifinterface.media.ExifInterface(new ByteArrayInputStream(encoded));
            assertNull(metadata.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE));
            assertNull(metadata.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL));
            assertThrows(IOException.class, () -> SafeImages.displayProfilePhoto(photo));
            assertThrows(IOException.class, () -> SafeImages.displayProfilePhoto(new byte[ProfileEnvelope.MAX_PHOTO_BYTES + 1]));
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    private ProfilePhotos.Packet profilePacket(SignalClient remote, ProfileEnvelope.Action action, UUID requestId,
                                               long revision, byte[] image, long deadline) throws Exception {
        UUID id = action == ProfileEnvelope.Action.REQUEST ? requestId : UUID.randomUUID();
        ChatEnvelope context = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId,
                System.currentTimeMillis(), deadline, ChatEnvelope.Expiry.HOURS_24, "profile-photo", null);
        byte[] plaintext = RelayApi.JSON.toJson(new ProfileEnvelope(1, action, requestId, revision, image, context)).getBytes(StandardCharsets.UTF_8);
        try {
            SignalClient.Packet encrypted = remote.encrypt(userId, plaintext, Instant.now());
            return new ProfilePhotos.Packet(id, peerId, peerDevice, userId, deviceId, deadline, encrypted.type(), encrypted.ciphertext());
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private void profileTransport(SignalClient remote, List<ProfilePhotos.Packet> incoming, List<ProfilePhotos.Send> outgoing) {
        ((RelayApi) readField(engine, "api")).close();
        setField(engine, "api", syntheticApi(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.equals("/profile/packets") && chain.request().method().equals("GET")) {
                List<ProfilePhotos.Packet> batch = new ArrayList<>(incoming); incoming.clear();
                return syntheticResponse(chain.request(), 200, batch);
            }
            if (path.equals("/profile/packets")) {
                okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer);
                outgoing.add(RelayApi.JSON.fromJson(buffer.readUtf8(), ProfilePhotos.Send.class));
                return syntheticResponse(chain.request(), 200, null);
            }
            if (path.startsWith("/profile/packets/")) return syntheticResponse(chain.request(), 200, null);
            if (path.equals("/users/id/" + peerId)) return syntheticResponse(chain.request(), 200,
                    new ChatEngine.Contact(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity())));
            if (path.equals("/keys/" + peerId + "/claim")) {
                try { return syntheticResponse(chain.request(), 200, remote.generatePreKey(Instant.now())); }
                catch (Exception failure) { throw new IOException("Synthetic profile key generation failed"); }
            }
            throw new AssertionError("Unexpected profile transport path");
        }));
    }

    private ProfileEnvelope decryptProfile(SignalClient remote, ProfilePhotos.Send packet) throws Exception {
        byte[] plaintext = remote.decrypt(userId, new SignalClient.Packet(packet.type(), packet.ciphertext()));
        try { return RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ProfileEnvelope.class); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private void saveProfilePeer(SignalClient remote) throws Exception {
        peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "Friend", "friend", null);
        vault.transaction(() -> { engine.groupSignal().verifyPeer(peerId, remote.publicIdentity()); write("contact/" + peerId, peer); return null; });
    }

    @Test public void profilePhotoRequiresBothVerifiedContactsAndSupportsRemoval() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity());
        remote.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            engine.photos().update(image);
            UUID untrustedRequest = UUID.randomUUID();
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.REQUEST, untrustedRequest, 0, null, System.currentTimeMillis() + 60_000));
            engine.photos().sync();
            assertTrue("An unknown requester must never receive a photo", outgoing.isEmpty());
            assertTrue(vault.names("profile-photo-grant/").isEmpty());
            saveProfilePeer(remote);
            engine.photos().sync();
            assertEquals(1, outgoing.size());
            ProfileEnvelope request = decryptProfile(remote, outgoing.remove(0));
            assertEquals(ProfileEnvelope.Action.REQUEST, request.action()); assertNull(request.photo());
            UUID mutualRequest = UUID.randomUUID();
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.REQUEST, mutualRequest, 0, null, System.currentTimeMillis() + 60_000));
            engine.photos().sync();
            ProfileEnvelope shared = null;
            for (ProfilePhotos.Send packet : outgoing) {
                ProfileEnvelope received = decryptProfile(remote, packet);
                if (received.action() == ProfileEnvelope.Action.UPDATE) shared = received;
            }
            assertNotNull("A mutual verified request must receive the photo", shared);
            assertEquals(mutualRequest, shared.requestId()); assertArrayEquals(image, shared.photo());
            assertTrue(engine.entries(null).isEmpty());
            outgoing.clear();
            engine.photos().update(null); engine.photos().sync();
            assertEquals(1, outgoing.size());
            ProfileEnvelope removed = decryptProfile(remote, outgoing.remove(0));
            assertEquals(ProfileEnvelope.Action.UPDATE, removed.action()); assertNull(removed.photo());
            assertTrue(removed.revision() > shared.revision());
            engine.forget(peer); engine.photos().sync();
            assertEquals(ProfileEnvelope.Action.REVOKE, decryptProfile(remote, outgoing.remove(0)).action());
            assertTrue(engine.peers().isEmpty()); assertTrue(vault.names("profile-photo-grant/").isEmpty());
            assertTrue(vault.names("profile-photo-out/").isEmpty());
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void receivedProfilePhotoRequiresCurrentRequestAndExpiresWithoutChatHistory() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity());
        saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        engine.photos().sync();
        ProfileEnvelope request = decryptProfile(remote, outgoing.remove(0));
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            engine.photos().receive(profilePacket(remote, ProfileEnvelope.Action.UPDATE, UUID.randomUUID(), 1, image, System.currentTimeMillis() + 60_000));
            assertNull("An unsolicited update cannot become a visible photo", engine.photos().contact(peerId));
            long deadline = System.currentTimeMillis() + 60_000;
            ProfilePhotos.Packet update = profilePacket(remote, ProfileEnvelope.Action.UPDATE, request.requestId(), 2, image, deadline);
            engine.photos().receive(update);
            assertArrayEquals(image, engine.photos().contact(peerId)); assertEquals(deadline, engine.photos().expiresAt(peerId));
            engine.photos().receive(update);
            assertTrue("Photo delivery must not create a chat entry", engine.entries(null).isEmpty());
            assertTrue(vault.names("body/").isEmpty()); assertTrue(vault.names("outbox/").isEmpty());
            vault.transaction(() -> { engine.photos().purge("", deadline + 1); return null; });
            assertNull(engine.photos().contact(peerId));
            engine.forget(peer);
            assertNull(engine.photos().contact(peerId)); assertTrue(vault.names("profile-photo-request/").isEmpty());
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void crossedMutualPhotoRequestsAcceptTheFirstAuthorizedReply() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity()); saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        engine.photos().sync();
        ProfileEnvelope pending = decryptProfile(remote, outgoing.remove(0));
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.REQUEST, UUID.randomUUID(), 0, null, deadline));
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.UPDATE, pending.requestId(), 1, image, deadline));
            engine.photos().sync();
            assertArrayEquals("A crossing mutual request must not invalidate the photo reply already in flight", image, engine.photos().contact(peerId));
            assertTrue(engine.entries(null).isEmpty());
            assertEquals(deadline, engine.photos().expiresAt(peerId));
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void crossedMutualPhotoRequestKeepsThePendingReplyAcrossSyncs() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity()); saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        engine.photos().sync();
        ProfileEnvelope pending = decryptProfile(remote, outgoing.remove(0));
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.REQUEST, UUID.randomUUID(), 0, null, deadline));
            engine.photos().sync();
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.UPDATE, pending.requestId(), 1, image, deadline));
            engine.photos().sync();
            assertArrayEquals("A mutual request must not discard the authorized photo reply arriving on the next sync", image, engine.photos().contact(peerId));
            assertEquals(deadline, engine.photos().expiresAt(peerId));
            assertTrue(engine.entries(null).isEmpty());
            for (ProfilePhotos.Send packet : outgoing)
                assertNotEquals("A fresh pending request must not be replaced before its reply arrives", ProfileEnvelope.Action.REQUEST, decryptProfile(remote, packet).action());
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void missingMutualPhotoReplyRetriesWithoutWaitingTwelveHours() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity()); saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing); engine.photos().sync();
        ProfileEnvelope original = decryptProfile(remote, outgoing.remove(0));
        long now = System.currentTimeMillis();
        vault.transaction(() -> {
            write("profile-photo-request/" + peerId, new ProfilePhotos.Request(original.requestId(), peerDevice, peer.identityKey(), now + 86_280_000, now + 43_080_000));
            write("profile-photo-grant/" + peerId, new ProfilePhotos.Grant(UUID.randomUUID(), peerDevice, peer.identityKey(), now + 60_000, 1));
            return null;
        });
        engine.photos().sync();
        assertEquals("An unanswered mutual request from the previous release must be retried promptly", 1, outgoing.size());
        ProfileEnvelope retried = decryptProfile(remote, outgoing.remove(0));
        assertEquals(ProfileEnvelope.Action.REQUEST, retried.action()); assertNotEquals(original.requestId(), retried.requestId());
        engine.photos().sync(); assertTrue("A fresh request must not be repeated on every poll", outgoing.isEmpty());
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            incoming.add(profilePacket(remote, ProfileEnvelope.Action.UPDATE, retried.requestId(), 1, image, System.currentTimeMillis() + 60_000));
            engine.photos().sync(); assertArrayEquals(image, engine.photos().contact(peerId));
            engine.photos().sync(); assertTrue(outgoing.isEmpty());
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void failedProfileUploadRetriesIdenticalCiphertextAndOriginalDeadline() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity()); saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        RelayApi api = (RelayApi) readField(engine, "api");
        okhttp3.OkHttpClient original = (okhttp3.OkHttpClient) readField(api, "client");
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        List<byte[]> sent = new ArrayList<>();
        var transport = original.newBuilder();
        transport.interceptors().add(0, chain -> {
            if (chain.request().method().equals("POST") && chain.request().url().encodedPath().equals("/profile/packets")) {
                okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer); sent.add(buffer.readByteArray());
                if (attempts.incrementAndGet() == 1) throw new IOException("Synthetic offline profile upload");
            }
            return chain.proceed(chain.request());
        });
        setField(api, "client", transport.build());
        assertThrows(IOException.class, () -> engine.photos().sync());
        assertEquals(1, vault.names("profile-photo-out/").size());
        engine.photos().sync();
        assertEquals(2, sent.size()); assertArrayEquals(sent.get(0), sent.get(1));
        assertTrue(vault.names("profile-photo-out/").isEmpty());
        assertEquals(ProfileEnvelope.Action.REQUEST, decryptProfile(remote, outgoing.get(0)).action());
    }

    @Test public void detectedProfileIdentityChangeClearsCachedPhotoAndQueuedUpdates() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity()); saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing); engine.photos().sync();
        ProfileEnvelope request = decryptProfile(remote, outgoing.remove(0));
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            engine.photos().receive(profilePacket(remote, ProfileEnvelope.Action.UPDATE, request.requestId(), 1, image, System.currentTimeMillis() + 60_000));
            assertNotNull(engine.photos().contact(peerId));
            RelayApi api = (RelayApi) readField(engine, "api");
            var transport = ((okhttp3.OkHttpClient) readField(api, "client")).newBuilder();
            transport.interceptors().add(0, chain -> chain.request().url().encodedPath().equals("/users/id/" + peerId)
                    ? syntheticResponse(chain.request(), 200, new ChatEngine.Contact(peerId, UUID.randomUUID(), peer.identityKey()))
                    : chain.proceed(chain.request()));
            setField(api, "client", transport.build());
            ProfilePhotos.Packet changed = profilePacket(remote, ProfileEnvelope.Action.UPDATE, request.requestId(), 2, image, System.currentTimeMillis() + 60_000);
            assertThrows(SecurityException.class, () -> engine.photos().receive(changed));
            assertNull(engine.photos().contact(peerId)); assertTrue(vault.names("profile-photo-out/").isEmpty());
            assertTrue(vault.names("profile-photo-request/").isEmpty());
            assertTrue("The independent contact pin must not be silently replaced", engine.groupSignal().isVerified(peerId));
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void fullProfileOutboxDrainsBeforeSchedulingAnotherRequest() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault()); saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        vault.transaction(() -> {
            for (int index = 0; index < 64; index++) {
                UUID id = UUID.randomUUID();
                ProfilePhotos.Send packet = new ProfilePhotos.Send(id, peerId, peerDevice, System.currentTimeMillis() + 60_000, 3, new byte[32]);
                write("profile-photo-out/" + id, new ProfilePhotos.Queued(packet, ProfileEnvelope.Action.REQUEST, id, peer.identityKey(), 0));
            }
            return null;
        });
        engine.photos().sync();
        assertTrue("A full outbox must still send existing packets", outgoing.size() >= 4);
        assertTrue(vault.names("profile-photo-out/").size() < 64);
        assertNotNull(vault.get("profile-photo-request/" + peerId));
    }

    @Test public void profilePhotoControlsSaveRemoveAndClearBitmapsOnBackground() throws Exception {
        signedInFixture();
        byte[] image = SafeImages.profilePhoto(photo);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "accountDialog");
                text(dialog(activity).getWindow().getDecorView(), "Profile photo").performClick();
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Choose photo"));
                invoke(activity, "previewProfilePhoto", new Class<?>[]{byte[].class, UUID.class}, image.clone(), userId);
                assertTrue((dialog(activity).getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertNull(engine.photos().own());
                invoke(activity, "previewProfilePhoto", new Class<?>[]{byte[].class, UUID.class}, image.clone(), userId);
            });
            snapshot(scenario, "60-profile-photo-preview");
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("My profile")), 10_000));
            scenario.onActivity(activity -> {
                assertArrayEquals(image, engine.photos().own());
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> view instanceof ImageView picture
                        && picture.getDrawable() instanceof android.graphics.drawable.BitmapDrawable));
            });
            snapshot(scenario, "61-my-profile-photo");
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> {
                assertTrue(((List<?>) readField(activity, "photoAvatars")).isEmpty());
                assertNull(readField(activity, "displayedBitmap")); assertNull(readField(activity, "engine"));
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("My profile")), 10_000));
            scenario.onActivity(activity -> {
                invoke(activity, "profilePhotoDialog");
                text(dialog(activity).getWindow().getDecorView(), "Remove photo").performClick();
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Remove profile photo?")), 5000));
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("My profile")), 10_000));
            scenario.onActivity(activity -> {
                ChatEngine current = (ChatEngine) readField(activity, "engine"); assertNull(current.photos().own());
                invoke(activity, "profilePhotoDialog"); assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Choose photo"));
                assertFalse(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> view instanceof TextView label
                        && "Remove photo".contentEquals(label.getText())));
            });
            snapshot(scenario, "62-profile-photo-removed");
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void contactLookupNeverRendersCachedPhotoAndExpiryClearsVisibleAvatar() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault()); saveProfilePeer(remote);
        byte[] image = SafeImages.profilePhoto(photo);
        vault.transaction(() -> { write("profile-photo-cache/" + peerId, new ProfilePhotos.Cached(peerDevice, peer.identityKey(), 1, image, System.currentTimeMillis() + 60_000)); return null; });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "verifyContactDialog", new Class<?>[]{ChatEngine.Peer.class}, peer);
                assertFalse("Username lookup must never reveal even a cached contact photo",
                        descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> view instanceof ImageView picture
                                && picture.getDrawable() instanceof android.graphics.drawable.BitmapDrawable));
                invoke(activity, "dismissContent");
            });
            snapshot(scenario, "63-saved-contact-photo");
            scenario.onActivity(activity -> {
                assertTrue(descendants(root(activity)).stream().anyMatch(view -> view instanceof ImageView picture
                        && picture.getDrawable() instanceof android.graphics.drawable.BitmapDrawable));
                try { vault.transaction(() -> { engine.photos().purge("", System.currentTimeMillis() + 120_000); return null; }); }
                catch (Exception failure) { throw new AssertionError(failure); }
                invoke(activity, "refreshProfileAvatars");
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof ImageView picture
                        && picture.getDrawable() instanceof android.graphics.drawable.BitmapDrawable));
            });
        } finally { Arrays.fill(image, (byte) 0); }
    }

    @Test public void profilePhotosStayInTheirRetainedAccountPartition() throws Exception {
        signedInFixture();
        byte[] image = SafeImages.profilePhoto(photo);
        try {
            engine.photos().update(image);
            vault.saveAccount(userId);
            ChatEngine signedOut = new ChatEngine(vault);
            assertNull(signedOut.account()); assertNull(signedOut.photos().own());
            assertTrue(vault.restoreAccount(userId));
            engine = new ChatEngine(vault);
            assertEquals(userId, engine.account().userId()); assertArrayEquals(image, engine.photos().own());
        } finally { Arrays.fill(image, (byte) 0); }
    }

    private RelayApi syntheticApi(okhttp3.Interceptor interceptor) {
        RelayApi api = new RelayApi("https://127.0.0.1:1/", null);
        okhttp3.OkHttpClient transport = ((okhttp3.OkHttpClient) readField(api, "client")).newBuilder().addInterceptor(interceptor).build();
        setField(api, "client", transport);
        return api;
    }

    private okhttp3.Response syntheticResponse(okhttp3.Request request, int status, Object body) {
        return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
            .body(okhttp3.ResponseBody.create(RelayApi.JSON.toJson(body), okhttp3.MediaType.get("application/json"))).build();
    }

    private ChatEngine.NotificationDestination notificationDestination(UUID messageId) {
        return new ChatEngine.NotificationDestination(userId, deviceId, peerId, messageId, System.currentTimeMillis() + 60_000);
    }

    private void notificationTransport(java.util.function.Function<String, ChatEngine.NotificationDestination> resolver) {
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/notifications/resolve")) {
                okio.Buffer buffer = new okio.Buffer(); request.body().writeTo(buffer);
                com.google.gson.JsonObject body = RelayApi.JSON.fromJson(buffer.readUtf8(), com.google.gson.JsonObject.class);
                assertEquals(1, body.size()); assertTrue(body.has("reference"));
                ChatEngine.NotificationDestination destination = resolver.apply(body.get("reference").getAsString());
                return syntheticResponse(request, destination == null ? 404 : 200, destination);
            }
            if (path.equals("/messages/pending") || path.equals("/messages/status") || path.equals("/groups")) return syntheticResponse(request, 200, List.of());
            if (path.startsWith("/messages/")) return syntheticResponse(request, 200, null);
            if (path.equals("/keys")) return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
            if (path.equals("/account/profile")) return syntheticResponse(request, 200, new ChatEngine.Profile(userId, "alex", "Alex"));
            if (path.endsWith("/profile") && path.startsWith("/users/id/")) {
                UUID contact = UUID.fromString(path.substring("/users/id/".length(), path.length() - "/profile".length()));
                ChatEngine.Peer saved = engine.peers().stream().filter(value -> value.userId().equals(contact)).findFirst().orElseThrow();
                return syntheticResponse(request, 200, new ChatEngine.Profile(contact, "friend", saved.name()));
            }
            if (path.equals("/users/id/" + peerId)) return syntheticResponse(request, 200, new ChatEngine.Contact(peerId, peerDevice, peer.identityKey()));
            if (path.equals("/profile/packets")) return syntheticResponse(request, 404, Map.of("error", "not_found"));
            throw new AssertionError("Unexpected synthetic notification request");
        }));
    }

    @Test public void notificationReferenceOpensOnlyItsVerifiedUnreadMessageWithoutConsumingViewOnce() throws Exception {
        signedInFixture(); conversationsFixture();
        byte[] original = vault.get("body/" + once.id());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        notificationTransport(reference -> { assertEquals("a".repeat(43), reference); calls.incrementAndGet(); return notificationDestination(once.id()); });
        assertEquals(peerId, engine.openNotification("a".repeat(43)));
        assertEquals(1, calls.get());
        assertEquals("DELIVERED", engine.entries(peerId).stream().filter(entry -> entry.id().equals(once.id())).findFirst().orElseThrow().state());
        assertArrayEquals(original, vault.get("body/" + once.id()));
        assertNull(engine.openNotification(peerId.toString())); assertEquals(1, calls.get());
        Arrays.fill(original, (byte) 0);
    }

    @Test public void notificationReferenceRejectsOtherAccountsReadExpiredAndChangedContacts() throws Exception {
        signedInFixture(); conversationsFixture();
        notificationTransport(reference -> notificationDestination(once.id()));
        long deadline = System.currentTimeMillis() + 60_000;
        assertNull(engine.notificationConversation(new ChatEngine.NotificationDestination(UUID.randomUUID(), deviceId, peerId, once.id(), deadline)));
        assertNull(engine.notificationConversation(new ChatEngine.NotificationDestination(userId, UUID.randomUUID(), peerId, once.id(), deadline)));
        assertNull(engine.notificationConversation(new ChatEngine.NotificationDestination(userId, deviceId, UUID.randomUUID(), once.id(), deadline)));
        assertNull(engine.notificationConversation(notificationDestination(UUID.randomUUID())));
        assertNull(engine.notificationConversation(new ChatEngine.NotificationDestination(userId, deviceId, peerId, once.id(), System.currentTimeMillis() - 1)));
        ChatEngine.Entry outgoing = engine.entries(peerId).stream().filter(ChatEngine.Entry::outgoing).findFirst().orElseThrow();
        assertNull(engine.notificationConversation(notificationDestination(outgoing.id())));
        vault.transaction(() -> { write("entry/" + once.id(), once.withState("READ")); return null; });
        assertNull(engine.notificationConversation(notificationDestination(once.id())));
        vault.transaction(() -> { write("entry/" + once.id(), new ChatEngine.Entry(once.id(), peerId, System.currentTimeMillis() - 1,
                once.expiry(), false, false, "DELIVERED")); return null; });
        assertNull(engine.notificationConversation(notificationDestination(once.id())));
        vault.transaction(() -> { write("entry/" + once.id(), once); return null; });
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> syntheticResponse(chain.request(), 200, new ChatEngine.Contact(peerId, UUID.randomUUID(), peer.identityKey()))));
        assertNull(engine.notificationConversation(notificationDestination(once.id())));
        vault.transaction(() -> { engine.groupSignal().forgetPeer(peerId); return null; });
        assertNull(engine.notificationConversation(notificationDestination(once.id())));
    }

    @Test public void notificationColdStartIntentOpensTheChatAndKeepsViewOnceClosed() throws Exception {
        signedInFixture(); conversationsFixture();
        notificationTransport(reference -> notificationDestination(once.id()));
        var monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();
        androidx.test.runner.lifecycle.ActivityLifecycleCallback injection = (activity, stage) -> {
            if (activity instanceof MainActivity main && stage == androidx.test.runner.lifecycle.Stage.CREATED) inject(main);
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> monitor.addLifecycleCallback(injection));
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(PushService.notificationIntent(context, "a".repeat(43)))) {
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("Conversation options")), 15_000));
            scenario.onActivity(activity -> {
                assertEquals(peerId, readField(activity, "selectedPeer"));
                assertNull(readField(activity, "notificationOpen")); assertNull(dialog(activity));
                assertNotNull(text(root(activity), "View once"));
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertEquals("DELIVERED", engine.entries(peerId).stream().filter(entry -> entry.id().equals(once.id())).findFirst().orElseThrow().state());
            });
            snapshot(scenario, "66-notification-opens-chat");
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> monitor.removeLifecycleCallback(injection)); }
    }

    @Test public void notificationShadeTapLaunchesTheExactChatThroughItsImmutablePendingIntent() throws Exception {
        signedInFixture(); conversationsFixture();
        notificationTransport(reference -> notificationDestination(once.id()));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation);
        var monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();
        var launched = new java.util.concurrent.atomic.AtomicReference<MainActivity>();
        androidx.test.runner.lifecycle.ActivityLifecycleCallback injection = (activity, stage) -> {
            if (activity instanceof MainActivity main && stage == androidx.test.runner.lifecycle.Stage.CREATED) { inject(main); launched.set(main); }
        };
        boolean granted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        try {
            if (!granted) {
                try (var output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation()
                        .executeShellCommand("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS"))) { output.readAllBytes(); }
            }
            manager.createNotificationChannel(new android.app.NotificationChannel("messages", "Messages", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            instrumentation.runOnMainSync(() -> monitor.addLifecycleCallback(injection));
            android.app.Notification notification = PushService.genericNotification(context, "a".repeat(43));
            assertTrue(notification.contentIntent.isImmutable());
            manager.notify("vanishr-qa-tap", 900, notification);
            assertTrue(device.openNotification());
            var alert = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("New message")), 10_000);
            assertNotNull("The generic notification must be visible in the shade", alert);
            alert.click();
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("Conversation options")), 15_000));
            instrumentation.runOnMainSync(() -> {
                MainActivity activity = launched.get(); assertNotNull(activity);
                assertEquals(peerId, readField(activity, "selectedPeer"));
                assertNull(dialog(activity)); assertNotNull(text(root(activity), "View once"));
                assertEquals("DELIVERED", engine.entries(peerId).stream().filter(entry -> entry.id().equals(once.id())).findFirst().orElseThrow().state());
            });
        } finally {
            manager.cancel("vanishr-qa-tap", 900);
            instrumentation.runOnMainSync(() -> {
                monitor.removeLifecycleCallback(injection);
                if (launched.get() != null) launched.get().finish();
            });
            instrumentation.waitForIdleSync();
            if (launched.get() != null) ((java.util.concurrent.ExecutorService) readField(launched.get(), "work")).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test public void notificationWarmIntentRoutesTheExactChatAndGenericOrStaleTapsReturnToList() throws Exception {
        signedInFixture(); conversationsFixture();
        notificationTransport(reference -> reference.equals("a".repeat(43)) ? notificationDestination(once.id()) : null);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final MainActivity[] original = new MainActivity[1];
            scenario.onActivity(activity -> {
                inject(activity); original[0] = activity;
                invoke(activity, "onNewIntent", new Class<?>[]{android.content.Intent.class}, PushService.notificationIntent(context, "a".repeat(43)));
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("Conversation options")), 15_000));
            scenario.onActivity(activity -> {
                assertSame(original[0], activity); assertEquals(peerId, readField(activity, "selectedPeer")); assertNull(dialog(activity));
                invoke(activity, "onNewIntent", new Class<?>[]{android.content.Intent.class}, PushService.notificationIntent(context, "b".repeat(43)));
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("New conversation")), 15_000));
            scenario.onActivity(activity -> {
                assertNull(readField(activity, "selectedPeer")); assertNotNull(readField(activity, "contactRows"));
                setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                invoke(activity, "onNewIntent", new Class<?>[]{android.content.Intent.class}, PushService.notificationIntent(context, null));
                assertNull(readField(activity, "selectedPeer")); assertNotNull(readField(activity, "contactRows"));
                android.content.Intent expired = PushService.notificationIntent(context, "a".repeat(43)).putExtra(PushService.DEADLINE, System.currentTimeMillis() - 1);
                invoke(activity, "onNewIntent", new Class<?>[]{android.content.Intent.class}, expired);
                assertNull(readField(activity, "notificationOpen")); assertNull(readField(activity, "selectedPeer"));
            });
        }
    }

    @Test public void notificationsDefaultOnButNeedRegistrationAndPreserveExplicitOptOut() {
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        assertFalse(preferences.contains("notifications"));
        assertTrue(PushService.notificationsEnabled(context));
        assertFalse(PushService.notificationsActive(context));
        assertTrue(PushService.claimPermissionPrompt(context, false));
        assertFalse("A denied permission must not be requested on every app open", PushService.claimPermissionPrompt(context, false));
        assertTrue("An explicit toggle may retry Android permission", PushService.claimPermissionPrompt(context, true));
        assertTrue(preferences.edit().putBoolean("push-active", true).commit());
        assertTrue(PushService.notificationsActive(context));
        assertTrue(preferences.edit().putBoolean("notifications", false).commit());
        assertFalse(PushService.notificationsEnabled(context)); assertFalse(PushService.notificationsActive(context));
        assertFalse(PushService.claimPermissionPrompt(context, true));
        assertTrue(preferences.edit().putBoolean("notifications", true).putBoolean("push-active", false).commit());
        assertTrue(PushService.notificationsEnabled(context));
        assertFalse("Suspending registration must keep signed-out devices quiet without changing the preference", PushService.notificationsActive(context));
    }

    @Test public void profileNotificationSwitchDefaultsOnAndSavedOffSurvivesReopening() throws Exception {
        signedInFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "accountDialog");
                CompoundButton toggle = descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof CompoundButton)
                        .map(view -> (CompoundButton) view).findFirst().orElseThrow();
                assertTrue(toggle.isChecked()); toggle.setChecked(false);
                assertFalse(PushService.notificationsEnabled(context));
                invoke(activity, "dismissContent"); invoke(activity, "accountDialog");
                CompoundButton restored = descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof CompoundButton)
                        .map(view -> (CompoundButton) view).findFirst().orElseThrow();
                assertFalse(restored.isChecked());
            });
            snapshot(scenario, "67-notification-default-choice");
        }
    }

    @Test public void notificationRegistrationUploadsWhileOtherUiWorkIsBusy() throws Exception {
        signedInFixture();
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().putBoolean("notifications", true).commit());
        var uploaded = new java.util.concurrent.CountDownLatch(1);
        sessionTransport(chain -> {
            if (!chain.request().url().encodedPath().equals("/devices/push")) throw new IOException("Unrelated synthetic request");
            assertEquals("POST", chain.request().method());
            okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer);
            Map<?, ?> body = RelayApi.JSON.fromJson(buffer.readUtf8(), Map.class);
            assertEquals(2, body.size()); assertEquals("synthetic-push-registration", body.get("token"));
            assertEquals(Boolean.TRUE, body.get("routeHints")); uploaded.countDown();
            return syntheticResponse(chain.request(), 204, null);
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            var worker = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ExecutorService>();
            scenario.onActivity(activity -> {
                inject(activity); setField(activity, "busy", true); setField(activity, "pushRegistrationRunning", true);
                worker.set((java.util.concurrent.ExecutorService) readField(activity, "work"));
                invoke(activity, "accountDialog");
                invoke(activity, "uploadPushToken", new Class<?>[]{ChatEngine.class, int.class, String.class}, engine,
                        (int) readField(activity, "screenGeneration"), "synthetic-push-registration");
            });
            assertTrue("Busy UI work must not silently discard push registration", uploaded.await(10, java.util.concurrent.TimeUnit.SECONDS));
            worker.get().submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(userId, readField(activity, "pushRegisteredAccount"));
                assertTrue(PushService.notificationsActive(context));
                assertFalse((boolean) readField(activity, "pushRegistrationRunning"));
                assertTrue("Registration must not clear another action's busy flag", (boolean) readField(activity, "busy"));
                TextView status = (TextView) readField(activity, "notificationStatus"); assertNotNull(status);
                assertFalse(status.getText().toString().contains("synthetic-push-registration"));
                setField(activity, "busy", false);
            });
        } finally { assertTrue(preferences.edit().putBoolean("notifications", false).commit()); }
    }

    @Test public void notificationRegistrationIgnoresOptOutAndStaleActivityGeneration() throws Exception {
        signedInFixture();
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().putBoolean("notifications", false).commit());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        sessionTransport(chain -> { if (chain.request().url().encodedPath().equals("/devices/push")) attempts.incrementAndGet(); throw new IOException("Synthetic offline request"); });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            var worker = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ExecutorService>();
            scenario.onActivity(activity -> {
                inject(activity); worker.set((java.util.concurrent.ExecutorService) readField(activity, "work"));
                invoke(activity, "uploadPushToken", new Class<?>[]{ChatEngine.class, int.class, String.class}, engine,
                        (int) readField(activity, "screenGeneration"), "synthetic-push-registration");
            });
            worker.get().submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, attempts.get());
            assertTrue(preferences.edit().putBoolean("notifications", true).commit());
            scenario.onActivity(activity -> invoke(activity, "uploadPushToken", new Class<?>[]{ChatEngine.class, int.class, String.class}, engine,
                    (int) readField(activity, "screenGeneration") - 1, "synthetic-push-registration"));
            worker.get().submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, attempts.get());
            scenario.onActivity(activity -> assertNull(readField(activity, "pushRegisteredAccount")));
        } finally { assertTrue(preferences.edit().putBoolean("notifications", false).commit()); }
    }

    @Test public void notificationWaitsForStorageAndCannotCarryOverToAnotherAccount() throws Exception {
        signedInFixture(); conversationsFixture();
        notificationTransport(reference -> { throw new AssertionError("Another account must never resolve this notification"); });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "notificationIntent", new Class<?>[]{android.content.Intent.class}, PushService.notificationIntent(context, "a".repeat(43)));
                Object pending = readField(activity, "notificationOpen"); assertNotNull(pending);
                setField(activity, "engine", null); invoke(activity, "openNotification");
                assertSame(pending, readField(activity, "notificationOpen"));
                setField(pending, "owner", UUID.randomUUID()); setField(activity, "engine", engine);
                invoke(activity, "openNotification");
                assertNull(readField(activity, "notificationOpen")); assertNull(readField(activity, "selectedPeer"));
                assertNotNull(readField(activity, "contactRows"));
            });
        }
    }

    @Test public void googleReauthenticationUsesFreshAccountOnlyChallenges() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (RelayApi api = syntheticApi(chain -> {
            okhttp3.Request request = chain.request();
            assertEquals("/auth/google/challenge", request.url().encodedPath());
            assertNull("A fresh Google challenge must not carry an expired bearer", request.header("Authorization"));
            okio.Buffer buffer = new okio.Buffer(); request.body().writeTo(buffer);
            GoogleSignIn.Start start = RelayApi.JSON.fromJson(buffer.readUtf8(), GoogleSignIn.Start.class);
            assertNull("The stale device must be checked after fresh Google authentication, not bound into its nonce", start.deviceId());
            int attempt = count.incrementAndGet();
            return syntheticResponse(request, 200, new GoogleSignIn.Challenge((attempt == 1 ? "a" : "b").repeat(43),
                (attempt == 1 ? "c" : "d").repeat(43), BuildConfig.GOOGLE_WEB_CLIENT_ID, System.currentTimeMillis() + 300_000));
        })) {
            GoogleSignIn.Challenge first = GoogleSignIn.prepare(api);
            GoogleSignIn.Challenge second = GoogleSignIn.prepare(api);
            assertNotEquals(first.id(), second.id()); assertNotEquals(first.nonce(), second.nonce());
            assertEquals(2, count.get());
        }
    }

    @Test public void googleReauthenticationReenrollsTheSameKeysWithoutSigningOut() throws Exception {
        signedInFixture(); conversationsFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        byte[] body = vault.get("body/" + once.id());
        engine.invalidateToken();
        ((RelayApi) readField(engine, "api")).close();
        var registrations = new java.util.concurrent.atomic.AtomicInteger();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request();
            if (request.url().encodedPath().equals("/devices")) {
                assertEquals("Bearer fresh-enrollment", request.header("Authorization"));
                okio.Buffer buffer = new okio.Buffer(); request.body().writeTo(buffer);
                ChatEngine.DeviceRegistration registration = RelayApi.JSON.fromJson(buffer.readUtf8(), ChatEngine.DeviceRegistration.class);
                assertEquals(deviceId, registration.deviceId());
                assertFalse("Reauthentication must never silently replace another device", registration.replaceExisting());
                assertEquals(identity, userId + ":" + Base64.getEncoder().encodeToString(registration.identityKey()));
                registrations.incrementAndGet();
                return syntheticResponse(request, 200, new ChatEngine.Token(userId, deviceId, "fresh-device-session", System.currentTimeMillis() + 3_600_000));
            }
            assertEquals("/keys", request.url().encodedPath());
            assertEquals("Bearer fresh-device-session", request.header("Authorization"));
            return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
        }));
        engine.finishGoogleLogin(new ChatEngine.GoogleResponse(new ChatEngine.Token(userId, null, "fresh-enrollment", System.currentTimeMillis() + 300_000), "alex"), false);
        assertTrue(engine.authenticated()); assertTrue(engine.usesGoogle());
        assertEquals(identity, engine.identityCode()); assertEquals(deviceId, engine.activeDeviceId());
        assertEquals(1, registrations.get()); assertEquals("fresh-device-session", engine.account().accessToken());
        assertArrayEquals(body, vault.get("body/" + once.id()));
        assertEquals(once.expiresAt(), engine.entries(peerId).stream().filter(entry -> entry.id().equals(once.id())).findFirst().orElseThrow().expiresAt());
        Arrays.fill(body, (byte) 0);
    }

    @Test public void googleReauthenticationRejectsAnotherAccountAndRequiresExplicitDeviceReplacement() throws Exception {
        signedInFixture(); conversationsFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        byte[] body = vault.get("body/" + once.id());
        engine.invalidateToken();
        ChatEngine.Account saved = engine.account();
        assertThrows(SecurityException.class, () -> engine.finishGoogleLogin(new ChatEngine.GoogleResponse(
            new ChatEngine.Token(UUID.randomUUID(), null, "other-account", System.currentTimeMillis() + 300_000), "other"), false));
        assertEquals(saved, engine.account());
        ((RelayApi) readField(engine, "api")).close();
        List<Boolean> replacements = new ArrayList<>();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request();
            if (request.url().encodedPath().equals("/devices")) {
                okio.Buffer buffer = new okio.Buffer(); request.body().writeTo(buffer);
                ChatEngine.DeviceRegistration registration = RelayApi.JSON.fromJson(buffer.readUtf8(), ChatEngine.DeviceRegistration.class);
                replacements.add(registration.replaceExisting());
                assertEquals(deviceId, registration.deviceId());
                assertEquals(identity, userId + ":" + Base64.getEncoder().encodeToString(registration.identityKey()));
                if (!registration.replaceExisting()) return syntheticResponse(request, 409, Map.of("error", "device_already_registered"));
                return syntheticResponse(request, 200, new ChatEngine.Token(userId, deviceId, "replacement-session", System.currentTimeMillis() + 3_600_000));
            }
            assertEquals("/keys", request.url().encodedPath());
            return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
        }));
        ChatEngine.GoogleResponse verified = new ChatEngine.GoogleResponse(new ChatEngine.Token(userId, null, "fresh-enrollment", System.currentTimeMillis() + 300_000), "alex");
        for (int attempt = 0; attempt < 2; attempt++) {
            RelayApi.ApiFailure failure = assertThrows(RelayApi.ApiFailure.class, () -> engine.finishGoogleLogin(verified, false));
            assertEquals(409, failure.status); assertEquals("device_already_registered", failure.code);
            assertFalse(engine.authenticated()); assertEquals(identity, engine.identityCode());
            assertArrayEquals(body, vault.get("body/" + once.id()));
        }
        engine.finishGoogleLogin(verified, true);
        assertEquals(List.of(false, false, true), replacements);
        assertTrue(engine.authenticated()); assertEquals(identity, engine.identityCode());
        assertArrayEquals(body, vault.get("body/" + once.id()));
        Arrays.fill(body, (byte) 0);
    }

    @Test public void googleReauthenticationRejectsMismatchedEnrollmentResponse() throws Exception {
        signedInFixture(); conversationsFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        engine.invalidateToken();
        ((RelayApi) readField(engine, "api")).close();
        setField(engine, "api", syntheticApi(chain -> {
            assertEquals("/devices", chain.request().url().encodedPath());
            return syntheticResponse(chain.request(), 200, new ChatEngine.Token(UUID.randomUUID(), UUID.randomUUID(), "wrong-session", System.currentTimeMillis() + 3_600_000));
        }));
        assertThrows(SecurityException.class, () -> engine.finishGoogleLogin(new ChatEngine.GoogleResponse(
            new ChatEngine.Token(userId, null, "fresh-enrollment", System.currentTimeMillis() + 300_000), "alex"), false));
        assertFalse(engine.authenticated()); assertEquals(userId, engine.account().userId());
        assertEquals(deviceId, engine.account().deviceId()); assertEquals(identity, engine.identityCode());
    }

    @Test public void googleCredentialResetFailureCanRetryWithoutSigningOut() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        engine.invalidateToken();
        String identity = engine.identityCode();
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        boolean previousReset = preferences.getBoolean("google-reset-pending", false);
        Field clearing = GoogleSignIn.class.getDeclaredField("clearingSession"); clearing.setAccessible(true);
        Object previous = clearing.get(null);
        var pending = new java.util.concurrent.CompletableFuture<Boolean>();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        clearing.set(null, pending);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "resetGoogleSignIn", new Class<?>[]{String.class, boolean.class}, engine.account().origin(), false);
                assertEquals(true, readField(activity, "busy")); assertTrue(preferences.getBoolean("google-reset-pending", false));
            });
            pending.complete(false);
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(
                "Google could not reset its sign-in session. Check Google Play services and try again.")), 5000));
            scenario.onActivity(activity -> {
                assertEquals(false, readField(activity, "busy"));
                assertTrue(preferences.getBoolean("google-reset-pending", false));
                assertEquals(identity, engine.identityCode());
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
            });
            var retry = new java.util.concurrent.CompletableFuture<Boolean>(); clearing.set(null, retry);
            scenario.onActivity(activity -> invoke(activity, "resetGoogleSignIn", new Class<?>[]{String.class, boolean.class}, engine.account().origin(), false));
            retry.complete(true);
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(
                "Cannot connect right now. Check your connection and try again.")), 5000));
            scenario.onActivity(activity -> {
                assertEquals(false, readField(activity, "busy"));
                assertFalse(preferences.getBoolean("google-reset-pending", true));
                assertTrue(engine.usesGoogle()); assertEquals(identity, engine.identityCode());
                assertNull(readField(activity, "googleAttempt"));
            });
        } finally { clearing.set(null, previous); preferences.edit().putBoolean("google-reset-pending", previousReset).commit(); }
    }

    @Test public void googleCredentialResetCompletingAfterPauseCannotStartAStaleSignIn() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        engine.invalidateToken();
        String identity = engine.identityCode();
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        boolean previousReset = preferences.getBoolean("google-reset-pending", false);
        Field clearing = GoogleSignIn.class.getDeclaredField("clearingSession"); clearing.setAccessible(true);
        Object previous = clearing.get(null);
        var pending = new java.util.concurrent.CompletableFuture<Boolean>(); clearing.set(null, pending);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "resetGoogleSignIn", new Class<?>[]{String.class, boolean.class}, engine.account().origin(), false);
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            pending.complete(true);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNull(readField(activity, "engine")); assertNull(readField(activity, "googleAttempt"));
                assertEquals(false, readField(activity, "busy"));
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Continue with Google")), 10_000));
            scenario.onActivity(activity -> {
                ChatEngine retained = (ChatEngine) readField(activity, "engine");
                assertTrue(retained.usesGoogle()); assertFalse(retained.authenticated());
                assertEquals(identity, retained.identityCode()); assertEquals(deviceId, retained.account().deviceId());
                assertTrue(text(root(activity), "Continue with Google").isEnabled());
            });
        } finally { clearing.set(null, previous); preferences.edit().putBoolean("google-reset-pending", previousReset).commit(); }
    }

    @Test public void profileSessionRejectionReturnsToSignInInsteadOfStayingInTheDialog() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        var requestReceived = new java.util.concurrent.CountDownLatch(1);
        ((RelayApi) readField(engine, "api")).close();
        setField(engine, "api", syntheticApi(chain -> {
            if (chain.request().method().equals("PATCH") && chain.request().url().encodedPath().equals("/account/profile")) requestReceived.countDown();
            return syntheticResponse(chain.request(), 401, Map.of("error", "authentication_required"));
        }));
        java.util.concurrent.ExecutorService[] worker = new java.util.concurrent.ExecutorService[1];
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "accountDialog");
                worker[0] = (java.util.concurrent.ExecutorService) readField(activity, "work");
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> "Save name".equals(view.getContentDescription()))
                    .findFirst().orElseThrow().performClick());
            });
            assertTrue("Profile save must reach the rejected request", requestReceived.await(10, java.util.concurrent.TimeUnit.SECONDS));
            worker[0].submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue("Rejected profile must return to sign-in", device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Continue with Google")), 5000));
            scenario.onActivity(activity -> {
                assertNull(dialog(activity)); assertFalse(engine.authenticated());
                assertEquals(identity, engine.identityCode()); assertTrue(engine.usesGoogle());
            });
        }
    }

    @Test public void groupFirstSendDistributesThePreMessageKeyAndKeepsTheRelayBlind() throws Exception {
        signedInFixture();
        UUID groupId=UUID.randomUUID(); UUID epoch=UUID.randomUUID();
        SignalClient recipient=new SignalClient(peerId,new DeviceSecurityTest.MemoryVault());
        recipient.verifyPeer(userId,engine.groupSignal().publicIdentity());
        engine.groupSignal().verifyPeer(peerId,recipient.publicIdentity());
        peer=new ChatEngine.Peer(peerId,peerDevice,Base64.getEncoder().encodeToString(recipient.publicIdentity()),"friend","friend",null);
        GroupChat.Snapshot snapshot=new GroupChat.Snapshot(groupId,userId,2,epoch,false,List.of(
            new GroupChat.Member(userId,deviceId,Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()),"alex",null,"ACTIVE",0),
            new GroupChat.Member(peerId,peerDevice,peer.identityKey(),"friend",null,"ACTIVE",0)));
        vault.transaction(() -> {
            write("contact/"+peerId,peer); write("group/"+groupId,new GroupChat.Conversation(snapshot,"Private launch",null));
            write("group-title/"+groupId,"Private launch"); write("group-allowed/"+groupId,snapshot.roster().members()); return null;
        });
        List<GroupChat.ControlSend> controls=new ArrayList<>(); List<GroupChat.Send> messages=new ArrayList<>();
        ((RelayApi) readField(engine,"api")).close();
        setField(engine,"api",syntheticApi(chain -> {
            okhttp3.Request request=chain.request();
            String path=request.url().encodedPath();
            if (path.equals("/groups/"+groupId) && request.method().equals("GET")) return syntheticResponse(request,200,snapshot);
            if (path.endsWith("/keys")) {
                try {
                    PublicBundle bundle=recipient.generatePreKey(Instant.now());
                    return syntheticResponse(request,200,List.of(new GroupChat.Claimed(peerId,bundle)));
                }
                catch (Exception failure) { throw new IOException("Synthetic prekey fixture failed"); }
            }
            okio.Buffer buffer=new okio.Buffer(); request.body().writeTo(buffer); String body=buffer.readUtf8();
            assertFalse(body.contains("Private launch")); assertFalse(body.contains("First group message"));
            if (path.endsWith("/controls")) {
                controls.addAll(RelayApi.JSON.fromJson(body,GroupChat.Controls.class).packets()); return syntheticResponse(request,200,Map.of());
            }
            assertTrue(path.endsWith("/messages"));
            GroupChat.Send message=RelayApi.JSON.fromJson(body,GroupChat.Send.class); messages.add(message);
            return syntheticResponse(request,200,new GroupChat.Status(message.id(),message.expiresAt(),1,0,0,"QUEUED"));
        }));
        UUID messageId=UUID.randomUUID(); long created=System.currentTimeMillis();
        createFixtureKey(AndroidVault.contentAlias(created+3_600_000,messageId,userId));
        engine.groups().send(groupId,"First group message",null,ChatEnvelope.Expiry.HOUR_1,messageId,created,() -> { });
        assertEquals(2,controls.size()); assertEquals(1,messages.size());
        SignalGroup receiving=new SignalGroup(new DeviceSecurityTest.MemoryVault(),groupId,epoch,peerId);
        for (GroupChat.ControlSend packet : controls) {
            byte[] plaintext=recipient.decrypt(userId,new SignalClient.Packet(packet.type(),packet.ciphertext()));
            try {
                GroupChat.ControlBody body=RelayApi.JSON.fromJson(new String(plaintext,StandardCharsets.UTF_8),GroupChat.ControlBody.class);
                assertEquals(snapshot.roster().digest(),body.rosterDigest()); assertEquals("Private launch",body.name());
                if (body.kind().equals("KEY")) receiving.accept(userId,body.distribution());
            } finally { Arrays.fill(plaintext,(byte)0); }
        }
        byte[] plaintext=receiving.decrypt(userId,messages.get(0).ciphertext());
        try {
            GroupEnvelope envelope=RelayApi.JSON.fromJson(new String(plaintext,StandardCharsets.UTF_8),GroupEnvelope.class);
            envelope.verify(groupId,epoch,2,messageId,userId,deviceId,ChatEnvelope.Expiry.HOUR_1,created+3_600_000,null,Instant.now());
            assertEquals("First group message",envelope.content().text());
        } finally { Arrays.fill(plaintext,(byte)0); }
        assertEquals(1,engine.entries(groupId).size());
        assertEquals(epoch,engine.entries(groupId).get(0).groupEpoch());
        assertTrue(vault.names("group-out/").isEmpty()); assertTrue(vault.names("group-distribution/").isEmpty());
    }

    @Test public void groupReceiverRequiresOwnerApprovalAndAcknowledgesOnlyItsOwnCopy() throws Exception {
        signedInFixture();
        SignalClient owner=new SignalClient(peerId,new DeviceSecurityTest.MemoryVault());
        owner.verifyPeer(userId,engine.groupSignal().publicIdentity()); engine.groupSignal().verifyPeer(peerId,owner.publicIdentity());
        owner.establish(userId,engine.groupSignal().generatePreKey(Instant.now()),Instant.now());
        UUID groupId=UUID.randomUUID(); UUID epoch=UUID.randomUUID(); UUID messageId=UUID.randomUUID();
        long created=System.currentTimeMillis(); long deadline=created+3_600_000;
        GroupChat.Snapshot group=new GroupChat.Snapshot(groupId,peerId,2,epoch,false,List.of(
            new GroupChat.Member(peerId,peerDevice,Base64.getEncoder().encodeToString(owner.publicIdentity()),"owner",null,"ACTIVE",0),
            new GroupChat.Member(userId,deviceId,Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()),"alex",null,"ACTIVE",0)));
        vault.transaction(() -> { write("contact/"+peerId,new ChatEngine.Peer(peerId,peerDevice,group.member(peerId).identityKey(),"Owner","owner",null)); return null; });
        SignalGroup sending=new SignalGroup(new DeviceSecurityTest.MemoryVault(),groupId,epoch,peerId);
        List<GroupChat.Control> controls=new ArrayList<>();
        for (String kind : List.of("ROSTER","KEY")) {
            GroupChat.ControlBody body=new GroupChat.ControlBody(1,kind,groupId,epoch,2,peerId,userId,"Verified group",group.roster().digest(),kind.equals("KEY") ? sending.distribution() : null,deadline);
            SignalClient.Packet encrypted=owner.encrypt(userId,RelayApi.JSON.toJson(body).getBytes(StandardCharsets.UTF_8),Instant.now());
            controls.add(new GroupChat.Control(UUID.randomUUID(),groupId,peerId,peerDevice,userId,deviceId,epoch,2,deadline,encrypted.type(),encrypted.ciphertext()));
        }
        GroupEnvelope envelope=new GroupEnvelope(1,groupId,epoch,2,new ChatEnvelope(1,messageId,peerId,peerDevice,groupId,epoch,created,deadline,ChatEnvelope.Expiry.HOUR_1,"Protected group hello",null));
        GroupChat.Message message=new GroupChat.Message(messageId,groupId,epoch,2,peerId,peerDevice,ChatEnvelope.Expiry.HOUR_1,deadline,
                sending.encrypt(RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8)),null);
        List<String> acknowledgements=new ArrayList<>();
        ((RelayApi) readField(engine,"api")).close();
        setField(engine,"api",syntheticApi(chain -> {
            okhttp3.Request request=chain.request(); String path=request.url().encodedPath();
            if (path.equals("/groups")) return syntheticResponse(request,200,List.of(group));
            if (path.endsWith("/controls") && request.method().equals("GET")) return syntheticResponse(request,200,controls);
            if (path.contains("/controls") || path.endsWith("/status")) return syntheticResponse(request,200,List.of());
            if (path.endsWith("/messages") && request.method().equals("GET")) return syntheticResponse(request,200,List.of(message));
            if (path.endsWith("/delivered") || path.endsWith("/read")) { acknowledgements.add(path); return syntheticResponse(request,200,Map.of()); }
            throw new IOException("Unexpected synthetic group request");
        }));
        createFixtureKey(AndroidVault.contentAlias(deadline,messageId,userId));
        assertFalse(engine.groups().conversations().stream().anyMatch(engine.groups()::ready));
        engine.groups().sync();
        assertTrue(engine.groups().ready(engine.groups().get(groupId)));
        assertEquals("Verified group",engine.groups().get(groupId).name());
        assertEquals(1,engine.entries(groupId).size());
        ChatEngine.Entry entry=engine.entries(groupId).get(0);
        assertEquals(peerId,entry.senderId());
        ChatEngine.NotificationDestination destination = new ChatEngine.NotificationDestination(userId, deviceId, groupId, messageId, System.currentTimeMillis() + 60_000);
        assertEquals(groupId, engine.notificationConversation(destination));
        assertEquals("DELIVERED", engine.entries(groupId).get(0).state());
        assertEquals("Protected group hello",engine.content(entry,false).envelope().text());
        assertNull(engine.notificationConversation(destination));
        assertNull(vault.get("ack/"+messageId)); assertNotNull(vault.get("group-ack/"+messageId));
        engine.groups().sync();
        assertEquals(1,engine.entries(groupId).size());
        assertTrue(acknowledgements.stream().anyMatch(path -> path.endsWith("/read")));
        assertEquals("READ",engine.entries(groupId).get(0).state());
    }

    @Test public void groupScreensRequireConsentAndBlockSendingUntilMembershipIsApproved() throws Exception {
        signedInFixture();
        UUID groupId=UUID.randomUUID(); UUID epoch=UUID.randomUUID();
        String key=Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity());
        GroupChat.Snapshot group=new GroupChat.Snapshot(groupId,userId,1,epoch,false,List.of(
                new GroupChat.Member(userId,deviceId,key,"alex",null,"ACTIVE",0),
                new GroupChat.Member(peerId,peerDevice,key,"friend",null,"ACTIVE",0)));
        vault.transaction(() -> { write("group/"+groupId,new GroupChat.Conversation(group,"Weekend plans",null)); return null; });
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); assertNotNull(text(root(activity),"Weekend plans"));
                invoke(activity,"newGroupDialog");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof MaterialCheckBox)
                        .map(view -> (MaterialCheckBox)view).findFirst().orElseThrow().setChecked(true);
                assertTrue(dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            });
            snapshot(scenario,"40-new-group");
            scenario.onActivity(activity -> {
                dialog(activity).dismiss(); setField(activity,"selectedPeer",groupId); invoke(activity,"render");
                assertNotNull(text(root(activity),"Your private group"));
                assertFalse(((EditText)readField(activity,"composer")).isEnabled());
                assertFalse(((View)readField(activity,"sendControl")).isEnabled());
            });
            snapshot(scenario,"57-centered-empty-group");
            scenario.onActivity(activity -> assertHorizontallyCentered((TextView) text(root(activity), "Your private group"),
                    (View) readField(activity, "messageScroll")));
            vault.transaction(() -> { write("group-approval/"+groupId+"/"+epoch,new GroupChat.Approval(group.roster(),"Weekend plans",System.currentTimeMillis()+86_400_000)); return null; });
            scenario.onActivity(activity -> {
                invoke(activity,"render"); assertTrue(((EditText)readField(activity,"composer")).isEnabled());
                invoke(activity,"groupInfo",new Class<?>[]{UUID.class},groupId);
                assertNotNull(text(dialog(activity).getWindow().getDecorView(),"2 / 200 members"));
                assertNotNull(text(dialog(activity).getWindow().getDecorView(),"Close group"));
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> "Invite members".equals(view.getContentDescription())));
            });
            snapshot(scenario,"41-group-members");
        }
    }

    @Test public void groupInvitationCannotBeAcceptedBeforeIndependentOwnerVerification() throws Exception {
        signedInFixture();
        UUID id=UUID.randomUUID(); UUID epoch=UUID.randomUUID();
        String key=Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity());
        GroupChat.Snapshot invitation=new GroupChat.Snapshot(id,peerId,1,epoch,false,List.of(
                new GroupChat.Member(peerId,peerDevice,key,"owner",null,"ACTIVE",0),
                new GroupChat.Member(userId,deviceId,key,"alex",null,"INVITED",System.currentTimeMillis()+86_400_000)));
        vault.transaction(() -> { write("group/"+id,new GroupChat.Conversation(invitation,"Private group",null)); return null; });
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity,"groupInvitationDialog",new Class<?>[]{GroupChat.Conversation.class},engine.groups().get(id));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNotNull(text(dialog(activity).getWindow().getDecorView(),"Verify owner"));
                assertFalse(dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertFalse(descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof MaterialCheckBox).findFirst().orElseThrow().isEnabled());
            });
            snapshot(scenario,"42-group-invitation");
            vault.transaction(() -> { write("contact/"+peerId,new ChatEngine.Peer(peerId,peerDevice,key,"Owner","owner",null)); write("group-invitation/"+id,new GroupChat.Invitation("Private group",System.currentTimeMillis()+86_400_000)); return null; });
            scenario.onActivity(activity -> {
                dialog(activity).dismiss(); invoke(activity,"groupInvitationDialog",new Class<?>[]{GroupChat.Conversation.class},engine.groups().get(id));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                MaterialCheckBox consent=descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof MaterialCheckBox)
                    .map(view -> (MaterialCheckBox)view).findFirst().orElseThrow();
                assertTrue(consent.isEnabled()); consent.setChecked(true);
                assertTrue(dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            });
        }
    }

    @Test public void groupOwnerControlDeadlineCannotBeExtendedByRelayMetadata() throws Exception {
        signedInFixture();
        SignalClient owner=new SignalClient(peerId,new DeviceSecurityTest.MemoryVault());
        owner.verifyPeer(userId,engine.groupSignal().publicIdentity()); engine.groupSignal().verifyPeer(peerId,owner.publicIdentity());
        owner.establish(userId,engine.groupSignal().generatePreKey(Instant.now()),Instant.now());
        UUID id=UUID.randomUUID(); UUID epoch=UUID.randomUUID(); long deadline=System.currentTimeMillis()+60_000;
        GroupChat.Snapshot group=new GroupChat.Snapshot(id,peerId,1,epoch,false,List.of(
                new GroupChat.Member(peerId,peerDevice,Base64.getEncoder().encodeToString(owner.publicIdentity()),"owner",null,"ACTIVE",0),
                new GroupChat.Member(userId,deviceId,Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()),"alex",null,"ACTIVE",0)));
        vault.transaction(() -> { write("contact/"+peerId,new ChatEngine.Peer(peerId,peerDevice,group.member(peerId).identityKey(),"Owner","owner",null)); return null; });
        GroupChat.ControlBody body=new GroupChat.ControlBody(1,"ROSTER",id,epoch,1,peerId,userId,"Bounded invitation",group.roster().digest(),null,deadline);
        SignalClient.Packet encrypted=owner.encrypt(userId,RelayApi.JSON.toJson(body).getBytes(StandardCharsets.UTF_8),Instant.now());
        GroupChat.Control extended=new GroupChat.Control(UUID.randomUUID(),id,peerId,peerDevice,userId,deviceId,epoch,1,deadline+60_000,encrypted.type(),encrypted.ciphertext());
        ((RelayApi) readField(engine,"api")).close();
        setField(engine,"api",syntheticApi(chain -> {
            if (chain.request().url().encodedPath().equals("/groups")) return syntheticResponse(chain.request(),200,List.of(group));
            assertTrue(chain.request().url().encodedPath().endsWith("/controls"));
            return syntheticResponse(chain.request(),200,List.of(extended));
        }));
        engine.groups().sync();
        assertFalse(engine.groups().ready(engine.groups().get(id)));
        assertEquals("Identity verification required",engine.groups().get(id).issue());
        assertNull(vault.get("group-approval/"+id+"/"+epoch));
        assertNull(vault.get("group-control-seen/"+extended.id()));
    }

    @Test public void removedGroupRoutesBackToChatsWithoutKeepingItsComposer() throws Exception {
        signedInFixture();
        UUID id=UUID.randomUUID(); UUID epoch=UUID.randomUUID();
        GroupChat.Snapshot group=new GroupChat.Snapshot(id,userId,1,epoch,false,List.of(
                new GroupChat.Member(userId,deviceId,Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()),"alex",null,"ACTIVE",0)));
        vault.transaction(() -> { write("group/"+id,new GroupChat.Conversation(group,"Removed group",null)); return null; });
        var device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); setField(activity,"selectedPeer",id); invoke(activity,"render"); });
            engine.groups().forget(id);
            scenario.onActivity(activity -> ((java.util.concurrent.ExecutorService)readField(activity,"work")).execute(() -> invoke(activity,"syncOnce")));
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("New conversation")),10_000));
            scenario.onActivity(activity -> { assertNull(readField(activity,"selectedPeer")); assertNull(readField(activity,"composer")); });
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
        assertTrue(store.containsAlias(AndroidVault.currentAlias(AndroidVault.MASTER)));
        assertTrue(store.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(previousDeadline, once.id()))));
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
        assertFalse(store.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(expired.expiresAt(), expired.id()))));
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
                int edge = Math.round(17 * context.getResources().getDisplayMetrics().density);
                View toolbar=((ViewGroup)root(activity)).getChildAt(0);
                assertTrue(toolbar.getPaddingLeft() >= edge);
                assertTrue(toolbar.getPaddingRight() >= edge);
                assertTrue(descendants(root(activity)).stream().anyMatch(view -> "My profile".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())));
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
            var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
            assertTrue(preferences.edit().putBoolean("push-active", true).commit());
            scenario.onActivity(activity -> {
                text(dialog(activity).getWindow().getDecorView(), "Sign out").performClick();
                int beforeSignOut = (int) readField(activity, "screenGeneration");
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue("Sign-out must invalidate late push-registration callbacks", (int) readField(activity, "screenGeneration") > beforeSignOut);
                assertFalse("Sign-out must immediately suppress push alerts", PushService.notificationsActive(context));
                assertTrue("Sign-out is not an explicit notification opt-out", PushService.notificationsEnabled(context));
                assertFalse(preferences.contains("notifications"));
            });
            var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Continue with Google")), 10_000));
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

    private void awaitPhotoCondition(java.util.function.BooleanSupplier condition) throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(1);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable check = new Runnable() {
            @Override public void run() { if (condition.getAsBoolean()) ready.countDown(); else handler.postDelayed(this, 50); }
        };
        handler.post(check);
        try { assertTrue("Photo operation did not complete", ready.await(20, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { handler.removeCallbacks(check); }
    }

    private void photoFixturePermission() {
        assertEquals("app.vanishr.android.qa", context.getPackageName());
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES : android.Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
    }

    private void photoSnapshot(RemotePhotosActivity activity, String name) {
        View decor = activity.getWindow().getDecorView();
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        Bitmap image = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            decor.draw(new Canvas(image));
            File folder = new File(context.getExternalFilesDir(null), "ui-qa"); assertTrue(folder.isDirectory() || folder.mkdirs());
            try (OutputStream output = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            for (View view : descendants(decor)) if (view instanceof TextView text && text.isShown() && text.getLayout() != null) {
                int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                for (int line = 0; line < text.getLineCount(); line++) assertTrue("Photo UI text must fit", text.getLayout().getLineMax(line) <= available + 4);
            }
        } catch (IOException failure) { throw new AssertionError(failure); }
        finally { image.recycle(); }
    }

    private android.net.Uri insertPhotoFixture(byte[] bytes) throws Exception {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
        android.content.ContentValues metadata = new android.content.ContentValues();
        metadata.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "vanishr-qa-" + UUID.randomUUID() + ".jpg");
        metadata.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        metadata.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vanishr-QA");
        metadata.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, metadata);
        assertNotNull(uri);
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) { assertNotNull(output); output.write(bytes); }
            metadata.clear(); metadata.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
            assertEquals(1, context.getContentResolver().update(uri, metadata, null, null)); return uri;
        } catch (Exception failure) { context.getContentResolver().delete(uri, null, null); throw failure; }
    }

    private final class PhotoServer implements AutoCloseable {
        final okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        final SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        final java.util.concurrent.BlockingQueue<RemotePhotoSession.Frame> received = new java.util.concurrent.LinkedBlockingQueue<>();
        final ArrayDeque<RemotePhotoSession.Packet> incoming = new ArrayDeque<>();
        final Set<UUID> delivered = new HashSet<>();
        final okhttp3.tls.HandshakeCertificates clientTls;
        final UUID id = UUID.randomUUID();
        final boolean localOwner;
        final byte[] thumbnail;
        final java.util.concurrent.atomic.AtomicInteger originals = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger declined = new java.util.concurrent.atomic.AtomicInteger();
        RemotePhotoSession.Session snapshot;
        volatile boolean disconnected;
        volatile Throwable error;
        PhotoServer(boolean localOwner) throws Exception {
            this.localOwner = localOwner;
            remote.verifyPeer(userId, engine.groupSignal().publicIdentity()); saveProfilePeer(remote);
            Bitmap icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888); icon.eraseColor(Color.rgb(40, 120, 95));
            ByteArrayOutputStream output = new ByteArrayOutputStream(); icon.compress(Bitmap.CompressFormat.JPEG, 75, output); icon.recycle(); thumbnail = output.toByteArray();
            var certificate = new okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build();
            var serverTls = new okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build();
            clientTls = new okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
            server.useHttps(serverTls.sslSocketFactory(), false);
            ChatEngine.Contact own = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()));
            ChatEngine.Contact other = RemotePhotoSession.contact(peer);
            if (localOwner) snapshot = new RemotePhotoSession.Session(id, other, own, false, System.currentTimeMillis() + 300_000, remote.generatePreKey(Instant.now()));
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    try {
                        if ("/photo-events".equals(request.getPath())) return new okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener() { });
                        synchronized (PhotoServer.this) {
                            if ("/remote-photos".equals(request.getPath())) {
                                RemotePhotoSession.Start start = RelayApi.JSON.fromJson(request.getBody().readUtf8(), RemotePhotoSession.Start.class);
                                snapshot = new RemotePhotoSession.Session(start.id(), own, other, true, System.currentTimeMillis() + 300_000, start.key());
                                remote.establish(userId, start.key(), Instant.now()); queue(start.id(), "HELLO", 0, 0, 0, 0, false, null);
                                return response(snapshot);
                            }
                            if (request.getPath().endsWith("/accept")) {
                                snapshot = new RemotePhotoSession.Session(snapshot.id(), snapshot.requester(), snapshot.owner(), true, snapshot.expiresAt(), snapshot.key());
                                return response(snapshot);
                            }
                            if (request.getPath().endsWith("/exchange")) {
                                if (disconnected) return new okhttp3.mockwebserver.MockResponse().setResponseCode(410).setBody("{\"error\":\"photo_session_ended\"}");
                                RemotePhotoSession.Exchange exchange = RelayApi.JSON.fromJson(request.getBody().readUtf8(), RemotePhotoSession.Exchange.class);
                                if (!incoming.isEmpty() && incoming.peek().id().equals(exchange.acknowledge())) incoming.remove();
                                if (exchange.packet() != null && delivered.add(exchange.packet().id())) {
                                    RemotePhotoSession.Send packet = exchange.packet();
                                    byte[] plaintext = remote.decrypt(userId, new SignalClient.Packet(packet.type(), packet.ciphertext()));
                                    try {
                                        RemotePhotoSession.Frame frame = RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), RemotePhotoSession.Frame.class);
                                        frame.message().verify(packet.id(), userId, deviceId, peerId, peerDevice, ChatEnvelope.Expiry.HOURS_24, packet.expiresAt(), null, Instant.now());
                                        received.add(frame);
                                        if (!localOwner) respond(frame);
                                    } finally { Arrays.fill(plaintext, (byte) 0); }
                                }
                                return response(new RemotePhotoSession.Delivery(incoming.peek()));
                            }
                            return response(snapshot);
                        }
                    } catch (Throwable failure) { error = failure; return new okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("{}"); }
                }
            });
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            ChatEngine.Account previous = engine.account();
            setField(engine, "account", new ChatEngine.Account(server.url("/").toString(), previous.handle(), userId, deviceId, "a".repeat(43), previous.expiresAt(), true));
            engine.groupApi().close();
            setField(engine, "api", syntheticApi(chain -> {
                String path = chain.request().url().encodedPath();
                if (path.equals("/account/type")) return syntheticResponse(chain.request(), 200, new RemotePhotoSession.AccountType(userId, localOwner ? "USER" : "ADMIN"));
                if (path.equals("/users/id/" + peerId)) return syntheticResponse(chain.request(), 200, other);
                if (path.equals("/remote-photos")) return syntheticResponse(chain.request(), 200, localOwner ? List.of(snapshot) : List.of());
                if (path.equals("/remote-photos/" + id) && chain.request().method().equals("DELETE")) {
                    declined.incrementAndGet(); return syntheticResponse(chain.request(), 204, null);
                }
                return syntheticResponse(chain.request(), 404, Map.of("error", "not_found"));
            }));
        }
        okhttp3.mockwebserver.MockResponse response(Object body) { return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(RelayApi.JSON.toJson(body)); }
        synchronized void queue(UUID request, String action, long photoId, long cursor, long offset, long total, boolean more, byte[] data) throws Exception {
            long deadline = Math.min(snapshot.expiresAt(), System.currentTimeMillis() + 50_000);
            ChatEnvelope message = new ChatEnvelope(1, UUID.randomUUID(), peerId, peerDevice, userId, deviceId, System.currentTimeMillis(), deadline, ChatEnvelope.Expiry.HOURS_24, "remote-photos", null);
            byte[] plaintext = RelayApi.JSON.toJson(new RemotePhotoSession.Frame(snapshot.id(), request, action, photoId, cursor, offset, total, more, data, message)).getBytes(StandardCharsets.UTF_8);
            try {
                SignalClient.Packet packet = remote.encrypt(userId, plaintext, Instant.now());
                incoming.add(new RemotePhotoSession.Packet(message.id(), peerId, peerDevice, deadline, packet.type(), packet.ciphertext()));
            } finally { Arrays.fill(plaintext, (byte) 0); }
        }
        void respond(RemotePhotoSession.Frame request) throws Exception {
            if (request.action().equals("LIST")) {
                long first = Math.min(40, request.cursor() - 1), last = Math.max(1, first - PhotoLibrary.PAGE_SIZE + 1);
                for (long photoId = first; photoId >= last; photoId--) queue(request.requestId(), "ENTRY", photoId, 0, 0, 0, false, thumbnail);
                queue(request.requestId(), "PAGE_END", 0, last, 0, 0, last > 1, null);
            } else if (request.action().equals("THUMB")) queue(request.requestId(), "THUMB", request.photoId(), 0, 0, 0, false, thumbnail);
            else if (request.action().equals("GET")) {
                originals.incrementAndGet();
                for (int offset = 0; offset < photo.length; offset += PhotoLibrary.CHUNK_SIZE) {
                    byte[] chunk = Arrays.copyOfRange(photo, offset, Math.min(photo.length, offset + PhotoLibrary.CHUNK_SIZE));
                    queue(request.requestId(), "CHUNK", request.photoId(), 0, offset, photo.length, offset + chunk.length < photo.length, chunk);
                }
            }
        }
        RemotePhotoSession.Prepared prepare() throws Exception {
            RemotePhotoSession.Prepared result = new RemotePhotoSession.Prepared(engine, peer, localOwner ? snapshot : null);
            okhttp3.OkHttpClient client = (okhttp3.OkHttpClient) readField(result.api, "client");
            setField(result.api, "client", client.newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build());
            return result;
        }
        RemotePhotoSession.Frame take() throws Exception {
            RemotePhotoSession.Frame frame = received.poll(20, java.util.concurrent.TimeUnit.SECONDS);
            if (error != null) throw new AssertionError(error);
            assertNotNull("Encrypted photo response did not arrive", frame); return frame;
        }
        @Override public void close() throws Exception {
            PhotoSharingService.endFor(userId, null);
            awaitPhotoCondition(() -> !PhotoSharingService.busy());
            server.shutdown(); Arrays.fill(thumbnail, (byte) 0);
        }
    }

    @Test public void remotePhotosPageAllAccessibleImagesAndReadOnlyTheOpenedOriginal() throws Exception {
        photoFixturePermission();
        List<android.net.Uri> fixtures = new ArrayList<>();
        try {
            for (int index = 0; index < 37; index++) fixtures.add(insertPhotoFixture(photo));
            Set<Long> expected = new HashSet<>(); for (var uri : fixtures) expected.add(android.content.ContentUris.parseId(uri));
            Set<Long> found = new HashSet<>(); long before = Long.MAX_VALUE;
            PhotoLibrary library = new PhotoLibrary(context);
            for (int pages = 0; pages < 100; pages++) {
                PhotoLibrary.Page page = library.page(before); assertTrue(page.ids().size() <= PhotoLibrary.PAGE_SIZE);
                for (long id : page.ids()) { assertTrue(id < before); assertTrue(found.add(id)); }
                if (!page.more()) break;
                assertTrue(page.cursor() < before); before = page.cursor();
            }
            assertTrue("Pagination must not cap the gallery to one page", found.containsAll(expected));
            long id = android.content.ContentUris.parseId(fixtures.get(0));
            byte[] thumbnail = library.thumbnail(id); assertTrue(thumbnail.length <= PhotoLibrary.THUMBNAIL_BYTES);
            try (PhotoLibrary.Opened original = library.open(id)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                while (original.position() < original.size) { byte[] chunk = original.next(); assertTrue(chunk.length <= PhotoLibrary.CHUNK_SIZE); bytes.write(chunk); }
                assertArrayEquals(photo, bytes.toByteArray());
            }
        } finally { for (var uri : fixtures) context.getContentResolver().delete(uri, null, null); }
    }

    @Test public void remotePhotosOwnerServiceWorksAfterClosingChatAndNotificationEndsAccess() throws Exception {
        signedInFixture(); photoFixturePermission();
        android.net.Uri image = insertPhotoFixture(photo);
        try (PhotoServer server = new PhotoServer(true); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ChatEngine.Account account = engine.account();
            setField(engine, "account", new ChatEngine.Account(account.origin(), account.handle(), account.userId(), account.deviceId(),
                    account.accessToken(), System.currentTimeMillis() + 120_000, true));
            RemotePhotoSession.Prepared prepared = server.prepare();
            assertTrue(prepared.deadline < server.snapshot.expiresAt());
            scenario.onActivity(activity -> { inject(activity); PhotoSharingService.start(activity, prepared); });
            assertEquals("HELLO", server.take().action());
            RemotePhotoSession active = PhotoSharingService.current(prepared.id.toString()); assertNotNull(active);
            scenario.moveToState(Lifecycle.State.CREATED);
            assertThrows(SecurityException.class, () -> vault.get("identity"));
            UUID query = UUID.randomUUID(); long imageId = android.content.ContentUris.parseId(image);
            server.queue(query, "GET", imageId, 0, 0, 0, false, null);
            ByteArrayOutputStream original = new ByteArrayOutputStream();
            for (;;) {
                RemotePhotoSession.Frame frame = server.take(); assertEquals("CHUNK", frame.action()); assertEquals(query, frame.requestId());
                assertEquals(original.size(), frame.offset()); original.write(frame.data()); if (!frame.more()) break;
            }
            assertArrayEquals("Background sharing must transfer the original bytes without reopening chat storage", photo, original.toByteArray());
            var notifications = context.getSystemService(android.app.NotificationManager.class).getActiveNotifications();
            android.app.Notification notification = Arrays.stream(notifications).filter(value -> value.getId() == PhotoSharingService.NOTIFICATION).findFirst().orElseThrow().getNotification();
            assertEquals("Photo sharing active", notification.extras.getString(android.app.Notification.EXTRA_TITLE));
            assertTrue((notification.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0);
            assertTrue(notification.actions[0].actionIntent.isImmutable()); assertEquals("End access", notification.actions[0].title.toString());
            assertEquals(notification.actions[0].actionIntent, notification.deleteIntent);
            notification.actions[0].actionIntent.send();
            awaitPhotoCondition(() -> active.ended() && !PhotoSharingService.busy());
            assertEquals(true, readField(prepared.vault, "closed"));
            assertThrows(SecurityException.class, () -> prepared.vault.get("identity"));
        } finally { context.getContentResolver().delete(image, null, null); }
    }

    @Test public void remotePhotosViewerShowsThumbnailsThenLoadsOnlyTheTappedPhoto() throws Exception {
        signedInFixture(); photoFixturePermission();
        try (PhotoServer server = new PhotoServer(false); ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            RemotePhotoSession.Prepared prepared = server.prepare();
            main.onActivity(activity -> { inject(activity); PhotoSharingService.start(activity, prepared); });
            awaitPhotoCondition(() -> PhotoSharingService.current(prepared.id.toString()) != null);
            try (ActivityScenario<RemotePhotosActivity> viewer = ActivityScenario.launch(new android.content.Intent(context, RemotePhotosActivity.class).putExtra("session", prepared.id.toString()))) {
                RemotePhotoSession active = PhotoSharingService.current(prepared.id.toString());
                awaitPhotoCondition(() -> active.photos().size() >= PhotoLibrary.PAGE_SIZE);
                assertEquals("Scrolling thumbnails must not upload originals", 0, server.originals.get());
                viewer.onActivity(activity -> photoSnapshot(activity, "62-remote-photos-grid"));
                viewer.onActivity(activity -> {
                    assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                    GridView grid = (GridView) readField(activity, "grid");
                    assertTrue(grid.performItemClick(grid.getChildAt(0), 0, grid.getAdapter().getItemId(0)));
                });
                awaitPhotoCondition(() -> active.fullImage() != null);
                assertEquals(1, server.originals.get());
                assertTrue(active.fullImage().getWidth() > 160);
                RemotePhotosActivity[] screen = new RemotePhotosActivity[1]; viewer.onActivity(activity -> screen[0] = activity);
                awaitPhotoCondition(() -> ((ImageView) readField(screen[0], "image")).getDrawable() != null);
                viewer.onActivity(activity -> { assertEquals(View.VISIBLE, ((View) readField(activity, "image")).getVisibility()); photoSnapshot(activity, "63-remote-photo-full"); });
                server.disconnected = true;
                awaitPhotoCondition(active::ended);
                awaitPhotoCondition(() -> active.fullImage() == null && active.photos().isEmpty());
                viewer.onActivity(activity -> invoke(activity, "finishSharing"));
            }
        }
    }

    @Test public void remotePhotosRoleGateAndDecliningApprovalNeverOpenTheGallery() throws Exception {
        signedInFixture(); photoFixturePermission();
        try (PhotoServer server = new PhotoServer(true); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertFalse(RemotePhotoSession.administrator(engine));
            assertThrows(SecurityException.class, () -> new RemotePhotoSession.Prepared(engine, peer, null));
            scenario.onActivity(this::inject);
            scenario.onActivity(activity -> {
                var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
                try { worker.submit(() -> invoke(activity, "photoRequestsOnce")).get(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Allow photo access?")), 5000));
            snapshot(scenario, "64-remote-photo-approval");
            scenario.onActivity(activity -> {
                assertEquals(false, readField(activity, "photoAdmin"));
                assertFalse(PhotoSharingService.busy());
                assertEquals("Allow", dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitPhotoCondition(() -> server.declined.get() == 1);
            assertFalse(PhotoSharingService.busy()); assertTrue(server.received.isEmpty());
        }
    }

    @Test public void remotePhotosSignOutAndSystemStopClearTheApprovedSession() throws Exception {
        photoFixturePermission();
        for (String reason : List.of("signout", "screen-off", "timeout")) {
            vault.unlock(); signedInFixture();
            try (PhotoServer server = new PhotoServer(true); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                RemotePhotoSession.Prepared prepared = server.prepare();
                scenario.onActivity(activity -> { inject(activity); PhotoSharingService.start(activity, prepared); });
                assertEquals("HELLO", server.take().action());
                RemotePhotoSession current = PhotoSharingService.current(prepared.id.toString());
                if (reason.equals("signout")) engine.logout();
                else {
                    Field serviceField = PhotoSharingService.class.getDeclaredField("active"); serviceField.setAccessible(true);
                    PhotoSharingService service = (PhotoSharingService) serviceField.get(null); assertNotNull(service);
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                        if (reason.equals("timeout")) service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                        else ((android.content.BroadcastReceiver) readField(service, "screenOff")).onReceive(service, new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
                    });
                }
                awaitPhotoCondition(() -> !PhotoSharingService.busy());
                assertTrue(current.ended()); assertNull(current.fullImage()); assertTrue(current.photos().isEmpty());
                assertThrows(SecurityException.class, () -> prepared.vault.get("identity"));
            }
            if (engine != null) engine.close();
            resetQaFixture(); createFixtureKey(AndroidVault.MASTER);
            vault = new AndroidVault(context); vault.unlock(); engine = new ChatEngine(vault);
        }
    }

    @Test public void remotePhotosMenuIsAvailableOnlyForTheCurrentAdminAccount() throws Exception {
        signedInFixture();
        try (PhotoServer server = new PhotoServer(false); ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
                try { worker.submit(() -> invoke(activity, "photoRequestsOnce")).get(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            scenario.onActivity(activity -> invoke(activity, "conversationMenu", new Class<?>[]{ChatEngine.Peer.class, View.class}, peer, root(activity)));
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Photos")), 5000));
            device.pressBack();
            scenario.onActivity(activity -> {
                setField(activity, "photoAdmin", false);
                invoke(activity, "conversationMenu", new Class<?>[]{ChatEngine.Peer.class, View.class}, peer, root(activity));
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Profile")), 5000));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.text("Photos"))); device.pressBack();
        }
    }

    @Test public void clearChatRemovesLocalMessagesAndOutboxWithoutRemovingIdentityOrReplayProtection() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        engine.renameContact(peer, "Private friend");
        List<ChatEngine.Entry> removed = engine.entries(peerId);
        byte[] oldCiphertext = vault.get("body/" + once.id());
        byte[] seen = Long.toString(once.expiresAt()).getBytes(StandardCharsets.US_ASCII);
        ChatEngine.Entry unrelated = entry(false, "Unrelated conversation", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", 0);
        UUID otherPeer = UUID.randomUUID();
        vault.transaction(() -> {
            write("entry/" + unrelated.id(), new ChatEngine.Entry(unrelated.id(), otherPeer, unrelated.expiresAt(), unrelated.expiry(), false, false, unrelated.state()));
            vault.put("seen/" + once.id(), seen);
            write("ack/" + once.id(), new ChatEngine.Ack(once.id(), once.expiresAt(), "delivered"));
            ChatEngine.Entry outgoing = removed.stream().filter(ChatEngine.Entry::outgoing).findFirst().orElseThrow();
            write("outbox/" + outgoing.id(), new ChatEngine.Outbox(new ChatEngine.Send(outgoing.id(), peerId, peerDevice,
                    outgoing.expiry(), outgoing.expiresAt(), 2, new byte[32], null), null));
            return null;
        });
        byte[] unrelatedBody = vault.get("body/" + unrelated.id());
        engine.clearChat(peerId);
        assertTrue(engine.entries(peerId).isEmpty());
        assertEquals(1, engine.entries(otherPeer).size());
        assertArrayEquals(unrelatedBody, vault.get("body/" + unrelated.id()));
        assertEquals(identity, engine.identityCode());
        assertTrue(engine.groupSignal().isVerified(peerId));
        assertEquals("Private friend", engine.privateName(peer));
        assertTrue(engine.peers().stream().anyMatch(value -> value.userId().equals(peerId)));
        assertArrayEquals(seen, vault.get("seen/" + once.id()));
        ChatEngine.Ack acknowledgement = RelayApi.JSON.fromJson(new String(vault.get("ack/" + once.id()), StandardCharsets.UTF_8), ChatEngine.Ack.class);
        assertEquals("delivered", acknowledgement.action()); assertEquals(once.expiresAt(), acknowledgement.expiresAt());
        for (ChatEngine.Entry entry : removed) {
            assertNull(vault.get("entry/" + entry.id())); assertNull(vault.get("body/" + entry.id())); assertNull(vault.get("outbox/" + entry.id()));
        }
        assertThrows(Exception.class, () -> vault.unseal(AndroidVault.contentAlias(once.expiresAt(), once.id()), oldCiphertext));
        engine.clearChat(peerId);
        assertThrows(SecurityException.class, () -> engine.clearChat(UUID.randomUUID()));
        assertArrayEquals(unrelatedBody, vault.get("body/" + unrelated.id()));
    }

    @Test public void clearChatMenuFollowsRemoveContactAndRequiresConfirmation() throws Exception {
        signedInFixture(); conversationsFixture();
        int count = engine.entries(peerId).size();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                ((EditText) readField(activity, "composer")).setText("Draft to clear");
                descendants(root(activity)).stream().filter(view -> "Conversation options".equals(view.getContentDescription())).findFirst().orElseThrow().performClick();
            });
            var clear = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Clear chat")), 5000);
            assertNotNull(clear);
            assertTrue(clear.getVisibleBounds().centerY() > device.findObject(androidx.test.uiautomator.By.text("Remove contact")).getVisibleBounds().centerY());
            clear.click();
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Clear chat?")), 5000));
            snapshot(scenario, "66-clear-chat-confirmation");
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            assertEquals(count, engine.entries(peerId).size());
            scenario.onActivity(activity -> {
                assertEquals("Draft to clear", ((EditText) readField(activity, "composer")).getText().toString());
                invoke(activity, "clearChatDialog", new Class<?>[]{ChatEngine.Peer.class}, peer);
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Just the two of you")), 10000));
            scenario.onActivity(activity -> {
                assertTrue(engine.entries(peerId).isEmpty()); assertEquals(peerId, readField(activity, "selectedPeer"));
                assertEquals("", ((EditText) readField(activity, "composer")).getText().toString());
                assertTrue(engine.groupSignal().isVerified(peerId));
            });
            snapshot(scenario, "67-cleared-chat");
        }
    }

    @Test public void peerPresenceExpiresAndRejectsUnverifiedChangedAndUnsolicitedIdentities() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        ContactPresence presence = engine.presence();
        presence.foreground(true); setField(engine, "realtimeReady", true);
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 5_000)}, List.of(contact), 1000, 1200);
        assertEquals("Typing", presence.label(peer, 1200));
        assertEquals("Online", presence.label(peer, 6000));
        assertEquals("", presence.label(peer, 13_000));
        setField(engine, "realtimeReady", false); assertEquals("", presence.label(peer, 1200)); setField(engine, "realtimeReady", true);
        assertEquals("", presence.label(new ChatEngine.Peer(peerId, UUID.randomUUID(), peer.identityKey(), "Other"), 1200));
        assertTrue(vault.names("presence").isEmpty());
        assertThrows(SecurityException.class, () -> presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_001, 0)}, List.of(contact), 1000, 1200));
        assertEquals("", presence.label(peer, 1200));
        assertThrows(SecurityException.class, () -> presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 5001)}, List.of(contact), 1000, 1200));
        assertThrows(SecurityException.class, () -> presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(), 1000, 1200));
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 1000, 0)}, List.of(contact), 1000, 2001);
        assertEquals("", presence.label(peer, 2001));
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(contact), 1000, 1200);
        vault.transaction(() -> { engine.groupSignal().forgetPeer(peerId); return null; });
        assertEquals("", presence.label(peer, 1200));
    }

    @Test public void typingContainsOnlyVerifiedRecipientAndExpiresWithoutSendingDraftText() throws Exception {
        signedInFixture(); conversationsFixture();
        ContactPresence presence = engine.presence(); presence.foreground(true); presence.conversation(peerId);
        presence.edited(peerId, true);
        long now = android.os.SystemClock.elapsedRealtime();
        ContactPresence.Update update = presence.update(now);
        assertEquals(peerId, update.typingTo()); assertTrue(update.typingForMillis() > 0 && update.typingForMillis() <= 5000);
        assertEquals(1, update.contacts().size()); assertEquals(peerId, update.contacts().get(0).userId());
        assertTrue(update.lastSeen());
        assertEquals(Set.of("contacts", "typingTo", "typingForMillis", "lastSeen"), RelayApi.JSON.toJsonTree(update).getAsJsonObject().keySet());
        assertNull(presence.update(now + 5001).typingTo());
        presence.edited(peerId, false); assertNull(presence.update(now).typingTo());
        presence.edited(peerId, true); presence.conversation(UUID.randomUUID()); assertNull(presence.update(now).typingTo());
        presence.foreground(false); assertNull(presence.update(now));
    }

    @Test public void presenceTransportDropsFailedAndBackgroundResponsesWithoutPersistingStatus() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        var mode = new java.util.concurrent.atomic.AtomicInteger();
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            assertEquals("/presence", chain.request().url().encodedPath());
            okio.Buffer body = new okio.Buffer(); chain.request().body().writeTo(body);
            ContactPresence.Update update = RelayApi.JSON.fromJson(body.readUtf8(), ContactPresence.Update.class);
            assertEquals(List.of(contact), update.contacts()); assertEquals(peerId, update.typingTo());
            if (mode.get() == 1) throw new IOException("Synthetic unavailable presence");
            if (mode.get() == 2) engine.presence().foreground(false);
            return syntheticResponse(chain.request(), 200, new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 5000)});
        }));
        setField(engine, "realtimeReady", true);
        ContactPresence presence = engine.presence(); presence.foreground(true); presence.conversation(peerId); presence.edited(peerId, true);
        presence.refresh(); assertEquals("Typing", presence.label(peer, android.os.SystemClock.elapsedRealtime()));
        mode.set(1); presence.refresh(); assertEquals("", presence.label(peer, android.os.SystemClock.elapsedRealtime()));
        mode.set(2); presence.refresh(); assertEquals("", presence.label(peer, android.os.SystemClock.elapsedRealtime()));
        assertTrue(vault.names("presence").isEmpty());
    }

    @Test public void offlineLastSeenIsBoundedFreshAndNeverInferredFromExpiredOnline() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        ContactPresence presence = engine.presence(); presence.foreground(true); setField(engine, "realtimeReady", true);
        for (long age : List.of(0L, 60_000L, 3_600_000L, 82_800_000L)) {
            presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 0, 0, age)}, List.of(contact), 1000, 1200);
            String expected = age == 0 ? "Last seen just now" : age == 60_000 ? "Last seen 1 min ago"
                    : age == 3_600_000 ? "Last seen 1 hour ago" : "Last seen 23 hours ago";
            assertEquals(expected, presence.label(peer, 1200));
            assertEquals("", presence.label(peer, 13_000));
        }
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 0, 0, 59_900L)}, List.of(contact), 1000, 1200);
        assertEquals("Last seen 1 min ago", presence.label(peer, 1200));
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 0, 0, ContactPresence.LAST_SEEN_LIFETIME - 100)}, List.of(contact), 1000, 1200);
        assertEquals("", presence.label(peer, 1200));
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 5000)}, List.of(contact), 1000, 1200);
        assertEquals("Typing", presence.label(peer, 1200)); assertEquals("Online", presence.label(peer, 6000));
        assertEquals("", presence.label(peer, 13_000));
        assertTrue(vault.names("presence").isEmpty()); assertTrue(vault.names("last-seen").isEmpty());
    }

    @Test public void offlineLastSeenRejectsInvalidUnverifiedAndStaleLocalStates() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        ContactPresence presence = engine.presence(); presence.foreground(true); setField(engine, "realtimeReady", true);
        for (ContactPresence.Status invalid : List.of(new ContactPresence.Status(contact, 0, 0),
                new ContactPresence.Status(contact, 0, 1, 0L), new ContactPresence.Status(contact, 0, 0, -1L),
                new ContactPresence.Status(contact, 0, 0, ContactPresence.LAST_SEEN_LIFETIME),
                new ContactPresence.Status(contact, 12_000, 0, 0L))) {
            assertThrows(SecurityException.class, () -> presence.accept(new ContactPresence.Status[]{invalid}, List.of(contact), 1000, 1200));
            assertEquals("", presence.label(peer, 1200));
        }
        ContactPresence.Status offline = new ContactPresence.Status(contact, 0, 0, 60_000L);
        assertThrows(SecurityException.class, () -> presence.accept(new ContactPresence.Status[]{offline}, List.of(), 1000, 1200));
        assertThrows(SecurityException.class, () -> presence.accept(new ContactPresence.Status[]{offline, offline}, List.of(contact), 1000, 1200));
        presence.accept(new ContactPresence.Status[]{offline}, List.of(contact), 1000, 1200);
        assertEquals("", presence.label(new ChatEngine.Peer(peerId, UUID.randomUUID(), peer.identityKey(), "Other"), 1200));
        setField(engine, "realtimeReady", false); assertEquals("", presence.label(peer, 1200)); setField(engine, "realtimeReady", true);
        presence.foreground(false); presence.foreground(true); assertEquals("", presence.label(peer, 1200));
        presence.accept(new ContactPresence.Status[]{offline}, List.of(contact), 1000, 1200);
        presence.disconnected(); assertEquals("", presence.label(peer, 1200));
        presence.accept(new ContactPresence.Status[]{offline}, List.of(contact), 1000, 1200);
        vault.transaction(() -> { engine.groupSignal().forgetPeer(peerId); return null; });
        assertEquals("", presence.label(peer, 1200));
    }

    @Test public void queuedSyncCannotRefreshBusyOrObsoleteAccountScreens() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            for (boolean busyScreen : List.of(true, false)) {
                TextView[] badge = new TextView[1];
                scenario.onActivity(activity -> {
                    badge[0] = (TextView) ((Map<?, ?>) readField(activity, "unreadLabels")).get(peerId);
                    badge[0].setText("unchanged");
                    var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
                    try { worker.submit(() -> invoke(activity, "syncOnce")).get(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    if (busyScreen) setField(activity, "busy", true);
                    else setField(activity, "screenGeneration", (int) readField(activity, "screenGeneration") + 1);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals("A queued sync must not touch a closing account screen", "unchanged", badge[0].getText().toString());
                    setField(activity, "busy", false);
                });
            }
        }
    }

    @Test public void contactStatusTimerDoesNotReadAClosedAccountVault() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                TextView status = (TextView) readField(activity, "contactStatus");
                status.setText("Online"); status.setVisibility(View.VISIBLE);
                engine.close();
                invoke(activity, "refreshContactStatus");
                assertEquals("", status.getText().toString());
                assertEquals(View.GONE, status.getVisibility());
            });
        }
    }

    @Test public void chatHeaderShowsOnlyNameUntilFreshPeerStatusIsAvailable() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.applyContactProfile(peerId, new ChatEngine.Profile(peerId, "friend_name", "Friend Public"));
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                assertNotNull(text(root(activity), "Friend Public"));
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                        && Set.of("@friend_name", "Connected", "Offline", "Working").contains(label.getText().toString())));
                TextView status = (TextView) readField(activity, "contactStatus");
                assertEquals(View.GONE, status.getVisibility());
                setField(engine, "realtimeReady", true);
                long now = android.os.SystemClock.elapsedRealtime();
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(contact), now, now);
                invoke(activity, "refreshContactStatus"); assertEquals("Online", status.getText().toString()); assertEquals(View.VISIBLE, status.getVisibility());
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 5000)}, List.of(contact), now, now);
                invoke(activity, "refreshContactStatus"); assertEquals("Typing", status.getText().toString());
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 0, 0, 82_800_000L)}, List.of(contact), now, now);
                invoke(activity, "refreshContactStatus"); assertEquals("Last seen 23 hours ago", status.getText().toString());
                assertEquals(View.VISIBLE, status.getVisibility());
                EditText input = (EditText) readField(activity, "composer"); input.requestFocus(); input.setText("Synthetic draft stays local");
                assertEquals(peerId, engine.presence().update(android.os.SystemClock.elapsedRealtime()).typingTo());
                long typingDeadline = (long) readField(engine.presence(), "typingUntil");
                invoke(activity, "render");
                assertEquals(typingDeadline, (long) readField(engine.presence(), "typingUntil"));
                input = (EditText) readField(activity, "composer");
                status = (TextView) readField(activity, "contactStatus");
                input.setText(""); assertNull(engine.presence().update(android.os.SystemClock.elapsedRealtime()).typingTo());
            });
            snapshot(scenario, "61-contact-last-seen");
            scenario.onActivity(activity -> {
                TextView status = (TextView) readField(activity, "contactStatus");
                assertEquals("Last seen 23 hours ago", status.getText().toString());
                assertTrue("Last seen must fit the chat header", status.getPaint().measureText(status.getText().toString())
                        <= status.getWidth() - status.getPaddingLeft() - status.getPaddingRight());
                engine.presence().disconnected(); invoke(activity, "refreshContactStatus"); assertEquals(View.GONE, status.getVisibility());
            });
        }
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
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label && "@friend_name".contentEquals(label.getText())));
                invoke(activity, "accountDialog");
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> "Save username".equals(view.getContentDescription())));
                assertEquals(2, descendants(dialog(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof EditText).count());
            });
            snapshot(scenario, "28-profile-editable-username");
            scenario.onActivity(activity -> {
                dialog(activity).dismiss();
                setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label && "@friend_name".contentEquals(label.getText())));
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
                assertHorizontallyCentered(greeting, viewport);
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
        assertEquals(AndroidVault.RECORD_VERSION, java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath())[0]);
        assertEquals(AndroidVault.RECORD_VERSION, vault.get(prefix + "body/" + once.id())[0]);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse(store.containsAlias(AndroidVault.MASTER));
        assertFalse(store.containsAlias(alias));
        assertTrue(store.containsAlias(AndroidVault.currentAlias(alias)));
        assertTrue(vault.restoreAccount(userId)); engine = new ChatEngine(vault);
        assertEquals(identity, engine.identityCode());
        ChatEngine.Entry retained = engine.entries(peerId).stream().filter(value -> value.id().equals(once.id())).findFirst().orElseThrow();
        assertEquals(once.expiresAt(), retained.expiresAt());
        assertEquals("DELIVERED", retained.state());
        assertEquals("This message is visible once.", engine.content(retained, true).envelope().text());
        assertNull(vault.get("body/" + once.id()));
        assertFalse(store.containsAlias(AndroidVault.currentAlias(alias)));
    }

    @Test public void phoneProtectedVaultAndRetainedContentMigrateWithoutChangingIdentityOrExpiry() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        String alias = AndroidVault.contentAlias(once.expiresAt(), once.id());
        byte[] plaintext = vault.unseal(alias, vault.get("body/" + once.id()));
        byte[] encrypted;
        try { encrypted = legacySeal(alias, plaintext, 2); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        vault.transaction(() -> { vault.put("body/" + once.id(), encrypted); vault.saveAccount(userId); return null; });
        legacyVaultFile(2);
        vault.unlock(); engine = new ChatEngine(vault);
        String prefix = AndroidVault.savedAccountPrefix(userId);
        assertNull(engine.account());
        assertEquals(AndroidVault.RECORD_VERSION, java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath())[0]);
        assertEquals(AndroidVault.RECORD_VERSION, vault.get(prefix + "body/" + once.id())[0]);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse(store.containsAlias(AndroidVault.phoneAlias(AndroidVault.MASTER)));
        assertFalse(store.containsAlias(AndroidVault.phoneAlias(alias)));
        assertTrue(store.containsAlias(AndroidVault.currentAlias(alias)));
        assertTrue(vault.restoreAccount(userId)); engine = new ChatEngine(vault);
        assertEquals(identity, engine.identityCode());
        ChatEngine.Entry retained = engine.entries(peerId).stream().filter(value -> value.id().equals(once.id())).findFirst().orElseThrow();
        assertEquals(once.expiresAt(), retained.expiresAt()); assertEquals("DELIVERED", retained.state());
        assertEquals("This message is visible once.", engine.content(retained, true).envelope().text());
        assertNull(vault.get("body/" + once.id())); assertFalse(store.containsAlias(AndroidVault.currentAlias(alias)));
    }

    @Test public void failedPhoneProtectedVaultMigrationKeepsItsFileAndKey() throws Exception {
        signedInFixture();
        legacyVaultFile(2);
        File target = new File(context.getNoBackupFilesDir(), "vault.bin");
        byte[] encrypted = java.nio.file.Files.readAllBytes(target.toPath());
        encrypted[encrypted.length - 1] ^= 1;
        java.nio.file.Files.write(target.toPath(), encrypted);
        assertThrows(Exception.class, vault::unlock);
        assertThrows(SecurityException.class, () -> vault.get("account"));
        assertArrayEquals(encrypted, java.nio.file.Files.readAllBytes(target.toPath()));
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertTrue(store.containsAlias(AndroidVault.phoneAlias(AndroidVault.MASTER)));
    }

    @Test public void missingPhoneProtectedVaultKeyCannotResetTheAccount() throws Exception {
        signedInFixture(); legacyVaultFile(2);
        File target = new File(context.getNoBackupFilesDir(), "vault.bin");
        byte[] encrypted = java.nio.file.Files.readAllBytes(target.toPath());
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        store.deleteEntry(AndroidVault.phoneAlias(AndroidVault.MASTER));
        assertThrows(SecurityException.class, vault::unlock);
        assertArrayEquals(encrypted, java.nio.file.Files.readAllBytes(target.toPath()));
        assertFalse(store.containsAlias(AndroidVault.phoneAlias(AndroidVault.MASTER)));
    }

    @Test public void failedPhoneProtectedMigrationKeepsOriginalContentAndKey() throws Exception {
        signedInFixture(); conversationsFixture();
        String alias = AndroidVault.contentAlias(once.expiresAt(), once.id());
        byte[] encrypted = legacySeal(alias, "synthetic old content".getBytes(StandardCharsets.UTF_8), 2);
        encrypted[encrypted.length - 1] ^= 1;
        vault.transaction(() -> { vault.put("body/" + once.id(), encrypted); return null; });
        byte[] before = java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath());
        assertThrows(Exception.class, () -> new ChatEngine(vault));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(new File(context.getNoBackupFilesDir(), "vault.bin").toPath()));
        assertArrayEquals(encrypted, vault.get("body/" + once.id()));
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertTrue(store.containsAlias(AndroidVault.phoneAlias(alias)));
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
                SecureSheet choices = dialog(activity); choices.getListView().performItemClick(choices.getListView().getChildAt(2), 2, 2);
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
            scenario.onActivity(activity -> {
                dialog(activity).dismiss(); engine.online = false; engine.presence().disconnected(); invoke(activity, "refreshContactStatus");
                assertEquals(View.GONE, ((TextView) readField(activity, "contactStatus")).getVisibility());
            });
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
                SecureSheet profile = dialog(activity);
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
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("New conversation")), 10_000));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.text("Unlock Vanishr")));
            assertFalse(device.hasObject(androidx.test.uiautomator.By.desc("Lock")));
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> assertNull(readField(activity, "engine")));
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("New conversation")), 10_000));
            scenario.onActivity(activity -> assertNotNull(readField(activity, "engine")));
            snapshot(scenario, "31-no-app-lock");
        }
    }
}