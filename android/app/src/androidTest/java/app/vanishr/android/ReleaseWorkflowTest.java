package app.vanishr.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inspector.WindowInspector;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import app.vanishr.crypto.ChatEnvelope;
import app.vanishr.crypto.ImageCipher;
import app.vanishr.crypto.SignalClient;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class ReleaseWorkflowTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);

    private List<View> descendants(View view) {
        List<View> result = new ArrayList<>();
        result.add(view);
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) result.addAll(descendants(group.getChildAt(index)));
        return result;
    }

    private List<View> visibleViews() {
        List<View> result = new ArrayList<>();
        for (View window : WindowInspector.getGlobalWindowViews()) {
            if (window.isShown()) result.addAll(descendants(window));
        }
        return result;
    }

    private void clickText(String value) {
        instrumentation.runOnMainSync(() -> {
            List<View> matches = visibleViews().stream().filter(view -> view.isShown() && view instanceof TextView text
                    && text.getText().toString().equals(value)).toList();
            assertFalse("Visible control is missing: " + value, matches.isEmpty());
            View selected = matches.get(matches.size() - 1);
            while (!selected.hasOnClickListeners() && selected.getParent() instanceof View parent) selected = parent;
            assertTrue("Control must be actionable: " + value, selected.performClick());
        });
        instrumentation.waitForIdleSync();
    }

    private void clickIcon(String description) throws Exception {
        eventually(() -> {
            java.util.concurrent.atomic.AtomicBoolean clicked = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                View control = visibleViews().stream().filter(view -> view.isShown() && view.isEnabled() && view.getWidth() > 0
                        && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                        .findFirst().orElse(null);
                if (control != null) clicked.set(control.performClick());
            });
            return clicked.get() ? Boolean.TRUE : null;
        });
        instrumentation.waitForIdleSync();
    }

    private void fill(String hint, String value) throws Exception {
        eventually(() -> {
            java.util.concurrent.atomic.AtomicBoolean filled = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                EditText input = visibleViews().stream().filter(view -> view.isShown() && view.getWidth() > 0 && view instanceof EditText field
                        && field.getHint() != null && field.getHint().toString().equals(hint))
                        .map(view -> (EditText) view).findFirst().orElse(null);
                if (input != null) { input.setText(value); filled.set(true); }
            });
            return filled.get() ? Boolean.TRUE : null;
        });
    }

    private void awaitText(String text) {
        boolean visible = device.wait(Until.hasObject(By.text(text)), 30_000);
        if (!visible) {
            try { capture("99-release-expected-screen-missing"); }
            catch (Exception ignored) { }
        }
        assertTrue("Expected screen text: " + text, visible);
    }

    private void signInSavedAccount(String handle, String password) throws Exception {
        awaitText("Continue with Google");
        instrumentation.runOnMainSync(() -> assertFalse("Returning to a retained account must not request device replacement",
                visibleViews().stream().anyMatch(view -> view instanceof TextView text && "Replace previous device".contentEquals(text.getText()))));
        fill("Username", handle);
        fill("Password", password);
        clickText("Sign in");
        awaitText("Chats");
    }

    private void verifyContactByUsername(String handle, String independentlyVerifiedNumber) throws Exception {
        clickIcon("Add contact");
        fill("Username", handle);
        clickText("Find");
        awaitText("Verify contact");
        instrumentation.runOnMainSync(() -> {
            String shown = visibleViews().stream().filter(view -> view instanceof TextView
                    && "Contact safety number".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                    .map(view -> ((TextView) view).getText().toString()).findFirst().orElseThrow();
            assertEquals(independentlyVerifiedNumber, shown);
            visibleViews().stream().filter(view -> view.isShown() && view instanceof CheckBox)
                    .map(view -> (CheckBox) view).findFirst().orElseThrow().setChecked(true);
        });
        clickText("Add contact");
        assertTrue("Contact verification must finish before opening the contact", device.wait(Until.gone(By.text("Verify contact")), 30_000));
        awaitText("@" + handle);
    }

    private void setContactDisplayName(String name, String username, String profileName) throws Exception {
        clickIcon("Conversation options");
        assertFalse("Duplicate contact-name action must not be offered", device.hasObject(By.text("Private contact name")));
        clickPopupItem("Profile");
        awaitText("Contact profile");
        awaitText(profileName);
        instrumentation.runOnMainSync(() -> {
            TextView contactUsername = visibleViews().stream().filter(view -> view.isShown() && view instanceof TextView
                    && "Contact username".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                    .map(view -> (TextView) view).findFirst().orElseThrow();
            assertEquals("@" + username, contactUsername.getText().toString());
            assertFalse("A contact's username is read-only", contactUsername instanceof EditText);
            assertFalse(visibleViews().stream().anyMatch(view -> view.isShown() && view instanceof TextView label
                    && ("Save username".contentEquals(label.getText()) || "My profile".contentEquals(label.getText()))));
        });
        fill("Display name", name);
        clickText("Save");
        assertTrue("The contact profile must close after saving", device.wait(Until.gone(By.text("Contact profile")), 30_000));
        awaitText(name);
    }

    private void clickPopupItem(String label) {
        UiObject2 item = device.wait(Until.findObject(By.text(label)), 10_000);
        assertNotNull("Popup menu item must be visible: " + label, item);
        item.click();
        instrumentation.waitForIdleSync();
    }

    private void awaitProfileName(String name) throws Exception {
        eventually(() -> {
            java.util.concurrent.atomic.AtomicBoolean present = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> present.set(visibleViews().stream().anyMatch(view -> view.isShown()
                    && view instanceof TextView text && !(view instanceof EditText) && name.contentEquals(text.getText()))));
            return present.get() ? Boolean.TRUE : null;
        });
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        eventually(() -> {
            java.util.concurrent.atomic.AtomicBoolean captured = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
            List<View> windows = WindowInspector.getGlobalWindowViews().stream().filter(View::isShown).toList();
            if (windows.isEmpty()) return;
            View decor = windows.get(windows.size() - 1);
            if (decor.getWidth() == 0 || decor.getHeight() == 0) return;
            assertTrue(decor.getLayoutParams() instanceof WindowManager.LayoutParams);
            assertTrue((((WindowManager.LayoutParams) decor.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            Bitmap image = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(image));
            File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "release-check");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            } catch (Exception failure) { throw new AssertionError("Could not capture the synthetic release screen"); }
            finally { image.recycle(); }
            captured.set(true);
            });
            return captured.get() ? Boolean.TRUE : null;
        });
    }

    private static <Value> Value eventually(Callable<Value> check) throws Exception {
        CompletableFuture<Value> result = new CompletableFuture<>();
        var timer = Executors.newSingleThreadScheduledExecutor();
        try {
            timer.scheduleWithFixedDelay(() -> {
                try { Value value = check.call(); if (value != null) result.complete(value); }
                catch (Exception | AssertionError failure) { result.completeExceptionally(failure); }
            }, 0, 750, TimeUnit.MILLISECONDS);
            return result.get(35, TimeUnit.SECONDS);
        } finally { timer.shutdownNow(); }
    }

    private record Peer(RelayApi api, ChatEngine.Token session, SignalClient crypto, String handle) implements AutoCloseable {
        @Override public String toString() { return "ReleaseTestPeer[redacted]"; }
        @Override public void close() throws Exception {
            try { api.call("POST", "/auth/logout", null, Void.class); }
            finally { api.close(); }
        }
    }

    private Peer peer(String origin) throws Exception {
        RelayApi api = new RelayApi(origin, null);
        String handle = "release_peer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ChatEngine.Token enrollment = api.call("POST", "/auth/register", new ChatEngine.Login(
            handle, UUID.randomUUID().toString(), null), ChatEngine.Token.class);
        SignalClient signal = new SignalClient(enrollment.userId(), new DeviceSecurityTest.MemoryVault());
        api.token(enrollment.accessToken());
        ChatEngine.Token session = api.call("POST", "/devices", new ChatEngine.DeviceRegistration(UUID.randomUUID(), signal.publicIdentity(), false), ChatEngine.Token.class);
        api.token(session.accessToken());
        api.call("POST", "/keys", Collections.singletonMap("keys", List.of(signal.generatePreKey(Instant.now()))), Void.class);
        return new Peer(api, session, signal, handle);
    }

    private UUID send(Peer sender, ChatEngine.Incoming recipient, String text, byte[] photo, ChatEnvelope.Expiry expiry) throws Exception {
        UUID id = UUID.randomUUID();
        UUID mediaId = photo == null ? null : UUID.randomUUID();
        long sentAt = System.currentTimeMillis();
        long deadline = sentAt + 180_000;
        ImageCipher.EncryptedImage image = photo == null ? null : ImageCipher.encrypt(mediaId, photo);
        ChatEnvelope.Attachment attachment = image == null ? null : new ChatEnvelope.Attachment(mediaId, image.key(), image.nonce(), "image/jpeg");
        ChatEnvelope envelope = new ChatEnvelope(1, id, sender.session().userId(), sender.session().deviceId(),
                recipient.senderId(), recipient.senderDeviceId(), sentAt, deadline, expiry, text, attachment);
        SignalClient.Packet packet = sender.crypto().encrypt(recipient.senderId(), RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
        try {
            if (image != null) sender.api().upload(mediaId, recipient.senderId(), recipient.senderDeviceId(), deadline, image.ciphertext());
            sender.api().call("POST", "/messages", new ChatEngine.Send(id, recipient.senderId(), recipient.senderDeviceId(), expiry,
                    deadline, packet.type(), packet.ciphertext(), mediaId), ChatEngine.Status.class);
        } finally { if (image != null) Arrays.fill(image.key(), (byte) 0); }
        return id;
    }

    private String status(Peer peer, UUID id) throws Exception {
        ChatEngine.Status[] values = peer.api().call("GET", "/messages/status?ids=" + id, null, ChatEngine.Status[].class);
        return values.length == 0 ? "MISSING" : values[0].state();
    }

    @Test public void signedReleaseUsesRealCredentialsAndExchangesEncryptedContentThroughItsUi() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit opt-in is required for synthetic accounts on the live relay", "true".equals(arguments.getString("releaseLive")));
        Context context = instrumentation.getTargetContext();
        assertEquals("app.vanishr.android", context.getPackageName());
        assertEquals("Do not substitute a QA/debug APK", 0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        String pin = arguments.getString("devicePin");
        assertTrue("A temporary dedicated-emulator credential is required", pin != null && pin.matches("[0-9]{6}"));
        assertTrue(context.getSystemService(KeyguardManager.class).isDeviceSecure());
        assertEquals(arguments.getString("relayOrigin"), BuildConfig.DEFAULT_RELAY_ORIGIN);
        device.wakeUp();
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard.isDeviceLocked()) {
            device.pressMenu();
            assertTrue("Device credential UI must be visible", device.wait(Until.hasObject(By.pkg("com.android.systemui")), 30_000));
            for (char digit : pin.toCharArray()) device.pressKeyCode(android.view.KeyEvent.KEYCODE_0 + digit - '0');
            device.pressEnter();
            eventually(() -> keyguard.isDeviceLocked() ? null : Boolean.TRUE);
        }
        try (Peer peer = peer(BuildConfig.DEFAULT_RELAY_ORIGIN);
             ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitText("Continue with Google");
            assertFalse("The phone is already unlocked", device.hasObject(By.text("Unlock Vanishr")));
            capture("02-release-sign-in");
            clickText("Sign up");
            String handle = "release_ui_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String password = UUID.randomUUID().toString();
            fill("Username", handle);
            fill("Password", password);
            clickText("Create account");
            awaitText("Chats");
            capture("03-release-chats");
            clickIcon("Profile");
            clickText("Verify identity");
            final String[] identity = new String[1];
            instrumentation.runOnMainSync(() -> identity[0] = visibleViews().stream().filter(view -> view instanceof TextView text
                    && "Device safety number".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                    .map(view -> ((TextView) view).getText().toString()).findFirst().orElseThrow());
                ChatEngine.Contact own = peer.api().call("GET", "/users/" + handle, null, ChatEngine.Contact.class);
                String canonical = own.userId() + ":" + own.identityKey();
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII));
                StringBuilder expected = new StringBuilder();
                for (byte value : digest) expected.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
                assertEquals(expected.toString(), identity[0].replace(" ", ""));
                peer.crypto().verifyPeer(own.userId(), Base64.getDecoder().decode(own.identityKey()));
            clickText("Close");
                clickIcon("Add contact");
                fill("Username", peer.handle());
                clickText("Find");
                awaitText("Verify contact");
            instrumentation.runOnMainSync(() -> visibleViews().stream().filter(view -> view.isShown() && view instanceof CheckBox)
                    .map(view -> (CheckBox) view).findFirst().orElseThrow().setChecked(true));
                clickText("Add contact");
                awaitText(peer.handle());
                clickText(peer.handle());
            fill("Message", "hello");
            clickIcon("Send");
            ChatEngine.Incoming outgoing;
            try {
                outgoing = eventually(() -> {
                    ChatEngine.Incoming[] messages = peer.api().call("GET", "/messages/pending", null, ChatEngine.Incoming[].class);
                    return messages.length == 0 ? null : messages[0];
                });
            } catch (Exception failure) {
                capture("04a-release-send-failed");
                throw failure;
            }
            ChatEnvelope hello = RelayApi.JSON.fromJson(new String(peer.crypto().decrypt(outgoing.senderId(),
                    new SignalClient.Packet(outgoing.type(), outgoing.ciphertext())), StandardCharsets.UTF_8), ChatEnvelope.class);
            assertEquals("hello", hello.text());
            hello.verify(outgoing.id(), outgoing.senderId(), outgoing.senderDeviceId(), peer.session().userId(), peer.session().deviceId(),
                    outgoing.expiry(), outgoing.expiresAt(), null, Instant.now());
            peer.api().call("POST", "/messages/" + outgoing.id() + "/delivered", null, Void.class);
            peer.api().call("POST", "/messages/" + outgoing.id() + "/read", null, Void.class);
            assertTrue(device.wait(Until.hasObject(By.desc("READ")), 30_000));
            capture("04-release-text-receipt");
            Bitmap bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(37, 112, 91));
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded));
            byte[] photo = encoded.toByteArray();
            bitmap.recycle();
            UUID imageId;
            try { imageId = send(peer, outgoing, null, photo, ChatEnvelope.Expiry.HOUR_1); }
            finally { Arrays.fill(photo, (byte) 0); }
            awaitText("Photo");
            clickText("Photo");
            assertTrue(device.wait(Until.hasObject(By.desc("Received photo")), 15_000));
            capture("05-release-photo");
            clickText("Done");
            assertEquals("READ", eventually(() -> "READ".equals(status(peer, imageId)) ? "READ" : null));
            UUID once = send(peer, outgoing, "One-time release verification", null, ChatEnvelope.Expiry.VIEW_ONCE);
            awaitText("View once");
            clickText("View once");
            awaitText("This message can only be opened once.");
            capture("06-release-once-confirmation");
            clickText("Not now");
            assertNotEquals("READ", status(peer, once));
            clickText("View once");
            clickText("Open");
            awaitText("One-time release verification");
            capture("07-release-once-content");
            assertEquals("READ", eventually(() -> "READ".equals(status(peer, once)) ? "READ" : null));
            scenario.moveToState(Lifecycle.State.CREATED);
            assertFalse(device.hasObject(By.text("One-time release verification")));
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitText(peer.handle());
            assertFalse(device.hasObject(By.text("Unlock Vanishr")));
            capture("08-release-background-return");
            assertFalse("Consumed view-once content must not return after reopening", device.hasObject(By.text("One-time release verification")));
            device.sleep();
            eventually(() -> keyguard.isDeviceLocked() ? Boolean.TRUE : null);
            assertThrows(SecurityException.class, () -> new AndroidVault(context).unlock());
            device.wakeUp();
            device.pressMenu();
            assertTrue("Only the phone credential screen should appear", device.wait(Until.hasObject(By.pkg("com.android.systemui")), 30_000));
            for (char digit : pin.toCharArray()) device.pressKeyCode(android.view.KeyEvent.KEYCODE_0 + digit - '0');
            device.pressEnter();
            eventually(() -> keyguard.isDeviceLocked() ? null : Boolean.TRUE);
            awaitText(peer.handle());
            assertFalse("Phone unlock must be sufficient", device.hasObject(By.text("Unlock Vanishr")));
            capture("08b-release-phone-unlock");
                clickIcon("Back");
                clickIcon("Profile");
                fill("Display name", "Release profile");
                clickIcon("Save name");
                awaitProfileName("Release profile");
                String renamedHandle = "renamed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
                fill("Username", renamedHandle);
                clickIcon("Save username");
                awaitText("@" + renamedHandle);
                assertEquals(own, peer.api().call("GET", "/users/" + renamedHandle, null, ChatEngine.Contact.class));
                assertEquals("Release profile", peer.api().call("GET", "/users/id/" + own.userId() + "/profile", null, ChatEngine.Profile.class).displayName());
                capture("09-release-profile");
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Cancel");
                awaitText("Chats");
                clickIcon("Profile");
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Sign out");
                awaitText("Continue with Google");
                assertFalse(device.hasObject(By.text(peer.handle())));
                capture("10-release-signed-out");
                UUID waitingMessage = send(peer, outgoing, "Queued while this account is signed out", null, ChatEnvelope.Expiry.HOUR_1);
                clickText("Sign up");
                String anotherHandle = "release_next_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                String anotherPassword = UUID.randomUUID().toString();
                fill("Username", anotherHandle);
                fill("Password", anotherPassword);
                clickText("Create account");
                awaitText("Chats");
                clickIcon("Profile");
                awaitText("@" + anotherHandle);
                fill("Display name", "Receiver profile");
                clickIcon("Save name");
                awaitProfileName("Receiver profile");
                clickText("Verify identity");
                final String[] switchedIdentity = new String[1];
                instrumentation.runOnMainSync(() -> switchedIdentity[0] = visibleViews().stream().filter(view -> view instanceof TextView
                    && "Device safety number".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                    .map(view -> ((TextView) view).getText().toString()).findFirst().orElseThrow());
                assertNotEquals("Switching accounts must not retain the previous identity", identity[0], switchedIdentity[0]);
                clickText("Close");
                capture("11-release-another-account");
                assertFalse(device.hasObject(By.text("Queued while this account is signed out")));
                ChatEngine.Contact anotherDevice = peer.api().call("GET", "/users/" + anotherHandle, null, ChatEngine.Contact.class);
                verifyContactByUsername(renamedHandle, identity[0]);
                clickText("@" + renamedHandle);
                setContactDisplayName("xyz", renamedHandle, "Release profile");
                capture("15-release-contact-display-name");
                clickIcon("Back");
                clickIcon("Profile");
                awaitText("Receiver profile");
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Sign out");
                awaitText("Continue with Google");
                signInSavedAccount(renamedHandle, password);
                assertEquals(own, peer.api().call("GET", "/users/" + renamedHandle, null, ChatEngine.Contact.class));
                clickIcon("Profile");
                awaitText("Release profile");
                clickText("Verify identity");
                instrumentation.runOnMainSync(() -> assertEquals(identity[0], visibleViews().stream().filter(view -> view instanceof TextView
                    && "Device safety number".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                    .map(view -> ((TextView) view).getText().toString()).findFirst().orElseThrow()));
                clickText("Close");
                clickText(peer.handle());
                awaitText("Queued while this account is signed out");
                assertEquals("READ", eventually(() -> "READ".equals(status(peer, waitingMessage)) ? "READ" : null));
                assertFalse(device.hasObject(By.text("One-time release verification")));
                clickIcon("Back");
                verifyContactByUsername(anotherHandle, switchedIdentity[0]);
                clickText("@" + anotherHandle);
                setContactDisplayName("abc", anotherHandle, "Receiver profile");
                fill("Message", "Account-switch delivery");
                clickIcon("Send");
                assertTrue("The message must reach the relay before sender sign-out", device.wait(Until.hasObject(By.desc("QUEUED")), 30_000));
                capture("12-release-waiting-for-other-account");
                clickIcon("Back");
                clickIcon("Profile");
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Sign out");
                signInSavedAccount(anotherHandle, anotherPassword);
                assertEquals(anotherDevice, peer.api().call("GET", "/users/" + anotherHandle, null, ChatEngine.Contact.class));
                awaitText("xyz");
                clickText("xyz");
                awaitText("Account-switch delivery");
                capture("13-release-message-after-account-switch");
                clickIcon("Back");
                clickIcon("Profile");
                awaitText("Receiver profile");
                String renamedOtherHandle = "receiver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
                fill("Username", renamedOtherHandle);
                clickIcon("Save username");
                awaitText("@" + renamedOtherHandle);
                assertEquals(anotherDevice, peer.api().call("GET", "/users/" + renamedOtherHandle, null, ChatEngine.Contact.class));
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Sign out");
                signInSavedAccount(renamedHandle, password);
                awaitText("abc");
                clickText("abc");
                awaitText("Account-switch delivery");
                awaitText("@" + renamedOtherHandle);
                assertTrue(device.hasObject(By.text("abc")));
                assertTrue("The sender's retained copy must show the receiver's receipt", device.wait(Until.hasObject(By.desc("READ")), 30_000));
                capture("14-release-retained-sender-copy");
                clickIcon("Conversation options");
                clickPopupItem("Profile");
                awaitText("Contact profile");
                awaitText("Receiver profile");
                clickText("Use profile name");
                assertTrue("Contact profile must close after restoring the profile name", device.wait(Until.gone(By.text("Contact profile")), 30_000));
                awaitText("Receiver profile");
                assertEquals("Release profile", peer.api().call("GET", "/users/id/" + own.userId() + "/profile", null, ChatEngine.Profile.class).displayName());
                clickIcon("Back");
                clickIcon("Profile");
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Sign out");
                awaitText("Continue with Google");
        }
    }
}