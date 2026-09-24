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
import app.vanishr.crypto.ProfileEnvelope;
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
        if (description.equals("Profile")) { clickIcon("My profile"); return; }
        if (description.equals("Add contact")) { clickIcon("New conversation"); awaitText("New conversation"); clickText("Add contact"); return; }
        eventually(() -> {
            java.util.concurrent.atomic.AtomicBoolean clicked = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                View control = visibleViews().stream().filter(view -> view.isShown() && view.isEnabled() && view.getWidth() > 0
                        && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                        .findFirst().orElse(null);
                if (control != null) {
                    EditText draft = "Send".equals(description) ? visibleViews().stream().filter(view -> view.isShown() && view instanceof EditText input
                        && "Message".contentEquals(input.getHint() == null ? "" : input.getHint())).map(view -> (EditText) view).findFirst().orElse(null) : null;
                    boolean performed = control.performClick();
                    clicked.set(performed && (!"Send".equals(description) || draft != null && draft.length() == 0));
                }
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
        boolean visible = device.wait(Until.hasObject(text.equals("Chats") ? By.desc("New conversation") : By.text(text)), 30_000);
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

    private void signOutFromChats() throws Exception {
        clickIcon("Profile"); clickText("Sign out"); awaitText("Sign out of this device?"); clickText("Sign out"); awaitText("Continue with Google");
    }

    private void acceptGroupConsent(String title) {
        awaitText(title);
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> visibleViews().stream().filter(view -> view.isShown() && view.isEnabled() && view instanceof CheckBox)
                .map(view -> (CheckBox)view).findFirst().orElseThrow().setChecked(true));
    }

    private void groupWorkflow(String ownerHandle,String ownerPassword,String memberHandle,String memberPassword) throws Exception {
        clickIcon("New group"); fill("Group name","Release group"); acceptGroupConsent("New group"); clickText("Create");
        awaitText("Invite members"); clickPopupItem("Receiver profile (@"+memberHandle+")");
        eventually(() -> {
            var selected = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> selected.set(visibleViews().stream().anyMatch(view -> view.isShown()
                && view instanceof android.widget.CheckedTextView choice && choice.isChecked()
                && ("Receiver profile (@" + memberHandle + ")").contentEquals(choice.getText()))));
            return selected.get() ? Boolean.TRUE : null;
        });
        clickPopupItem("Invite");
        awaitText("Group info"); awaitText("2 / 200 members"); capture("16-group-created"); clickText("Done");
        clickIcon("Back"); signOutFromChats(); signInSavedAccount(memberHandle,memberPassword);
        awaitText("Release group"); clickText("Release group"); acceptGroupConsent("Group invitation"); clickText("Accept");
        awaitText("Your private group"); capture("17-group-accepted"); clickIcon("Back"); signOutFromChats();
        signInSavedAccount(ownerHandle,ownerPassword); clickText("Release group");
        awaitText("2 members"); fill("Message","Encrypted group hello"); clickIcon("Send");
        awaitText("0 delivered / 0 of 1 read"); capture("18-group-sent"); clickIcon("Back"); signOutFromChats();
        signInSavedAccount(memberHandle,memberPassword); clickText("Release group"); awaitText("Encrypted group hello");
        fill("Message","Encrypted group reply"); clickIcon("Send"); awaitText("0 delivered / 0 of 1 read"); capture("19-group-received");
        clickIcon("Back"); signOutFromChats(); signInSavedAccount(ownerHandle,ownerPassword); clickText("Release group");
        awaitText("Encrypted group reply"); awaitText("1 delivered / 1 of 1 read");
        clickIcon("Conversation options"); clickPopupItem("Group info"); awaitText("Group info");
        clickIcon("Remove Receiver profile"); awaitText("Remove member?"); clickText("Remove"); awaitText("1 / 200 members");
        clickText("Done"); clickIcon("Back"); signOutFromChats(); signInSavedAccount(memberHandle,memberPassword);
        assertTrue("Removed membership must disappear after synchronization",device.wait(Until.gone(By.text("Release group")),30_000));
        signOutFromChats(); signInSavedAccount(ownerHandle,ownerPassword); clickText("Release group");
        clickIcon("Conversation options"); clickPopupItem("Group info"); awaitText("Group info"); awaitText("Close group");
        clickText("Close group"); awaitText("Close group?"); clickText("Close group");
        awaitText("Chats"); assertFalse(device.hasObject(By.text("Release group"))); capture("20-group-closed");
    }

    private void verifyContactByUsername(String handle, String independentlyVerifiedNumber, String displayName) throws Exception {
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
        awaitText(displayName);
        assertFalse(device.hasObject(By.text("@" + handle)));
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
        Bundle arguments = InstrumentationRegistry.getArguments();
        boolean photoAdmin = "true".equals(arguments.getString("releaseRemotePhotos"));
        String handle = photoAdmin ? arguments.getString("photoAdminHandle") : "release_peer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String password = photoAdmin ? arguments.getString("photoAdminPassword") : UUID.randomUUID().toString();
        if (photoAdmin) assertTrue("Only a provisioned synthetic admin may be used", handle != null && handle.matches("release_photo_[0-9a-f]{16}"));
        ChatEngine.Token enrollment = api.call("POST", photoAdmin ? "/auth/login" : "/auth/register", new ChatEngine.Login(handle, password, null), ChatEngine.Token.class);
        SignalClient signal = new SignalClient(enrollment.userId(), new DeviceSecurityTest.MemoryVault());
        api.token(enrollment.accessToken());
        ChatEngine.Token session = api.call("POST", "/devices", new ChatEngine.DeviceRegistration(UUID.randomUUID(), signal.publicIdentity(), false), ChatEngine.Token.class);
        api.token(session.accessToken());
        api.call("POST", "/keys", Collections.singletonMap("keys", List.of(signal.generatePreKey(Instant.now()))), Void.class);
        return new Peer(api, session, signal, handle);
    }

    private void clearChatWorkflow(Peer peer, ChatEngine.Contact own) throws Exception {
        clickIcon("Conversation options"); clickPopupItem("Clear chat"); awaitText("Clear chat?");
        capture("29-release-clear-chat-confirmation"); clickText("Cancel");
        assertTrue("Cancel keeps the chat history", device.hasObject(By.text("hello")));
        clickIcon("Conversation options"); clickPopupItem("Clear chat"); awaitText("Clear chat?"); clickText("Clear");
        awaitText("Just the two of you"); capture("30-release-cleared-chat");
        assertEquals(own, peer.api().call("GET", "/users/id/" + own.userId(), null, ChatEngine.Contact.class));
        fill("Message", "Message after clearing"); clickIcon("Send");
        ChatEngine.Incoming message = eventually(() -> {
            ChatEngine.Incoming[] pending = peer.api().call("GET", "/messages/pending", null, ChatEngine.Incoming[].class);
            return pending.length == 0 ? null : pending[0];
        });
        byte[] plaintext = peer.crypto().decrypt(own.userId(), new SignalClient.Packet(message.type(), message.ciphertext()));
        try { assertEquals("Message after clearing", RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ChatEnvelope.class).text()); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        peer.api().call("POST", "/messages/" + message.id() + "/read", null, Void.class);
        assertTrue(device.wait(Until.hasObject(By.desc("READ")), 30_000));
    }

    private final class PhotoLink implements AutoCloseable {
        final RelayApi api;
        final SignalClient signal;
        final ChatEngine.Contact own;
        final Peer peer;
        final UUID id = UUID.randomUUID();
        RemotePhotoSession.Session session;
        UUID acknowledged;
        PhotoLink(Peer peer, ChatEngine.Contact own) throws Exception {
            this.peer = peer; this.own = own;
            api = new RelayApi(peer.api().origin(), peer.session().accessToken());
            signal = peer.crypto().isolatedSession(new DeviceSecurityTest.MemoryVault());
            signal.verifyPeer(own.userId(), Base64.getDecoder().decode(own.identityKey()));
            var connected = new java.util.concurrent.atomic.AtomicBoolean();
            api.photoEvents(() -> { }, connected::set);
            eventually(() -> connected.get() ? Boolean.TRUE : null);
            RemotePhotoSession.AccountType role = api.call("GET", "/account/type", null, RemotePhotoSession.AccountType.class);
            assertEquals("ADMIN", role.userType()); assertEquals(peer.session().userId(), role.userId());
            session = api.call("POST", "/remote-photos", new RemotePhotoSession.Start(id, own, signal.generatePreKey(Instant.now())), RemotePhotoSession.Session.class);
            assertFalse(session.accepted());
        }
        void send(UUID query, String action, long photo, long cursor) throws Exception {
            long now = System.currentTimeMillis(), deadline = Math.min(session.expiresAt(), now + 50_000);
            ChatEnvelope message = new ChatEnvelope(1, UUID.randomUUID(), peer.session().userId(), peer.session().deviceId(), own.userId(), own.deviceId(),
                    now, deadline, ChatEnvelope.Expiry.HOURS_24, "remote-photos", null);
            byte[] plaintext = RelayApi.JSON.toJson(new RemotePhotoSession.Frame(id, query, action, photo, cursor, 0, 0, false, null, message)).getBytes(StandardCharsets.UTF_8);
            try {
                SignalClient.Packet encrypted = signal.encrypt(own.userId(), plaintext, Instant.now());
                RemotePhotoSession.Delivery delivery = api.call("POST", "/remote-photos/" + id + "/exchange",
                        new RemotePhotoSession.Exchange(acknowledged, new RemotePhotoSession.Send(message.id(), deadline, encrypted.type(), encrypted.ciphertext())), RemotePhotoSession.Delivery.class);
                assertNull("A request is sent only after acknowledging the previous response", delivery.packet());
            } finally { Arrays.fill(plaintext, (byte) 0); }
        }
        RemotePhotoSession.Frame receive() throws Exception {
            RemotePhotoSession.Packet packet = eventually(() -> api.call("POST", "/remote-photos/" + id + "/exchange",
                    new RemotePhotoSession.Exchange(acknowledged, null), RemotePhotoSession.Delivery.class).packet());
            assertEquals(own.userId(), packet.senderId()); assertEquals(own.deviceId(), packet.senderDeviceId());
            byte[] plaintext = signal.decrypt(own.userId(), new SignalClient.Packet(packet.type(), packet.ciphertext()));
            try {
                RemotePhotoSession.Frame frame = RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), RemotePhotoSession.Frame.class);
                assertEquals(id, frame.sessionId());
                frame.message().verify(packet.id(), own.userId(), own.deviceId(), peer.session().userId(), peer.session().deviceId(),
                        ChatEnvelope.Expiry.HOURS_24, packet.expiresAt(), null, Instant.now());
                acknowledged = packet.id(); return frame;
            } finally { Arrays.fill(plaintext, (byte) 0); }
        }
        @Override public void close() throws Exception {
            try { api.call("DELETE", "/remote-photos/" + id, null, Void.class); }
            finally { api.close(); }
        }
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

    private ProfileEnvelope awaitProfilePacket(Peer peer, UUID sender, ProfileEnvelope.Action action, UUID requestId) throws Exception {
        return eventually(() -> {
            ProfileEnvelope matching = null;
            for (ProfilePhotos.Packet packet : peer.api().call("GET", "/profile/packets", null, ProfilePhotos.Packet[].class)) {
                assertEquals(sender, packet.senderId());
                byte[] plaintext = peer.crypto().decrypt(sender, new SignalClient.Packet(packet.type(), packet.ciphertext()));
                ProfileEnvelope envelope;
                try { envelope = RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ProfileEnvelope.class); }
                finally { Arrays.fill(plaintext, (byte) 0); }
                envelope.verify(packet.id(), sender, packet.senderDeviceId(), peer.session().userId(), peer.session().deviceId(), packet.expiresAt(), Instant.now());
                peer.api().call("DELETE", "/profile/packets/" + packet.id(), null, Void.class);
                if (envelope.action() == action && (requestId == null || requestId.equals(envelope.requestId()))) matching = envelope;
            }
            return matching;
        });
    }

    private void sendProfilePacket(Peer peer, ChatEngine.Contact recipient, ProfileEnvelope.Action action,
                                   UUID requestId, long revision, byte[] photo, long deadline) throws Exception {
        UUID id = action == ProfileEnvelope.Action.REQUEST ? requestId : UUID.randomUUID();
        ChatEnvelope context = new ChatEnvelope(1, id, peer.session().userId(), peer.session().deviceId(), recipient.userId(),
                recipient.deviceId(), System.currentTimeMillis(), deadline, ChatEnvelope.Expiry.HOURS_24, "profile-photo", null);
        byte[] plaintext = RelayApi.JSON.toJson(new ProfileEnvelope(1, action, requestId, revision, photo, context)).getBytes(StandardCharsets.UTF_8);
        SignalClient.Packet encrypted;
        try { encrypted = peer.crypto().encrypt(recipient.userId(), plaintext, Instant.now()); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        peer.api().call("POST", "/profile/packets", new ProfilePhotos.Send(id, recipient.userId(), recipient.deviceId(), deadline,
                encrypted.type(), encrypted.ciphertext()), Void.class);
    }

    private void awaitPhotoAvatar(boolean expected) throws Exception {
        eventually(() -> {
            var visible = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> visible.set(visibleViews().stream().anyMatch(view -> view.isShown()
                    && view instanceof android.widget.ImageView image && image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)));
            return visible.get() == expected ? Boolean.TRUE : null;
        });
    }

    private void presenceWorkflow(ActivityScenario<MainActivity> scenario, Peer peer, ChatEngine.Contact own) throws Exception {
        var connected = new java.util.concurrent.atomic.AtomicBoolean();
        try (RelayApi connection = new RelayApi(peer.api().origin(), peer.session().accessToken())) {
            connection.events(() -> { }, connected::set);
            eventually(() -> connected.get() ? Boolean.TRUE : null);
            eventually(() -> {
                ContactPresence.Status[] status = peer.api().presence(new ContactPresence.Update(List.of(own), null, 0));
                return Arrays.stream(status).anyMatch(value -> value.peer().equals(own) && value.onlineForMillis() > 0) ? Boolean.TRUE : null;
            });
            awaitText("Online"); capture("26-release-peer-online");
            peer.api().presence(new ContactPresence.Update(List.of(own), own.userId(), 5000));
            assertTrue("Peer typing must be visible in this chat", device.wait(Until.hasObject(By.text("Typing")), 7000));
            capture("27-release-peer-typing");
            peer.api().presence(new ContactPresence.Update(List.of(own), null, 0));
            awaitText("Online");
            instrumentation.runOnMainSync(() -> {
                EditText input = visibleViews().stream().filter(view -> view instanceof EditText field && "Message".contentEquals(field.getHint()))
                        .map(view -> (EditText) view).findFirst().orElseThrow();
                input.requestFocus(); input.setText("Unsent presence fixture");
            });
            eventually(() -> {
                ContactPresence.Status[] status = peer.api().presence(new ContactPresence.Update(List.of(own), null, 0));
                return Arrays.stream(status).anyMatch(value -> value.peer().equals(own) && value.typingForMillis() > 0) ? Boolean.TRUE : null;
            });
            fill("Message", "");
            assertEquals(0, peer.api().call("GET", "/messages/pending", null, ChatEngine.Incoming[].class).length);
        }
        assertTrue("Disconnected peer must show last seen", device.wait(Until.hasObject(By.textStartsWith("Last seen ")), 15_000));
        assertFalse(device.hasObject(By.text("Online")));
        assertFalse(device.hasObject(By.text("Typing")));
        assertFalse(device.hasObject(By.text("Connected")));
        capture("28-release-peer-last-seen");
        connected.set(false);
        try (RelayApi connection = new RelayApi(peer.api().origin(), peer.session().accessToken())) {
            connection.events(() -> { }, connected::set);
            eventually(() -> connected.get() ? Boolean.TRUE : null);
            scenario.moveToState(Lifecycle.State.CREATED);
            eventually(() -> {
                ContactPresence.Status[] statuses = peer.api().presence(new ContactPresence.Update(List.of(own), null, 0));
                return Arrays.stream(statuses).anyMatch(value -> value.peer().equals(own) && value.onlineForMillis() == 0
                        && value.typingForMillis() == 0 && value.lastSeenAgoMillis() != null && value.lastSeenAgoMillis() >= 0
                        && value.lastSeenAgoMillis() < ContactPresence.LAST_SEEN_LIFETIME) ? Boolean.TRUE : null;
            });
        } finally { scenario.moveToState(Lifecycle.State.RESUMED); }
        awaitText(peer.handle());
    }

    private void profilePhotoWorkflow(Peer peer, ChatEngine.Contact own) throws Exception {
        ProfileEnvelope request = awaitProfilePacket(peer, own.userId(), ProfileEnvelope.Action.REQUEST, null);
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(43, 121, 103));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes)); bitmap.recycle();
        byte[] photo = bytes.toByteArray();
        long deadline = Math.min(request.message().expiresAt(), System.currentTimeMillis() + 180_000);
        try {
            UUID mutualRequest = UUID.randomUUID();
            sendProfilePacket(peer, own, ProfileEnvelope.Action.REQUEST, mutualRequest, 0, null, deadline);
            ProfileEnvelope response = awaitProfilePacket(peer, own.userId(), ProfileEnvelope.Action.UPDATE, mutualRequest);
            assertNull("The owner's unset photo must be returned only as an encrypted empty update", response.photo());
            sendProfilePacket(peer, own, ProfileEnvelope.Action.UPDATE, request.requestId(), 1, photo, deadline);
            awaitPhotoAvatar(true); capture("21-release-private-profile-photo");
            assertEquals(0, peer.api().call("GET", "/messages/pending", null, ChatEngine.Incoming[].class).length);
            var publicProfile = peer.api().call("GET", "/users/id/" + own.userId() + "/profile", null, java.util.Map.class);
            assertFalse(publicProfile.containsKey("photo")); assertFalse(publicProfile.containsKey("photoUrl"));
            sendProfilePacket(peer, own, ProfileEnvelope.Action.UPDATE, request.requestId(), 2, null, deadline);
            awaitPhotoAvatar(false); capture("22-release-profile-photo-removed");
        } finally { Arrays.fill(photo, (byte) 0); }
    }

    private void notificationWorkflow(ActivityScenario<MainActivity> scenario, Peer peer, ChatEngine.Incoming previous) throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue("The signed test requires configured Firebase messaging", !BuildConfig.FIREBASE_APP_ID.isEmpty()
            && !BuildConfig.FIREBASE_API_KEY.isEmpty() && !BuildConfig.FIREBASE_PROJECT_ID.isEmpty() && !BuildConfig.FIREBASE_SENDER_ID.isEmpty());
        if (android.os.Build.VERSION.SDK_INT >= 33) assertEquals("The app's permission dialog must have granted notifications",
            android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
        clickIcon("Back"); awaitText("Chats");
        scenario.moveToState(Lifecycle.State.CREATED);
        ChatEngine.Account account;
        try (AndroidVault stored = new AndroidVault(context)) {
            stored.unlock(); byte[] encoded = stored.get("account");
            try { account = RelayApi.JSON.fromJson(new String(encoded, StandardCharsets.UTF_8), ChatEngine.Account.class); }
            finally { Arrays.fill(encoded, (byte) 0); }
        }
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        assertFalse("Default-on behavior must not depend on a manually saved notification choice", preferences.contains("notifications"));
        android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
        notifications.cancelAll();
        try (RelayApi registration = new RelayApi(account.origin(), account.accessToken())) {
            scenario.moveToState(Lifecycle.State.RESUMED); awaitText("Chats");
            clickIcon("Profile"); awaitText("My profile");
            instrumentation.runOnMainSync(() -> {
                List<android.widget.CompoundButton> controls = visibleViews().stream().filter(view -> view.isShown() && view instanceof android.widget.CompoundButton)
                    .map(view -> (android.widget.CompoundButton) view).toList();
                assertEquals("My profile must have exactly one notification toggle", 1, controls.size());
                android.widget.CompoundButton control = controls.get(0);
                assertTrue("Notifications must be available in the signed app", control.isEnabled());
                assertTrue("Notifications must already be on without toggling the setting", control.isChecked());
            });
            assertTrue("Notification registration must finish before testing provider delivery", device.wait(Until.hasObject(By.text("Notifications ready")), 60_000));
            clickText("Close"); awaitText("Chats");
            scenario.moveToState(Lifecycle.State.CREATED);
            try {
                UUID messageId = UUID.randomUUID();
                long created = System.currentTimeMillis(); long deadline = created + 180_000;
                ChatEnvelope envelope = new ChatEnvelope(1, messageId, peer.session().userId(), peer.session().deviceId(), previous.senderId(), previous.senderDeviceId(),
                        created, deadline, ChatEnvelope.Expiry.HOUR_1, "Notification tap verification", null);
                byte[] plaintext = RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
                SignalClient.Packet packet;
                try { packet = peer.crypto().encrypt(previous.senderId(), plaintext, Instant.now()); }
                finally { Arrays.fill(plaintext, (byte) 0); }
                ChatEngine.Send message = new ChatEngine.Send(messageId, previous.senderId(), previous.senderDeviceId(), ChatEnvelope.Expiry.HOUR_1,
                        deadline, packet.type(), packet.ciphertext(), null);
                var nextSend = new java.util.concurrent.atomic.AtomicLong();
                eventually(() -> {
                    if (Arrays.stream(notifications.getActiveNotifications()).anyMatch(notification -> "vanishr-new".equals(notification.getTag()))) return Boolean.TRUE;
                    long now = System.currentTimeMillis();
                    if (now >= nextSend.get()) { nextSend.set(now + 5000); peer.api().call("POST", "/messages", message, ChatEngine.Status.class); }
                    return null;
                });
                assertTrue(device.openNotification());
                UiObject2 alert = device.wait(Until.findObject(By.text("New message")), 10_000);
                assertNotNull("Actual FCM delivery must produce the generic notification", alert);
                assertTrue(Arrays.stream(notifications.getActiveNotifications()).anyMatch(notification -> "vanishr-new".equals(notification.getTag())));
                alert.click();
                awaitText("Notification tap verification");
                assertTrue("Notification tap must open a conversation, not its list", device.hasObject(By.desc("Conversation options")));
                assertEquals("READ", eventually(() -> "READ".equals(status(peer, messageId)) ? "READ" : null));
                capture("24-release-fcm-notification-chat");
            } finally {
                assertTrue(preferences.edit().putBoolean("notifications", false).commit());
                registration.call("DELETE", "/devices/push", null, Void.class);
                notifications.cancelAll();
            }
        }
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
        if ("true".equals(arguments.getString("releasePush")) && android.os.Build.VERSION.SDK_INT >= 33)
            assertEquals("The dedicated permission fixture must start without notification permission", android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
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
            if (android.os.Build.VERSION.SDK_INT >= 33 && !BuildConfig.FIREBASE_APP_ID.isEmpty()
                    && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                UiObject2 allow = device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/permission_allow_button"))), 30_000);
                assertNotNull("Default-on notifications must request Android permission after sign-in", allow);
                device.waitForIdle();
                allow.click();
                try {
                    assertTrue("Android notification permission dialog must dismiss after Allow", device.wait(Until.gone(
                            By.res(java.util.regex.Pattern.compile(".*:id/permission_allow_button"))), 10_000));
                    eventually(() -> context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED ? Boolean.TRUE : null);
                } catch (Exception | AssertionError failure) {
                    File directory = new File(context.getExternalFilesDir(null), "release-check");
                    assertTrue(directory.isDirectory() || directory.mkdirs());
                    device.takeScreenshot(new File(directory, "25-notification-permission-failure.png"));
                    throw failure;
                }
            }
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
            profilePhotoWorkflow(peer, own);
            if ("true".equals(arguments.getString("releasePush"))) notificationWorkflow(scenario, peer, outgoing);
            presenceWorkflow(scenario, peer, own);
            if ("true".equals(arguments.getString("releaseRemotePhotos"))) remotePhotosWorkflow(scenario, peer, own);
            clearChatWorkflow(peer, own);
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
            scenario.moveToState(Lifecycle.State.CREATED);
            ChatEngine.Account expired;
            AndroidVault savedVault = new AndroidVault(context);
            try {
                savedVault.unlock();
                byte[] savedAccount = savedVault.get("account");
                try { expired = RelayApi.JSON.fromJson(new String(savedAccount, StandardCharsets.UTF_8), ChatEngine.Account.class); }
                finally { Arrays.fill(savedAccount, (byte) 0); }
                assertNotNull("Signed device sessions must include encrypted remembered sign-in", expired.refreshToken());
                ChatEngine.Account due = new ChatEngine.Account(expired.origin(), expired.handle(), expired.userId(), expired.deviceId(), expired.accessToken(),
                        System.currentTimeMillis() - 1000, true, expired.refreshToken(), expired.refreshExpiresAt());
                byte[] encodedAccount = RelayApi.JSON.toJson(due).getBytes(StandardCharsets.UTF_8);
                try { savedVault.transaction(() -> { savedVault.put("account", encodedAccount); return null; }); }
                finally { Arrays.fill(encodedAccount, (byte) 0); }
            } finally { savedVault.close(); }
            ChatEngine.Account beforeRenewal = expired;
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitText(peer.handle());
            assertFalse("Ordinary token expiry must not require another password", device.hasObject(By.text("Welcome back.")));
            fill("Message", "Remembered session renewed"); clickIcon("Send");
            ChatEngine.Incoming renewedMessage = eventually(() -> {
                ChatEngine.Incoming[] pending = peer.api().call("GET", "/messages/pending", null, ChatEngine.Incoming[].class);
                return pending.length == 0 ? null : pending[0];
            });
            byte[] renewedPlaintext = peer.crypto().decrypt(own.userId(), new SignalClient.Packet(renewedMessage.type(), renewedMessage.ciphertext()));
            try { assertEquals("Remembered session renewed", RelayApi.JSON.fromJson(new String(renewedPlaintext, StandardCharsets.UTF_8), ChatEnvelope.class).text()); }
            finally { Arrays.fill(renewedPlaintext, (byte) 0); }
            peer.api().call("POST", "/messages/" + renewedMessage.id() + "/read", null, Void.class);
            capture("23-release-remembered-session-renewed");
            scenario.moveToState(Lifecycle.State.CREATED);
            AndroidVault renewedVault = new AndroidVault(context);
            try {
                renewedVault.unlock();
                byte[] savedAccount = renewedVault.get("account");
                try { expired = RelayApi.JSON.fromJson(new String(savedAccount, StandardCharsets.UTF_8), ChatEngine.Account.class); }
                finally { Arrays.fill(savedAccount, (byte) 0); }
                assertNotEquals(beforeRenewal.refreshToken(), expired.refreshToken());
                assertEquals(beforeRenewal.deviceId(), expired.deviceId());
                assertNull(renewedVault.get("session-renewal"));
            } finally { renewedVault.close(); }
            try (RelayApi rejected = new RelayApi(expired.origin(), expired.accessToken())) { rejected.call("POST", "/auth/logout", null, Void.class); }
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitText("Welcome back.");
            assertFalse(device.hasObject(By.text("Sign out of this device?")));
            fill("Username", handle); fill("Password", password); clickText("Sign in");
            awaitText(peer.handle());
            assertEquals("Session recovery must preserve the same registered device and public key", own,
                peer.api().call("GET", "/users/" + handle, null, ChatEngine.Contact.class));
            assertFalse("Session recovery must not resurrect consumed content", device.hasObject(By.text("One-time release verification")));
            capture("08c-release-session-recovery");
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
                verifyContactByUsername(renamedHandle, identity[0], "Release profile");
                clickText("Release profile");
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
                verifyContactByUsername(anotherHandle, switchedIdentity[0], "Receiver profile");
                clickText("Receiver profile");
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
                assertFalse(device.hasObject(By.text("@" + renamedOtherHandle)));
                assertTrue(device.hasObject(By.text("abc")));
                assertTrue("The sender's retained copy must show the receiver's receipt", device.wait(Until.hasObject(By.desc("READ")), 30_000));
                capture("14-release-retained-sender-copy");
                clickIcon("Conversation options");
                clickPopupItem("Profile");
                awaitText("Contact profile");
                awaitText("Receiver profile");
                awaitText("@" + renamedOtherHandle);
                clickText("Use profile name");
                assertTrue("Contact profile must close after restoring the profile name", device.wait(Until.gone(By.text("Contact profile")), 30_000));
                awaitText("Receiver profile");
                assertEquals("Release profile", peer.api().call("GET", "/users/id/" + own.userId() + "/profile", null, ChatEngine.Profile.class).displayName());
                clickIcon("Back");
                groupWorkflow(renamedHandle,password,renamedOtherHandle,anotherPassword);
                clickIcon("Profile");
                clickText("Sign out");
                awaitText("Sign out of this device?");
                clickText("Sign out");
                awaitText("Continue with Google");
        }
    }
}