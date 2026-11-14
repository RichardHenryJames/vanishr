package app.vanishr.android;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
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
    // What the admin-onboarding transport was asked to delete at the relay (an unread message that was handed back by DELETE /messages/{id}).
    private final List<String> deletedMessages = new ArrayList<>();
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
        unlockPhotoFixture(InstrumentationRegistry.getArguments().getString("devicePin"));
        resetQaFixture();
        assertTrue(context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear()
            .putLong("last-check", System.currentTimeMillis()).commit());
        // The signed settings are not fetched from the real download site by a test that is about something else.
        assertTrue(context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE).edit().clear()
            .putLong("last-check", System.currentTimeMillis()).commit());
        assertTrue(context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE).edit().clear().commit());
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
        assertTrue(context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE).edit().clear().commit());
    }

    private void resetQaFixture() throws Exception {
        assertTrue(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(context.getSharedPreferences("backup", Context.MODE_PRIVATE).edit().clear().commit());
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
        signedInFixture(AdminOnboarding.OFFICIAL);
    }

    private void signedInFixture(AdminOnboarding.Pin pin) throws Exception {
        vault.transaction(() -> {
            write("account", new ChatEngine.Account("https://127.0.0.1:1/", "alex", userId, deviceId, "synthetic-fixture", System.currentTimeMillis() + 3_600_000, true));
            return null;
        });
        engine = new ChatEngine(vault, pin); engine.online = true;
        engine.safety().acceptTerms(PlayPolicy.VERSION);
    }

    private AdminOnboarding.Pin adminPin(UUID id, UUID device, SignalClient signal) throws Exception {
        return new AdminOnboarding.Pin("https://127.0.0.1:1/", id, device,
            ChatEngine.safetyNumber(id, Base64.getEncoder().encodeToString(signal.publicIdentity())).replace(" ", "").toLowerCase(Locale.ROOT));
    }

    private AdminOnboarding.Introduction introduction(UUID id, UUID device, SignalClient signal, String handle) {
        return new AdminOnboarding.Introduction(id, device, Base64.getEncoder().encodeToString(signal.publicIdentity()), handle, handle.equals("vanishr") ? "Vanishr" : null);
    }

    private void adminTransport(java.util.concurrent.atomic.AtomicReference<AdminOnboarding.Page> directory,
                                List<ChatEngine.Incoming> incoming, List<ChatEngine.Send> outgoing, PublicBundle prekey) {
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            var request = chain.request(); String path = request.url().encodedPath();
            AdminOnboarding.Page page = directory.get();
            if (path.equals("/account/admin-contacts")) return syntheticResponse(request, 200, page);
            if (path.startsWith("/account/admin-contacts/")) {
                UUID id = UUID.fromString(path.substring("/account/admin-contacts/".length()));
                return syntheticResponse(request, 200, new AdminOnboarding.Page(page.userId(), page.admin(),
                    page.contacts().stream().filter(value -> id.equals(value.userId())).toList(), null));
            }
            for (AdminOnboarding.Introduction contact : page.contacts()) {
                if (path.equals("/users/id/" + contact.userId()) || path.equals("/users/" + contact.handle()))
                    return syntheticResponse(request, 200, RemotePhotoSession.contact(contact.peer()));
                if (path.equals("/users/id/" + contact.userId() + "/profile"))
                    return syntheticResponse(request, 200, new ChatEngine.Profile(contact.userId(), contact.handle(), contact.displayName()));
                if (path.equals("/keys/" + contact.userId() + "/claim")) {
                    if (prekey == null) throw new AssertionError("Automatic contacts must not acquire keys for implicit sharing");
                    return syntheticResponse(request, 200, prekey);
                }
            }
            if (path.equals("/account/profile")) return syntheticResponse(request, 200, new ChatEngine.Profile(userId, "alex", null));
            if (path.equals("/account/type")) return syntheticResponse(request, 200, new RemotePhotoSession.AccountType(userId, engine.onboarding().admin() ? "ADMIN" : "USER"));
            if (path.equals("/messages/pending")) {
                List<ChatEngine.Incoming> batch = new ArrayList<>(incoming); incoming.clear();
                return syntheticResponse(request, 200, batch);
            }
            if (path.equals("/messages") && request.method().equals("POST")) {
                okio.Buffer body = new okio.Buffer(); request.body().writeTo(body);
                ChatEngine.Send sent = RelayApi.JSON.fromJson(body.readUtf8(), ChatEngine.Send.class); outgoing.add(sent);
                return syntheticResponse(request, 200, new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt()));
            }
            if (path.equals("/messages/status")) return syntheticResponse(request, 200,
                outgoing.stream().map(sent -> new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt())).toList());
            if (path.matches("/messages/[0-9a-f-]+") && request.method().equals("DELETE")) { deletedMessages.add(path); return syntheticResponse(request, 200, null); }
            if (path.matches("/messages/[0-9a-f-]+/(delivered|read)")) return syntheticResponse(request, 200, null);
            if (path.equals("/keys")) return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
            if (Set.of("/groups", "/profile/packets", "/remote-photos", "/presence").contains(path)) return syntheticResponse(request, 200, List.of());
            if (path.equals("/events")) return syntheticResponse(request, 503, Map.of("error", "fixture_offline"));
            throw new AssertionError("Unexpected admin-onboarding fixture request: " + request.method() + " " + path);
        }));
    }

    @Test public void adminOnboardingAddsOfficialAdminWithoutManualVerificationOrPhotoAndGroupPermissions() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(
            userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().refresh();
        assertEquals(List.of(introduced.peer()), engine.peers());
        assertTrue(engine.groupSignal().isVerified(peerId));
        assertFalse(engine.independentlyVerified(peerId));
        engine.prepareConversation(introduced.peer());
        engine.presence().foreground(true);
        assertTrue(engine.sharesPresence(peerId));
        assertEquals(List.of(RemotePhotoSession.contact(introduced.peer())),
            engine.presence().update(android.os.SystemClock.elapsedRealtime()).contacts());
        engine.photos().sync();
        assertTrue(vault.names("profile-photo-request/").isEmpty());
        assertThrows(SecurityException.class, () -> new RemotePhotoSession.Prepared(engine, introduced.peer(), null));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            snapshot(scenario, "80-official-admin-in-chats");
            scenario.onActivity(activity -> {
                assertNotNull(text(root(activity), "Vanishr"));
                assertFalse(descendants(root(activity)).stream().anyMatch(view ->
                    view instanceof TextView label && "Official admin".contentEquals(label.getText())));
                setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                assertNull(dialog(activity));
                assertOfficialAdminHeader(activity);
                assertNotNull(readField(activity, "composer"));
            });
            snapshot(scenario, "81-official-admin-conversation");
        }
    }

    @Test public void adminOnboardingRejectsUnpinnedChangedAndUnrelatedIdentitiesAtomically() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(
            userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        var unrelated = introduction(UUID.randomUUID(), UUID.randomUUID(), admin, "unrelated");
        directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(introduced.peer()), List.of(unrelated), null));
        assertThrows(SecurityException.class, () -> engine.onboarding().refresh());
        assertTrue(engine.peers().isEmpty()); assertFalse(engine.groupSignal().isVerified(peerId));
        directory.set(new AdminOnboarding.Page(UUID.randomUUID(), RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        assertThrows(SecurityException.class, () -> engine.onboarding().first());
        directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        engine.onboarding().first();
        SignalClient replacement = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        var changed = introduction(peerId, peerDevice, replacement, "vanishr");
        directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(changed.peer()), List.of(changed), null));
        assertThrows(SecurityException.class, () -> engine.onboarding().first());
        assertThrows(SecurityException.class, () -> engine.addPeer(changed.peer()));
        assertEquals(introduced.peer(), engine.peers().get(0));
        assertTrue(engine.onboarding().automatic(peerId));
        assertThrows(SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, replacement.publicIdentity()));
    }

    @Test public void adminOnboardingRemovalSurvivesRefreshAndExplicitVerificationRetainsItsSeparateMeaning() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(
            userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().refresh();
        engine.forget(introduced.peer());
        engine.onboarding().first();
        assertTrue(engine.peers().isEmpty());
        assertFalse(engine.onboarding().acceptIncoming(peerId, peerDevice));
        assertFalse(engine.groupSignal().isVerified(peerId));
        engine.addPeer(introduced.peer());
        assertTrue(engine.independentlyVerified(peerId));
        engine.onboarding().first();
        assertTrue(engine.independentlyVerified(peerId));
        assertEquals(1, engine.peers().size());
    }

    @Test public void adminOnboardingDirectoryIsPagedAndNewAccountsAppearBeforeMessaging() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        ChatEngine.Contact official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(own.publicIdentity()));
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        List<AdminOnboarding.Introduction> contacts = new ArrayList<>();
        for (int index = 1; index <= 65; index++) contacts.add(introduction(new UUID(0, index), UUID.randomUUID(), remote,
                index == 1 ? "quiet-otter-4827" : "new_account_" + index));
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, contacts.subList(0, 64), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().refresh();
        assertEquals(64, engine.peers().size()); assertFalse(engine.onboarding().hasNext());
        long generation = engine.onboarding().generation();
        List<ChatEngine.Peer> firstPage = engine.peers();
        directory.set(new AdminOnboarding.Page(userId, official, contacts.subList(0, 64), contacts.get(63).userId()));
        engine.onboarding().first();
        assertEquals(firstPage, engine.peers());
        assertTrue(engine.onboarding().generation() > generation);
        assertTrue(engine.onboarding().hasNext());
        assertEquals(64, vault.names("contact/").size()); assertEquals(64, vault.names("peer/").size());
        ChatEngine.Peer chosen = contacts.get(0).peer();
        engine.prepareConversation(chosen);
        assertEquals(64, vault.names("contact/").size());
        assertTrue(engine.groupSignal().isVerified(chosen.userId())); assertFalse(engine.independentlyVerified(chosen.userId()));
        directory.set(new AdminOnboarding.Page(userId, official, List.of(contacts.get(64)), null));
        engine.onboarding().next();
        assertEquals(65, engine.peers().size()); assertFalse(engine.onboarding().hasNext()); assertTrue(engine.onboarding().hasPrevious());
        assertTrue(engine.peers().contains(chosen));
        assertTrue(engine.peers().contains(contacts.get(64).peer()));
        assertEquals(65, vault.names("contact/").size());
        directory.set(new AdminOnboarding.Page(userId, official, List.of(), null));
        assertFalse(engine.onboarding().acceptIncoming(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test public void adminOnboardingRejectsAnAdminWithoutThePinnedPrivateIdentity() throws Exception {
        SignalClient expected = new SignalClient(userId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(userId, deviceId, expected));
        var official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(expected.publicIdentity()));
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, List.of(), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        assertThrows(SecurityException.class, () -> engine.onboarding().refresh());
        assertTrue(engine.peers().isEmpty());
    }

    @Test public void automaticAdminConnectionSharesPresenceLikeAVerifiedContactButNothingElse() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(
            userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().refresh();
        ChatEngine.Peer official = introduced.peer();
        ChatEngine.Contact contact = RemotePhotoSession.contact(official);
        assertFalse(engine.independentlyVerified(peerId));
        assertTrue(engine.automaticConnection(peerId)); assertTrue(engine.sharesPresence(peerId));
        ContactPresence presence = engine.presence();
        presence.foreground(true); presence.conversation(peerId); presence.edited(peerId, true);
        setField(engine, "realtimeReady", true);
        long now = android.os.SystemClock.elapsedRealtime();
        ContactPresence.Update update = presence.update(now);
        assertEquals(List.of(contact), update.contacts());
        assertEquals(peerId, update.typingTo()); assertTrue(update.lastSeen());
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 5_000)}, update.contacts(), 1000, 1200);
        assertEquals("Typing", presence.label(official, 1200)); assertEquals("Online", presence.label(official, 6000));
        presence.accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 0, 0, 3_600_000L)}, update.contacts(), 1000, 1200);
        assertEquals("Last seen 1 hour ago", presence.label(official, 1200));
        engine.photos().sync();
        assertTrue(vault.names("profile-photo-request/").isEmpty());
        assertFalse(engine.independentlyVerified(peerId));

        ChatEngine.Peer tampered = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(
            new SignalClient(peerId, new DeviceSecurityTest.MemoryVault()).publicIdentity()), "Vanishr");
        vault.transaction(() -> { write("contact/" + peerId, tampered); return null; });
        assertFalse(engine.sharesPresence(peerId));
        assertTrue(presence.update(now).contacts().isEmpty()); assertNull(presence.update(now).typingTo());
        assertEquals("", presence.label(official, 1200));
        vault.transaction(() -> { write("contact/" + peerId, official); return null; });
        assertTrue(engine.sharesPresence(peerId));
        vault.transaction(() -> { vault.put("blocked/" + peerId, new byte[]{1}); return null; });
        assertFalse(engine.sharesPresence(peerId)); assertTrue(presence.update(now).contacts().isEmpty());
        vault.transaction(() -> { vault.remove("blocked/" + peerId); return null; });
        assertEquals(List.of(contact), presence.update(now).contacts());

        setField(engine.onboarding(), "pin", adminPin(UUID.randomUUID(), UUID.randomUUID(), admin));
        assertFalse(engine.automaticConnection(peerId)); assertTrue(presence.update(now).contacts().isEmpty());
        assertNull(presence.update(now).typingTo()); assertEquals("", presence.label(official, 1200));
        setField(engine.onboarding(), "pin", adminPin(peerId, peerDevice, admin));
        assertTrue(engine.automaticConnection(peerId)); assertEquals("Last seen 1 hour ago", presence.label(official, 1200));

        UUID stranger = UUID.randomUUID();
        vault.transaction(() -> {
            engine.groupSignal().verifyPeer(stranger, admin.publicIdentity());
            write("contact/" + stranger, new ChatEngine.Peer(stranger, UUID.randomUUID(), contact.identityKey(), "Marked stranger"));
            write("admin-contact/" + stranger, true);
            return null;
        });
        assertFalse(engine.sharesPresence(stranger));
        assertEquals(List.of(contact), presence.update(now).contacts());

        engine.forget(official);
        assertFalse(engine.sharesPresence(peerId)); assertTrue(presence.update(now).contacts().isEmpty());
        engine.addPeer(official);
        assertTrue(engine.independentlyVerified(peerId)); assertFalse(engine.automaticConnection(peerId));
        assertEquals(List.of(contact), presence.update(now).contacts());
    }

    @Test public void adminPresenceAudienceKeepsTheOpenChatAndVerifiedContactsFirstWithinTheRelayLimit() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        ChatEngine.Contact official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(own.publicIdentity()));
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        List<AdminOnboarding.Introduction> contacts = new ArrayList<>();
        for (int index = 1; index <= 130; index++) contacts.add(introduction(new UUID(0, index), UUID.randomUUID(), remote, String.format(Locale.ROOT, "acct_%03d", index)));
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, contacts.subList(0, 64), contacts.get(63).userId()));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().first();
        directory.set(new AdminOnboarding.Page(userId, official, contacts.subList(64, 128), contacts.get(127).userId()));
        engine.onboarding().next();
        directory.set(new AdminOnboarding.Page(userId, official, contacts.subList(128, 130), null));
        engine.onboarding().next();
        assertEquals(130, engine.peers().size());
        ContactPresence presence = engine.presence(); presence.foreground(true);
        long now = android.os.SystemClock.elapsedRealtime();
        Set<UUID> firstPage = new HashSet<>();
        for (int index = 0; index < 128; index++) firstPage.add(contacts.get(index).userId());

        ContactPresence.Update update = presence.update(now);
        assertEquals(ContactPresence.LIMIT, update.contacts().size());
        assertEquals(firstPage, update.contacts().stream().map(ChatEngine.Contact::userId).collect(java.util.stream.Collectors.toSet()));
        assertNull(update.typingTo());

        ChatEngine.Peer last = contacts.get(129).peer();
        presence.conversation(last.userId()); presence.edited(last.userId(), true);
        update = presence.update(now);
        assertEquals(ContactPresence.LIMIT, update.contacts().size());
        assertEquals(last.userId(), update.contacts().get(0).userId()); assertEquals(last.userId(), update.typingTo());
        assertFalse(update.contacts().stream().anyMatch(value -> value.userId().equals(contacts.get(127).userId())));

        engine.addPeer(last);
        assertTrue(engine.independentlyVerified(last.userId()));
        presence.conversation(contacts.get(128).userId()); presence.edited(contacts.get(128).userId(), true);
        update = presence.update(now);
        assertEquals(ContactPresence.LIMIT, update.contacts().size());
        assertEquals(contacts.get(128).userId(), update.contacts().get(0).userId());
        assertEquals(contacts.get(128).userId(), update.typingTo());
        assertEquals(last.userId(), update.contacts().get(1).userId());
        assertFalse(update.contacts().stream().anyMatch(value -> value.userId().equals(contacts.get(126).userId())));
        assertTrue(update.contacts().stream().anyMatch(value -> value.userId().equals(contacts.get(125).userId())));

        setField(engine.onboarding(), "pin", adminPin(userId, deviceId, new SignalClient(userId, new DeviceSecurityTest.MemoryVault())));
        update = presence.update(now);
        assertEquals(List.of(RemotePhotoSession.contact(last)), update.contacts());
        assertNull(update.typingTo());
    }

    @Test public void officialAdminConversationHeaderShowsOnlineTypingAndLastSeen() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(
            userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().refresh();
        ChatEngine.Contact contact = RemotePhotoSession.contact(introduced.peer());
        var reportedPresence = new java.util.concurrent.atomic.AtomicReference<ContactPresence.Status>();
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            if (chain.request().url().encodedPath().equals("/presence")) {
                ContactPresence.Status state = reportedPresence.get();
                return syntheticResponse(chain.request(), 200, state == null ? List.of() : List.of(state));
            }
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                assertOfficialAdminHeader(activity);
                assertEquals(View.GONE, ((TextView) readField(activity, "contactStatus")).getVisibility());
                setField(engine, "realtimeReady", true);
            });
            for (String label : List.of("Online", "Typing", "Last seen 1 hour ago")) {
                scenario.onActivity(activity -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    ContactPresence.Status state = label.startsWith("Last seen") ? new ContactPresence.Status(contact, 0, 0, 3_600_000L)
                            : new ContactPresence.Status(contact, 12_000, label.equals("Typing") ? 5000 : 0);
                    reportedPresence.set(state);
                    engine.presence().accept(new ContactPresence.Status[]{state}, List.of(contact), now, now);
                    invoke(activity, "refreshContactStatus");
                });
                awaitMainCondition(scenario, activity -> label.contentEquals(((TextView) readField(activity, "contactStatus")).getText()));
            }
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, ((TextView) readField(activity, "contactStatus")).getVisibility());
                assertOfficialAdminHeader(activity);
            });
            snapshot(scenario, "82-official-admin-last-seen");
        }
    }

    @Test public void adminOnboardingAcceptsAnEnrolledFirstMessageButNeverReplacesItsPinnedIdentity() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        var official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(own.publicIdentity()));
        SignalClient newcomer = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        var introduced = introduction(peerId, peerDevice, newcomer, "newcomer");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, List.of(introduced), null));
        List<ChatEngine.Incoming> incoming = new ArrayList<>();
        adminTransport(directory, incoming, new ArrayList<>(), null);
        newcomer.verifyPeer(userId, own.publicIdentity());
        newcomer.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 120_000;
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId,
            now, deadline, ChatEnvelope.Expiry.HOUR_1, "new-account-fixture", null);
        byte[] encoded = RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        SignalClient.Packet packet;
        try { packet = newcomer.encrypt(userId, encoded, Instant.now()); }
        finally { Arrays.fill(encoded, (byte) 0); }
        incoming.add(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId,
            ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null));
        engine.groupSignal().verifyPeer(peerId, newcomer.publicIdentity());
        assertNull("A group-channel identity alone is not a saved direct contact", vault.get("contact/" + peerId));
        engine.sync();
        assertTrue(engine.entries(peerId).stream().anyMatch(entry -> entry.id().equals(id)));
        assertTrue(engine.onboarding().automatic(peerId)); assertFalse(engine.independentlyVerified(peerId));
        SignalClient changed = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        var changedIntroduction = introduction(peerId, peerDevice, changed, "newcomer");
        directory.set(new AdminOnboarding.Page(userId, official, List.of(changedIntroduction), null));
        assertFalse("A changed identity is not accepted from a message", engine.onboarding().acceptIncoming(peerId, peerDevice));
        assertEquals("It waits for the person to review it instead", changedIntroduction.peer(), engine.onboarding().changed(peerId));
        assertEquals(introduced.peer(), engine.peers().get(0));
        assertThrows("The identity that was saved is still the one pinned", SecurityException.class,
            () -> engine.groupSignal().verifyPeer(peerId, changed.publicIdentity()));
    }

    @Test public void adminOnboardingDirectMessagesUseOfficialSignalWithoutGrantingOtherSendersTrust() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(
            userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        adminTransport(directory, incoming, outgoing, admin.generatePreKey(Instant.now()));
        engine.onboarding().refresh();
        engine.send(introduced.peer(), "admin-onboarding-fixture", null, ChatEnvelope.Expiry.HOUR_1);
        assertEquals(1, outgoing.size());
        admin.verifyPeer(userId, engine.groupSignal().publicIdentity());
        ChatEngine.Send sent = outgoing.get(0);
        byte[] plaintext = admin.decrypt(userId, new SignalClient.Packet(sent.type(), sent.ciphertext()));
        try { assertEquals("admin-onboarding-fixture", RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ChatEnvelope.class).text()); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 120_000;
        ChatEnvelope reply = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId, now, deadline, ChatEnvelope.Expiry.HOUR_1, "admin-reply", null);
        SignalClient.Packet packet = admin.encrypt(userId, RelayApi.JSON.toJson(reply).getBytes(StandardCharsets.UTF_8), Instant.now());
        incoming.add(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null));
        UUID stranger = UUID.randomUUID();
        incoming.add(new ChatEngine.Incoming(UUID.randomUUID(), stranger, UUID.randomUUID(), userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, 2, new byte[32], null));
        engine.sync();
        assertTrue(engine.entries(peerId).stream().anyMatch(entry -> id.equals(entry.id()) && !entry.outgoing()));
        assertFalse(engine.groupSignal().isVerified(stranger));
        assertTrue(engine.unverifiedIncoming);
        assertFalse(engine.independentlyVerified(peerId));
    }

    @Test public void newUserRetriesFirstAdminMessageWithFallbackWithoutSigningInAgain() throws Exception {
        SignalClient admin = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, admin));
        var introduced = introduction(peerId, peerDevice, admin, "vanishr");
        PublicBundle fallback = admin.generateFallbackPreKey(Instant.now(), Instant.now().plusSeconds(86400));
        var available = new java.util.concurrent.atomic.AtomicBoolean();
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            var request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/account/admin-contacts")) return syntheticResponse(request, 200,
                    new AdminOnboarding.Page(userId, RemotePhotoSession.contact(introduced.peer()), List.of(introduced), null));
            if (path.equals("/users/id/" + peerId)) return syntheticResponse(request, 200, RemotePhotoSession.contact(introduced.peer()));
            if (path.equals("/keys/" + peerId + "/claim")) {
                assertEquals("true", request.url().queryParameter("fallback"));
                return available.get() ? syntheticResponse(request, 200, fallback)
                        : syntheticResponse(request, 409, Map.of("error", "prekeys_unavailable"));
            }
            if (path.equals("/messages")) {
                okio.Buffer body = new okio.Buffer(); request.body().writeTo(body);
                ChatEngine.Send sent = RelayApi.JSON.fromJson(body.readUtf8(), ChatEngine.Send.class);
                outgoing.add(sent);
                return syntheticResponse(request, 201, new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt()));
            }
            throw new AssertionError("Unexpected first-admin-message request");
        }));
        engine.onboarding().refresh();
        assertEquals(List.of(introduced.peer()), engine.peers());
        String identity = engine.identityCode();
        UUID id = UUID.randomUUID(); long created = System.currentTimeMillis();
        RelayApi.ApiFailure unavailable = assertThrows(RelayApi.ApiFailure.class, () ->
                engine.send(introduced.peer(), "first admin message", null, ChatEnvelope.Expiry.HOUR_1, id, created, () -> { }));
        assertEquals("prekeys_unavailable", unavailable.code);
        assertTrue(outgoing.isEmpty()); assertTrue(engine.entries(peerId).isEmpty());
        assertFalse(engine.groupSignal().hasSession(peerId));
        available.set(true);
        engine.send(introduced.peer(), "first admin message", null, ChatEnvelope.Expiry.HOUR_1, id, created, () -> { });
        assertEquals(identity, engine.identityCode()); assertTrue(engine.authenticated());
        assertEquals(1, outgoing.size());
        ChatEngine.Send sent = outgoing.get(0);
        assertEquals(id, sent.id()); assertEquals(created + ChatEnvelope.Expiry.HOUR_1.milliseconds, sent.expiresAt());
        admin.verifyPeer(userId, engine.groupSignal().publicIdentity());
        byte[] clear = admin.decrypt(userId, new SignalClient.Packet(sent.type(), sent.ciphertext()));
        try { assertEquals("first admin message", RelayApi.JSON.fromJson(new String(clear, StandardCharsets.UTF_8), ChatEnvelope.class).text()); }
        finally { Arrays.fill(clear, (byte) 0); }
        assertFalse(engine.independentlyVerified(peerId));
        assertTrue(vault.names("profile-photo-request/").isEmpty());
    }

    @Test public void fallbackPublicationRetriesAcrossReopeningWithoutReplacingPrivateKeysOrExtendingExpiry() throws Exception {
        signedInFixture();
        List<ChatEngine.FallbackKey> uploads = new ArrayList<>();
        var accepted = new java.util.concurrent.atomic.AtomicReference<ChatEngine.FallbackKey>();
        var loseResponse = new java.util.concurrent.atomic.AtomicBoolean(true);
        okhttp3.Interceptor transport = chain -> {
            var request = chain.request();
            assertTrue(request.url().encodedPath().startsWith("/keys"));
            if (request.method().equals("GET")) {
                ChatEngine.FallbackKey current = accepted.get();
                return syntheticResponse(request, 200, new ChatEngine.KeyCount(16, true,
                        current == null ? 0 : current.key().kyberPreKeyId(), current == null ? 0 : current.expiresAt()));
            }
            assertEquals("PUT", request.method()); assertEquals("/keys/fallback", request.url().encodedPath());
            okio.Buffer body = new okio.Buffer(); request.body().writeTo(body);
            ChatEngine.FallbackKey upload = RelayApi.JSON.fromJson(body.readUtf8(), ChatEngine.FallbackKey.class);
            uploads.add(upload);
            if (loseResponse.getAndSet(false)) throw new IOException("Synthetic upload interrupted");
            accepted.set(upload);
            return syntheticResponse(request, 204, null);
        };
        engine.groupApi().close(); setField(engine, "api", syntheticApi(transport));
        Method replenish = ChatEngine.class.getDeclaredMethod("replenishKeys"); replenish.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> replenish.invoke(engine));
        assertEquals(1, uploads.size());
        String identity = engine.identityCode();
        byte[] privateKey = vault.get("kyber/" + uploads.get(0).key().kyberPreKeyId());
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        engine.groupApi().close(); setField(engine, "api", syntheticApi(transport));
        replenish.invoke(engine);
        assertEquals(2, uploads.size());
        assertEquals(RelayApi.JSON.toJson(uploads.get(0)), RelayApi.JSON.toJson(uploads.get(1)));
        assertArrayEquals(privateKey, vault.get("kyber/" + uploads.get(1).key().kyberPreKeyId()));
        Arrays.fill(privateKey, (byte) 0);
        assertEquals(identity, engine.identityCode());
        replenish.invoke(engine); assertEquals(2, uploads.size());
        assertEquals(0, uploads.get(0).key().preKeyId()); assertNull(uploads.get(0).key().preKey());
        assertTrue(uploads.get(0).expiresAt() <= System.currentTimeMillis() + 30L * 86400_000);
        vault.saveAccount(userId);
        assertNull(vault.get("fallback-key"));
        assertTrue(vault.restoreAccount(userId));
        assertNotNull(vault.get("fallback-key"));
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

    /** What the open dialog says, for a failure message that names it. */
    private String shownText(MainActivity activity) {
        SecureSheet open = dialog(activity);
        if (open == null) return "(no dialog)";
        StringBuilder text = new StringBuilder(open.isShowing() ? "showing: " : "not showing: ");
        for (View view : descendants(open.getWindow().getDecorView())) if (view instanceof TextView label && label.getText().length() > 0) text.append('[').append(label.getText()).append(']');
        return text.toString();
    }

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

    private void awaitMainCondition(ActivityScenario<MainActivity> scenario, java.util.function.Predicate<MainActivity> condition) throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(1);
        var matched = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
        scenario.onActivity(activity -> new Runnable() {
            @Override public void run() {
                if (condition.test(activity)) { matched.set(true); ready.countDown(); }
                else if (android.os.SystemClock.elapsedRealtime() >= deadline) ready.countDown();
                else new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 20);
            }
        }.run());
        assertTrue("The chat UI must finish its asynchronous update", ready.await(20, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("The chat UI did not reach the expected state", matched.get());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void awaitMessages(ActivityScenario<MainActivity> scenario) throws Exception {
        awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "messageRefreshQueued"));
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

    private void assertBrandLogo(MainActivity activity) {
        ImageView logo = activity.findViewById(R.id.brand_logo);
        assertNotNull("The app header must show the shared logo", logo);
        assertNotNull(logo.getDrawable());
        assertNull("The supplied logo must retain its colors", logo.getColorFilter());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, logo.getImportantForAccessibility());
    }

    @Test public void policiesRequireAnUncheckedAffirmativeChoiceBeforeFirstUse() throws Exception {
        var continued = new java.util.concurrent.atomic.AtomicBoolean();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "ensurePolicies", new Class<?>[]{Runnable.class}, (Runnable) () -> continued.set(true));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertNotNull(text(sheet.getWindow().getDecorView(), "Privacy policy"));
                assertNotNull(text(sheet.getWindow().getDecorView(), "Terms & safety"));
                MaterialCheckBox consent = descendants(sheet.getWindow().getDecorView()).stream()
                    .filter(MaterialCheckBox.class::isInstance).map(MaterialCheckBox.class::cast).findFirst().orElseThrow();
                assertFalse(consent.isChecked());
                assertFalse(sheet.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertFalse(continued.get());
                consent.setChecked(true);
                assertTrue(sheet.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(continued.get());
            });
        }
    }

    @Test public void accountDeletionRequiresTypedConfirmationAndReauthentication() throws Exception {
        signedInFixture();
        String before = engine.identityCode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "deleteAccountDialog");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertNotNull(text(sheet.getWindow().getDecorView(), "Deletion policy and help"));
                assertFalse(descendants(sheet.getWindow().getDecorView()).stream().filter(EditText.class::isInstance)
                    .map(EditText.class::cast).anyMatch(input -> input.length() > 0));
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(descendants(sheet.getWindow().getDecorView()).stream()
                    .filter(com.google.android.material.textfield.TextInputLayout.class::isInstance)
                    .map(com.google.android.material.textfield.TextInputLayout.class::cast)
                    .anyMatch(field -> "Type DELETE exactly".contentEquals(field.getError())));
                assertEquals(before, engine.identityCode());
                assertTrue(engine.authenticated());
                assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
        }
    }

    @Test public void safetyActionsAndPoliciesAreReachableWithoutSendingPlaintext() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "accountDialog");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View panel = dialog(activity).getWindow().getDecorView();
                assertNotNull(text(panel, "Blocked accounts"));
                assertNotNull(text(panel, "Privacy policy"));
                assertNotNull(text(panel, "Terms & safety"));
                assertNotNull(text(panel, "Support"));
                assertNotNull(text(panel, "Delete account"));
                dialog(activity).dismiss();
                invoke(activity, "reportUserDialog", new Class<?>[]{UUID.class, ChatEngine.Entry.class}, peerId, once);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertEquals(7, sheet.getListView().getAdapter().getCount());
                assertEquals("Child safety", sheet.getListView().getAdapter().getItem(3));
                sheet.getListView().performItemClick(null, 3, 3);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertTrue(((TextView) sheet.findViewById(android.R.id.message)).getText().toString().contains("No chat text or photos"));
                assertFalse(descendants(sheet.getWindow().getDecorView()).stream().anyMatch(EditText.class::isInstance));
                assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
        }
    }

    @Test public void backupAndRestoreSheetsAreSecureAndStateWhatIsNeverBackedUp() throws Exception {
        signedInFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity, "accountDialog"); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Backup & restore"));
                invoke(activity, "backupDialog");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                View panel = sheet.getWindow().getDecorView();
                assertNotNull(text(panel, "Turn on backup"));
                assertNotNull(text(panel, "Restore from backup"));
                assertTrue(descendants(panel).stream().anyMatch(view -> view instanceof TextView label
                    && label.getText().toString().contains("Messages, photos, private keys and sign-in details are never backed up")));
                assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                invoke(activity, "restoreDialog", new Class<?>[]{ChatEngine.class}, engine);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertNotNull(text(sheet.getWindow().getDecorView(), "Recovery key"));
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(descendants(sheet.getWindow().getDecorView()).stream()
                    .filter(com.google.android.material.textfield.TextInputLayout.class::isInstance)
                    .map(com.google.android.material.textfield.TextInputLayout.class::cast)
                    .anyMatch(field -> "Enter your recovery key".contentEquals(field.getError())));
                assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertFalse(engine.backup().enabled());
            });
        }
    }

    @Test public void enabledBackupOffersBackupNowAndShowsTheStoredRecoveryKeyOnlyOnRequest() throws Exception {
        signedInFixture();
        byte[] key = BackupCipher.newRecoveryKey();
        engine.backup().enable(key);
        // A recent upload keeps the automatic refresh from starting a real attempt while the sheet is inspected.
        BackupState.succeeded(context, userId, System.currentTimeMillis());
        String formatted = BackupCipher.formatRecoveryKey(key);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity, "backupDialog"); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                View panel = sheet.getWindow().getDecorView();
                assertNotNull(text(panel, "Back up now"));
                assertNotNull(text(panel, "Show recovery key"));
                assertNotNull(text(panel, "Turn off and delete backup"));
                assertTrue(descendants(panel).stream().anyMatch(view -> view instanceof TextView label
                    && label.getText().toString().startsWith("Backup is on.")));
                assertFalse("The key is never shown until requested", descendants(panel).stream().anyMatch(view -> view instanceof TextView label
                    && label.getText().toString().contains(formatted.substring(0, 9))));
                invoke(activity, "recoveryKeySheet", new Class<?>[]{ChatEngine.class, boolean.class}, engine, false);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertNotNull(text(sheet.getWindow().getDecorView(), formatted));
                assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
        }
    }

    @Test public void launcherAndNotificationBrandingUseTheSharedLogo() {
        assertEquals(R.mipmap.ic_launcher, context.getApplicationInfo().icon);
        var drawable = context.getDrawable(R.mipmap.ic_launcher);
        assertTrue(drawable instanceof android.graphics.drawable.AdaptiveIconDrawable);
        var launcher = (android.graphics.drawable.AdaptiveIconDrawable) drawable;
        assertNotNull(launcher.getForeground()); assertNotNull(launcher.getBackground());
        if (Build.VERSION.SDK_INT >= 33) assertNotNull(launcher.getMonochrome());
        Bitmap notificationIcon = BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_stat_vanishr);
        try {
            assertEquals(0, Color.alpha(notificationIcon.getPixel(0, 0)));
            boolean visible = false;
            for (int y = 0; y < notificationIcon.getHeight(); y++) for (int x = 0; x < notificationIcon.getWidth(); x++) {
                int pixel = notificationIcon.getPixel(x, y);
                if (Color.alpha(pixel) == 0) continue;
                visible = true;
                assertEquals(255, Color.red(pixel)); assertEquals(255, Color.green(pixel)); assertEquals(255, Color.blue(pixel));
            }
            assertTrue("The notification logo cannot be empty", visible);
        } finally { notificationIcon.recycle(); }
        android.app.Notification notification = PushService.genericNotification(context);
        assertEquals(R.drawable.ic_stat_vanishr, notification.getSmallIcon().getResId());
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertTrue(notification.contentIntent.isImmutable());
    }

    @Test public void compactClassicHomeUsesOneToolbarAnd116DpBeforeTheList() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            snapshot(scenario, "50-compact-classic-home");
            scenario.onActivity(activity -> {
                assertBrandLogo(activity);
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
                for (String action : List.of("Share username","Verify identity",BuildConfig.PLAY_STORE ? "Open Google Play" : "Check for updates","Licenses & source","Sign out")) assertNotNull(text(decor,action));
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

    @Test public void homeAvatarRefreshDoesNotBlockOnBusySecureStorage() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(((List<?>) readField(activity, "photoAvatars")).isEmpty());
                ((java.util.concurrent.ExecutorService) readField(activity, "work")).execute(() -> {
                    synchronized (vault) {
                        locked.countDown();
                        try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    }
                });
            });
            try {
                assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    setField(activity, "shownPhotoGeneration", -1L);
                    long started = android.os.SystemClock.elapsedRealtime();
                    invoke(activity, "refreshProfileAvatars");
                    long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                    assertTrue("Home avatar refresh must take <100ms while storage is busy; took " + elapsed + "ms", elapsed < 100);
                    android.os.Bundle metrics = new android.os.Bundle(); metrics.putLong("homeAvatarRefreshMillis", elapsed);
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
                });
            } finally { release.countDown(); }
        }
    }

    @Test public void backgroundingCancelsBlockedNetworkBeforeQueuedVaultClose() throws Exception {
        signedInFixture(); notificationTransport(reference -> null);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        RelayApi api = engine.groupApi();
        var builder = ((okhttp3.OkHttpClient) readField(api, "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            if (chain.request().url().encodedPath().equals("/slow-fixture")) {
                started.countDown();
                try {
                    while (!chain.call().isCanceled() && !release.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) { }
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                throw new IOException("Synthetic cancelled request");
            }
            return chain.proceed(chain.request());
        });
        setField(api, "client", builder.build());
        java.util.concurrent.ExecutorService[] worker = new java.util.concurrent.ExecutorService[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    inject(activity);
                    worker[0] = (java.util.concurrent.ExecutorService) readField(activity, "work");
                    worker[0].execute(() -> {
                        try { api.call("GET", "/slow-fixture", null, Void.class); }
                        catch (Exception failure) { assertTrue(failure instanceof IOException); }
                        finally { finished.countDown(); }
                    });
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long pausedAt = android.os.SystemClock.elapsedRealtime();
                scenario.moveToState(Lifecycle.State.STARTED);
                assertTrue("Pausing must cancel network work within 1s instead of waiting for its timeout",
                        finished.await(1, java.util.concurrent.TimeUnit.SECONDS));
                android.os.Bundle metrics = new android.os.Bundle();
                metrics.putLong("backgroundRequestCancellationMillis", android.os.SystemClock.elapsedRealtime() - pausedAt);
                InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
                assertThrows(IOException.class, () -> api.call("GET", "/must-not-restart", null, Void.class));
            } finally { release.countDown(); }
            worker[0].submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        vault.unlock();
        ChatEngine.Account saved = RelayApi.JSON.fromJson(new String(vault.get("account"), StandardCharsets.UTF_8), ChatEngine.Account.class);
        assertEquals(userId, saved.userId());
        assertEquals(deviceId, saved.deviceId());
        assertEquals("synthetic-fixture", saved.accessToken());
    }

    @Test public void avatarStorageFailureIsReportedWithoutEscapingTheUiCallback() throws Exception {
        signedInFixture(); notificationTransport(reference -> null);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                vault.close();
                setField(activity, "shownPhotoGeneration", -1L);
                invoke(activity, "refreshProfileAvatars");
            });
            awaitMainCondition(scenario, activity -> readField(activity, "engine") == null
                    && "Secure storage is unavailable. Your encrypted data has not been cleared.".equals(readField(activity, "storageError")));
        }
        vault.unlock();
        assertNotNull("A display failure must not clear the saved account", vault.get("account"));
    }

    @Test public void queuedExplicitSignOutStillClearsSessionWhenBackgrounding() throws Exception {
        signedInFixture(); notificationTransport(reference -> null);
        String identity = engine.identityCode();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService[] worker = new java.util.concurrent.ExecutorService[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                worker[0] = (java.util.concurrent.ExecutorService) readField(activity, "work");
                worker[0].execute(() -> {
                    started.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                });
                invoke(activity, "signOutDialog");
            });
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                scenario.moveToState(Lifecycle.State.STARTED);
            } finally { release.countDown(); }
            worker[0].submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        vault.unlock();
        assertNull(vault.get("account"));
        ChatEngine.Account saved = RelayApi.JSON.fromJson(new String(
                vault.get(AndroidVault.savedAccountPrefix(userId) + "account"), StandardCharsets.UTF_8), ChatEngine.Account.class);
        assertEquals(userId, saved.userId()); assertEquals(deviceId, saved.deviceId());
        assertEquals("", saved.accessToken()); assertNull(saved.refreshToken());
        vault.transaction(() -> { assertTrue(vault.restoreAccount(userId)); return null; });
        engine = new ChatEngine(vault);
        assertEquals(identity, engine.identityCode());
        assertFalse(engine.authenticated());
    }

    @Test public void queuedAvatarCannotReappearAfterBackgrounding() throws Exception {
        signedInFixture(); notificationTransport(reference -> null);
        byte[] image = SafeImages.profilePhoto(photo);
        try { engine.photos().update(image); }
        finally { Arrays.fill(image, (byte) 0); }
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService[] worker = new java.util.concurrent.ExecutorService[1];
        List<Object> avatars = new ArrayList<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            awaitMainCondition(scenario, activity -> ((List<?>) readField(activity, "photoAvatars")).stream()
                    .anyMatch(avatar -> readField(avatar, "bitmap") != null));
            scenario.onActivity(activity -> {
                avatars.addAll((List<?>) readField(activity, "photoAvatars"));
                worker[0] = (java.util.concurrent.ExecutorService) readField(activity, "reader");
                worker[0].execute(() -> {
                    started.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                });
            });
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    setField(activity, "shownPhotoGeneration", -1L);
                    invoke(activity, "refreshProfileAvatars");
                });
                scenario.moveToState(Lifecycle.State.STARTED);
            } finally { release.countDown(); }
            worker[0].submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertTrue(((List<?>) readField(activity, "photoAvatars")).isEmpty());
                for (Object avatar : avatars) {
                    assertNull(readField(avatar, "bitmap"));
                    assertNull(((ImageView) readField(avatar, "image")).getDrawable());
                }
            });
        }
        vault.unlock();
        assertNotNull("Backgrounding must retain the encrypted owner photo", vault.get("profile-photo"));
    }

    @Test public void chatTypingAndSendDoNotWaitForBusyEncryptedStorage() throws Exception {
        signedInFixture(); conversationsFixture();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                ((EditText) readField(activity, "composer")).requestFocus();
                invoke(activity, "refreshProfileAvatars");
                ((java.util.concurrent.ExecutorService) readField(activity, "work")).execute(() -> {
                    synchronized (vault) {
                        locked.countDown();
                        try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    }
                });
            });
            try {
                assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    EditText input = (EditText) readField(activity, "composer");
                    long started = android.os.SystemClock.elapsedRealtime();
                    invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer);
                    invoke(activity, "refreshContactStatus");
                    for (int index = 0; index < 12; index++) input.append("a");
                    ((View) readField(activity, "sendControl")).performClick();
                    assertEquals("", input.getText().toString());
                    assertNotNull(text(root(activity), "Sending"));
                    input.setText("Next draft");
                    long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                    // The vault stays locked for 2 s, so waiting on it would show as seconds. The bound leaves room for cold-start
                    // jitter on a loaded emulator: this scenario's median was about 90 ms before the reader worker existed.
                    assertTrue("Typing and send callbacks must not wait for the vault lock; took " + elapsed + "ms", elapsed < 250);
                    android.os.Bundle metrics = new android.os.Bundle(); metrics.putLong("chatInputMillis", elapsed);
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
                });
            } finally { release.countDown(); }
        }
    }

    @Test public void receiptBurstsReuseEightyMessageRowsAndExpiryClearsCachedText() throws Exception {
        signedInFixture(); conversationsFixture();
        for (int index = 0; index < 73; index++)
            entry(true, "Synthetic history " + index, null, ChatEnvelope.Expiry.HOUR_1, "PENDING", -50_000 + index);
        Map<Object, Object> original = new HashMap<>();
        Object[] composer = new Object[1];
        int[] scrollPosition = new int[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                original.putAll((Map<?, ?>) readField(activity, "messageRows"));
                assertEquals(80, original.size());
                composer[0] = readField(activity, "composer");
                ((EditText) composer[0]).setText("Keep typing through receipts");
                ((EditText) composer[0]).requestFocus(); ((EditText) composer[0]).setSelection(5);
                ScrollView scroll = (ScrollView) readField(activity, "messageScroll");
                scroll.scrollTo(0, 400); scrollPosition[0] = scroll.getScrollY();
            });
            for (String state : List.of("QUEUED", "DELIVERED", "READ")) {
                vault.transaction(() -> {
                    for (ChatEngine.Entry entry : engine.entries(peerId))
                        if (entry.outgoing()) write("entry/" + entry.id(), entry.withState(state));
                    return null;
                });
                scenario.onActivity(activity -> {
                    for (int wake = 0; wake < 30; wake++)
                        invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer);
                });
                awaitMessages(scenario);
                scenario.onActivity(activity -> {
                    Map<?, ?> rows = (Map<?, ?>) readField(activity, "messageRows");
                    assertEquals(80, rows.size());
                    original.forEach((id, row) -> {
                        assertSame("Receipts must not recreate message views", row, rows.get(id));
                        ChatEngine.Entry entry = (ChatEngine.Entry) readField(row, "entry");
                        if (entry.outgoing()) assertEquals(state, entry.state());
                    });
                    assertSame(composer[0], readField(activity, "composer"));
                    assertEquals("Keep typing through receipts", ((EditText) composer[0]).getText().toString());
                    assertTrue(((EditText) composer[0]).hasFocus());
                    assertEquals(5, ((EditText) composer[0]).getSelectionStart());
                    assertEquals(scrollPosition[0], ((ScrollView) readField(activity, "messageScroll")).getScrollY());
                });
            }
            ChatEngine.Entry fresh = entry(false, "A fresh reply", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", 0);
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                Map<?, ?> rows = (Map<?, ?>) readField(activity, "messageRows");
                assertEquals(81, rows.size());
                original.forEach((id, row) -> assertSame(row, rows.get(id)));
                assertNotNull(text(root(activity), "A fresh reply"));
            });
            assertEquals("READ", engine.entries(peerId).stream().filter(entry -> entry.id().equals(fresh.id())).findFirst().orElseThrow().state());
            assertNotNull("Rendering must not consume a view-once message", vault.get("body/" + once.id()));
            ChatEngine.Entry expiring = entry(false, "Short lived reply", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -3_597_000);
            TextView[] expiredBody = new TextView[1];
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            awaitMessages(scenario);
            scenario.onActivity(activity -> expiredBody[0] = (TextView) text(root(activity), "Short lived reply"));
            awaitMainCondition(scenario, activity -> !((Map<?, ?>) readField(activity, "messageRows")).containsKey(expiring.id()));
            assertEquals("Expired text must not remain in the row cache", "", expiredBody[0].getText().toString());
        }
    }

    @Test public void storedSendAppearsBeforeTheRelayUploadReturns() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "Synthetic peer");
        vault.transaction(() -> {
            engine.groupSignal().verifyPeer(peerId, remote.publicIdentity());
            write("contact/" + peerId, peer); return null;
        });
        engine.groupSignal().establish(peerId, remote.generatePreKey(Instant.now()), Instant.now());
        notificationTransport(reference -> null);
        var uploading = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var sent = new java.util.concurrent.atomic.AtomicReference<ChatEngine.Send>();
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            if (chain.request().method().equals("POST") && chain.request().url().encodedPath().equals("/messages")) {
                okio.Buffer body = new okio.Buffer(); chain.request().body().writeTo(body);
                ChatEngine.Send message = RelayApi.JSON.fromJson(body.readUtf8(), ChatEngine.Send.class); sent.set(message);
                uploading.countDown();
                try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Synthetic upload interrupted"); }
                return syntheticResponse(chain.request(), 200, new ChatEngine.Status(message.id(), "QUEUED", message.expiresAt()));
            }
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            try {
                scenario.onActivity(activity -> {
                    ((EditText) readField(activity, "composer")).setText("Durable before upload");
                    ((View) readField(activity, "sendControl")).performClick();
                    ((EditText) readField(activity, "composer")).setText("Next draft");
                });
                assertTrue(uploading.await(10, java.util.concurrent.TimeUnit.SECONDS));
                awaitMainCondition(scenario, activity -> ((Map<?, ?>) readField(activity, "messageRows")).containsKey(sent.get().id()));
                scenario.onActivity(activity -> {
                    assertTrue(((List<?>) readField(activity, "pendingSends")).isEmpty());
                    assertNotNull(text(root(activity), "Durable before upload"));
                    assertEquals("Next draft", ((EditText) readField(activity, "composer")).getText().toString());
                    Object row = ((Map<?, ?>) readField(activity, "messageRows")).get(sent.get().id());
                    assertEquals("PENDING", ((ChatEngine.Entry) readField(row, "entry")).state());
                });
            } finally { release.countDown(); }
            awaitMessages(scenario);
        }
    }

    @Test public void typingDoesNotWaitForPresenceAudienceStorageReads() throws Exception {
        signedInFixture(); conversationsFixture();
        ContactPresence presence = engine.presence(); presence.foreground(true); presence.conversation(peerId);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = java.util.concurrent.Executors.newSingleThreadExecutor();
        var reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        var reading = new java.util.concurrent.atomic.AtomicReference<Thread>();
        // Reads no longer wait for a commit, so stall them where they can still wait: the lock that guards value wiping.
        var wipeLock = (java.util.concurrent.locks.ReentrantReadWriteLock) readField(vault, "arrays");
        try {
            holder.submit(() -> {
                wipeLock.writeLock().lock();
                try {
                    locked.countDown();
                    try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                } finally { wipeLock.writeLock().unlock(); }
            });
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var result = reader.submit(() -> { reading.set(Thread.currentThread()); return presence.update(android.os.SystemClock.elapsedRealtime()); });
            awaitPhotoCondition(() -> reading.get() != null && reading.get().getState() == Thread.State.WAITING);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                long started = android.os.SystemClock.elapsedRealtime();
                presence.edited(peerId, true);
                long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                assertTrue("A keystroke must not wait for presence to read the vault; took " + elapsed + "ms", elapsed < 100);
            });
            release.countDown();
            assertEquals(peerId, result.get(5, java.util.concurrent.TimeUnit.SECONDS).typingTo());
        } finally { release.countDown(); holder.shutdownNow(); reader.shutdownNow(); }
    }

    @Test public void backgroundingDropsQueuedConversationReadsAndClearsRetainedRows() throws Exception {
        signedInFixture(); conversationsFixture();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService[] worker = new java.util.concurrent.ExecutorService[1];
        TextView[] oldBody = new TextView[1];
        ChatEngine.Entry unread;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            unread = entry(false, "Unread after leaving", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", 0);
            scenario.onActivity(activity -> {
                oldBody[0] = (TextView) text(root(activity), "Coffee at 4?");
                worker[0] = (java.util.concurrent.ExecutorService) readField(activity, "reader");
                worker[0].execute(() -> {
                    started.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                });
            });
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
                scenario.moveToState(Lifecycle.State.STARTED);
                assertEquals("", oldBody[0].getText().toString());
            } finally { release.countDown(); }
            worker[0].submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        vault.unlock();
        ChatEngine.Entry stored = RelayApi.JSON.fromJson(new String(vault.get("entry/" + unread.id()), StandardCharsets.UTF_8), ChatEngine.Entry.class);
        assertEquals("A stale screen must not read an incoming message", "DELIVERED", stored.state());
        assertNotNull(vault.get("body/" + once.id()));
    }

    @Test public void realtimeWakeDuringSyncIsRetainedAndBurstsAreCoalesced() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var followUp = new java.util.concurrent.CountDownLatch(1);
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient transport = (okhttp3.OkHttpClient) readField(engine.groupApi(), "client");
        var builder = transport.newBuilder();
        builder.interceptors().add(0, chain -> {
            if (chain.request().url().encodedPath().equals("/messages/pending")) {
                if (polls.incrementAndGet() == 1) {
                    started.countDown();
                    try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Synthetic sync interrupted"); }
                } else followUp.countDown();
            }
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity, "queueSync"); });
            try {
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> { for (int index = 0; index < 100; index++) invoke(activity, "queueSync"); });
            } finally { release.countDown(); }
            assertTrue("A wake during sync must trigger another fetch without waiting for the 15s poll", followUp.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("A burst must not enqueue one full sync per notification", polls.get() <= 3);
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
                assertBrandLogo(activity);
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

    @Test public void googleDeletionPickerCancellationAfterBackgroundingShowsAnAccountBoundRetry() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        Object attempt = syntheticGoogleAttempt(); setField(attempt, "deletingAccount", userId);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity); setField(activity, "googleAttempt", attempt); invoke(activity, "render");
                assertGoogleProgress(activity);
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> invoke(activity, "googleFailed", new Class<?>[]{GoogleSignIn.Failure.class}, GoogleSignIn.Failure.CANCELLED));
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitMainCondition(scenario, activity -> dialog(activity) != null);
            scenario.onActivity(activity -> {
                engine = (ChatEngine) readField(activity, "engine"); vault = (AndroidVault) readField(engine, "vault");
                assertEquals(identity, engine.identityCode()); assertTrue(engine.usesGoogle());
                assertFalse(engine.safety().deletionPending());
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Account not deleted"));
                assertTrue(((TextView) text(dialog(activity).getWindow().getDecorView(),
                        GoogleSignIn.Failure.CANCELLED.message
                        + "\n\nKeep the app installed. Retrying requires a new confirmation; it does not clear your chats.")).getText().length() > 0);
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                assertEquals("Confirm with Google", sheet.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                assertTrue(descendants(sheet.getWindow().getDecorView()).stream().filter(EditText.class::isInstance)
                        .map(EditText.class::cast).allMatch(input -> input.length() == 0));
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertFalse(engine.safety().deletionPending());
                assertNull(readField(activity, "googleAttempt"));
                assertEquals(identity, engine.identityCode());
            });
        }
    }

    @Test public void googleDeletionAfterPickerReopensSameAccountAndErasesOnlyAfterConfirmation() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        Object attempt = syntheticGoogleAttempt(); setField(attempt, "deletingAccount", userId);
        var requests = new ArrayList<String>();
        String enrollment = "c".repeat(43);
        okhttp3.Interceptor transport = chain -> {
            var request = chain.request(); requests.add(request.url().encodedPath());
            if (request.url().encodedPath().equals("/auth/google")) {
                assertNull(request.header("Authorization"));
                return syntheticResponse(request, 200, new ChatEngine.GoogleResponse(
                        new ChatEngine.Token(userId, null, enrollment, System.currentTimeMillis() + 310_000), "alex"));
            }
            assertEquals("/account", request.url().encodedPath()); assertEquals("DELETE", request.method());
            assertEquals("Bearer " + enrollment, request.header("Authorization"));
            assertNotNull(vault.get("identity"));
            assertFalse(vault.pendingAccountDeletion().confirmed());
            return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(204).message("Synthetic deletion confirmed")
                    .body(okhttp3.ResponseBody.create(new byte[0], null)).build();
        };
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); setField(activity, "googleAttempt", attempt); invoke(activity, "render"); });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitMainCondition(scenario, activity -> readField(activity, "engine") != null);
            scenario.onActivity(activity -> {
                engine = (ChatEngine) readField(activity, "engine"); vault = (AndroidVault) readField(engine, "vault");
                assertEquals(identity, engine.identityCode());
                setField(engine, "safety", new AccountSafety(engine, vault, origin -> syntheticApi(transport),
                        () -> new okhttp3.OkHttpClient.Builder().addInterceptor(transport).build()));
                setField(attempt, "idToken", "synthetic-provider-proof");
                invoke(activity, "completeGoogle");
                assertGoogleProgress(activity);
            });
            awaitMainCondition(scenario, activity -> readField(activity, "engine") != null
                    && ((ChatEngine) readField(activity, "engine")).account() == null
                    && !(boolean) readField(activity, "completingGoogle"));
            scenario.onActivity(activity -> {
                engine = (ChatEngine) readField(activity, "engine"); vault = (AndroidVault) readField(engine, "vault");
                assertNull(vault.get("identity")); assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
                assertNull(readField(attempt, "idToken"));
                assertNotNull(text(root(activity), "Continue with Google"));
                assertNotNull(text(root(activity), "Account deleted. This account's data has been removed from this phone."));
            });
            assertEquals(List.of("/auth/google", "/account"), requests);
        }
    }

    @Test public void googleDeletionResultCannotSwitchToAnotherAccountAfterPicker() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        Object attempt = syntheticGoogleAttempt();
        setField(attempt, "deletingAccount", UUID.randomUUID());
        setField(attempt, "idToken", "synthetic-provider-proof");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); setField(activity, "googleAttempt", attempt); invoke(activity, "completeGoogle"); });
            awaitMainCondition(scenario, activity -> dialog(activity) != null);
            scenario.onActivity(activity -> {
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Account not deleted"));
                assertEquals(identity, engine.identityCode());
                assertTrue(engine.authenticated());
                assertNull(readField(attempt, "idToken"));
                assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
            });
        }
    }

    @Test public void googleDeletionErrorsExplainTheCauseAndRetryNeverSkipsConfirmation() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                for (Exception failure : List.of(
                        new AccountSafety.DeletionAuthorizationException(AccountSafety.DeletionAuthorizationException.Reason.DIFFERENT_ACCOUNT),
                        new AccountSafety.DeletionAuthorizationException(AccountSafety.DeletionAuthorizationException.Reason.EXPIRED),
                        new RelayApi.ApiFailure(429), new IOException("synthetic-private-error"))) {
                    invoke(activity, "deletionFailed", new Class<?>[]{Exception.class}, failure);
                    SecureSheet sheet = dialog(activity);
                    assertNotNull(text(sheet.getWindow().getDecorView(), "Account not deleted"));
                    assertFalse(descendants(sheet.getWindow().getDecorView()).stream().anyMatch(view -> view instanceof TextView label
                            && label.getText().toString().contains("synthetic-private-error")));
                    assertTrue((sheet.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                    sheet.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                    assertEquals(identity, engine.identityCode()); assertTrue(engine.authenticated());
                    assertNull(vault.get(AndroidVault.ACCOUNT_DELETION));
                }
            });
        }
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
            awaitMainCondition(scenario, activity -> ((List<?>) readField(activity, "photoAvatars")).stream()
                    .anyMatch(avatar -> peerId.equals(readField(avatar, "userId")) && readField(avatar, "bitmap") != null));
            snapshot(scenario, "63-saved-contact-photo");
            scenario.onActivity(activity -> {
                Object avatar = ((List<?>) readField(activity, "photoAvatars")).stream()
                        .filter(value -> peerId.equals(readField(value, "userId")) && ((View) readField(value, "frame")).isAttachedToWindow())
                        .findFirst().orElseThrow();
                assertNotNull(readField(avatar, "bitmap"));
                try { vault.transaction(() -> { engine.photos().purge("", System.currentTimeMillis() + 120_000); return null; }); }
                catch (Exception failure) { throw new AssertionError(failure); }
                invoke(activity, "refreshProfileAvatars");
                assertNull("An expired contact avatar must be cleared immediately", readField(avatar, "bitmap"));
                assertNull(((ImageView) readField(avatar, "image")).getDrawable());
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
        okhttp3.OkHttpClient transport = ((okhttp3.OkHttpClient) readField(api, "client")).newBuilder().addInterceptor(chain -> {
            if (chain.request().method().equals("GET") && chain.request().url().encodedPath().equals("/account/blocks"))
                return syntheticResponse(chain.request(), 200, List.of());
            return interceptor.intercept(chain);
        }).build();
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
            assertEquals(R.drawable.ic_stat_vanishr, notification.getSmallIcon().getResId());
            assertTrue(notification.contentIntent.isImmutable());
            manager.notify("vanishr-qa-tap", 900, notification);
            assertTrue(device.openNotification());
            var alert = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("New message")), 10_000);
            assertNotNull("The generic notification must be visible in the shade", alert);
            alert.click();
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.desc("Conversation options")), 15_000));
            // The header is there as soon as the chat opens; its messages are prepared on the reader lane and follow a moment later.
            boolean[] rowShown = new boolean[1];
            long rowDeadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            while (!rowShown[0] && android.os.SystemClock.elapsedRealtime() < rowDeadline) {
                instrumentation.runOnMainSync(() -> rowShown[0] = descendants(root(launched.get())).stream()
                        .anyMatch(view -> view instanceof TextView label && "View once".contentEquals(label.getText())));
                if (!rowShown[0]) Thread.sleep(20);
            }
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
            assertEquals(attempt == 1 ? null : userId, start.expectedUserId());
            return syntheticResponse(request, 200, new GoogleSignIn.Challenge((attempt == 1 ? "a" : "b").repeat(43),
                (attempt == 1 ? "c" : "d").repeat(43), BuildConfig.GOOGLE_WEB_CLIENT_ID, System.currentTimeMillis() + 300_000));
        })) {
            GoogleSignIn.Challenge first = GoogleSignIn.prepare(api);
            GoogleSignIn.Challenge second = GoogleSignIn.prepare(api, userId);
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
            });
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
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
            assertFalse("Contact metadata alone must not verify a group owner", engine.groups().ownerVerified(engine.groups().get(id)));
            engine.groupSignal().verifyPeer(peerId, Base64.getDecoder().decode(key));
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

    @Test public void anAccountWhoseSignInMovedFromGoogleToAPasswordComesBackWithItsSameDeviceAndKeys() throws Exception {
        signedInFixture(); conversationsFixture();
        vault.transaction(() -> { vault.put("google-account", new byte[]{1}); return null; });
        String identity = engine.identityCode();
        byte[] body = vault.get("body/" + once.id());
        engine.logout();
        vault.unlock(); engine = new ChatEngine(vault);
        assertNull(engine.account());
        var registrations = new java.util.concurrent.atomic.AtomicInteger();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request();
            if (request.url().encodedPath().equals("/devices")) {
                okio.Buffer buffer = new okio.Buffer(); request.body().writeTo(buffer);
                ChatEngine.DeviceRegistration registration = RelayApi.JSON.fromJson(buffer.readUtf8(), ChatEngine.DeviceRegistration.class);
                assertEquals("The saved device, not a new one", deviceId, registration.deviceId());
                assertFalse("Nothing is replaced", registration.replaceExisting());
                assertEquals("The saved identity, not a new one", identity, userId + ":" + Base64.getEncoder().encodeToString(registration.identityKey()));
                registrations.incrementAndGet();
                return syntheticResponse(request, 200, new ChatEngine.Token(userId, deviceId, "password-session", System.currentTimeMillis() + 3_600_000));
            }
            assertEquals("/keys", request.url().encodedPath());
            return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
        }));
        invoke(engine, "finishLogin", new Class<?>[]{ChatEngine.Token.class, String.class, UUID.class, boolean.class, boolean.class},
            new ChatEngine.Token(userId, null, "password-enrollment", System.currentTimeMillis() + 300_000), "alex", null, false, false);
        assertTrue(engine.authenticated()); assertFalse("It is a password account now", engine.usesGoogle());
        assertEquals(identity, engine.identityCode()); assertEquals(deviceId, engine.activeDeviceId());
        assertEquals(1, registrations.get()); assertEquals("password-session", engine.account().accessToken());
        assertArrayEquals(body, vault.get("body/" + once.id()));
        assertFalse(engine.peers().isEmpty());
        Arrays.fill(body, (byte) 0);
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

    @Test public void readableUsernamesPreserveProfileIdentityAndValidateGroupMembers() throws Exception {
        signedInFixture(); conversationsFixture();
        String identity = engine.identityCode();
        ChatEngine.Account original = engine.account();
        byte[] body = vault.get("body/" + once.id());
        assertEquals("quiet-otter-4827", ChatEngine.validUsername(" @QUIET-OTTER-4827 "));
        for (String legacy : List.of("g_0123456789abcdef0123456789ab", "alex", "custom_name123"))
            assertEquals(legacy, ChatEngine.validUsername(legacy));
        for (String invalid : List.of("quiet otter", "quiet/otter", "quiet.otter", "quiet%otter", "ab", "x".repeat(33)))
            assertThrows(IllegalArgumentException.class, () -> ChatEngine.validUsername(invalid));
        engine.applyProfile(new ChatEngine.Profile(userId, "quiet-otter-4827", null));
        assertEquals("quiet-otter-4827", engine.account().handle());
        assertEquals(original.userId(), engine.account().userId());
        assertEquals(original.deviceId(), engine.account().deviceId());
        assertEquals(original.accessToken(), engine.account().accessToken());
        assertEquals(identity, engine.identityCode());
        assertArrayEquals(body, vault.get("body/" + once.id()));
        engine.applyContactProfile(peerId, new ChatEngine.Profile(peerId, "calm-panda-1934", "Aarav"));
        ChatEngine.Peer updated = engine.peers().stream().filter(contact -> contact.userId().equals(peerId)).findFirst().orElseThrow();
        assertEquals(peer.deviceId(), updated.deviceId());
        assertEquals(peer.identityKey(), updated.identityKey());
        assertEquals("calm-panda-1934", updated.username());
        UUID groupId = UUID.randomUUID(), epoch = UUID.randomUUID();
        GroupChat.Snapshot group = new GroupChat.Snapshot(groupId, userId, 1, epoch, false, List.of(
                new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()),
                        "quiet-otter-4827", null, "ACTIVE", 0),
                new GroupChat.Member(peerId, peerDevice, peer.identityKey(), "calm-panda-1934", null, "ACTIVE", 0)));
        invoke(engine.groups(), "snapshot", new Class<?>[]{GroupChat.Snapshot.class}, group);
        assertEquals(group, engine.groups().get(groupId).snapshot());
        assertFalse("Readable usernames must not bypass membership approval", engine.groups().ready(engine.groups().get(groupId)));
        assertThrows(SecurityException.class, () -> ChatEngine.validateProfile(
                new ChatEngine.Profile(UUID.randomUUID(), "quiet-otter-4827", null), userId));
        engine.close(); vault.unlock(); engine = new ChatEngine(vault);
        assertEquals("quiet-otter-4827", engine.account().handle());
        assertEquals(identity, engine.identityCode());
        Arrays.fill(body, (byte) 0);
    }

    @Test public void readableUsernameCanBeFoundFromTheAddContactDialog() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        var requested = new java.util.concurrent.CountDownLatch(1);
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            String path = chain.request().url().encodedPath();
            if (path.equals("/users/quiet-otter-4827")) {
                requested.countDown();
                return syntheticResponse(chain.request(), 200, new ChatEngine.Contact(peerId, peerDevice, peer.identityKey()));
            }
            if (path.equals("/users/id/" + peerId + "/profile"))
                return syntheticResponse(chain.request(), 200, new ChatEngine.Profile(peerId, "quiet-otter-4827", "Aarav"));
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "addContactDialog", new Class<?>[]{String.class}, " @QUIET-OTTER-4827 ");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertTrue("Hyphenated lookup must reach the existing verified-contact flow", requested.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Safety number")), 10_000));
            scenario.onActivity(activity -> assertNotNull(text(dialog(activity).getWindow().getDecorView(), "@quiet-otter-4827")));
        }
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

    private void unlockPhotoFixture(String pin) throws Exception {
        if (pin == null) return;
        assertTrue("Use only the generated emulator credential", pin.matches("[0-9]{6}"));
        var keyguard = context.getSystemService(android.app.KeyguardManager.class);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        if (!keyguard.isDeviceLocked()) return;
        device.pressMenu();
        assertTrue("The emulator PIN field must be visible before entering the test credential", device.wait(
                androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.res("com.android.systemui", "pinEntry")), 15_000));
        for (char digit : pin.toCharArray()) device.pressKeyCode(android.view.KeyEvent.KEYCODE_0 + digit - '0');
        device.pressEnter(); awaitPhotoCondition(() -> !keyguard.isDeviceLocked());
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
        String pin = InstrumentationRegistry.getArguments().getString("devicePin");
        if (pin != null) assertTrue("Use only the generated emulator credential", pin.matches("[0-9]{6}"));
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        var keyguard = context.getSystemService(android.app.KeyguardManager.class);
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
            assertTrue(active.approvedOwner());
            scenario.moveToState(Lifecycle.State.CREATED);
            assertThrows(SecurityException.class, () -> vault.get("identity"));
            Field serviceField = PhotoSharingService.class.getDeclaredField("active"); serviceField.setAccessible(true);
            PhotoSharingService service = (PhotoSharingService) serviceField.get(null); assertNotNull(service);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ((android.content.BroadcastReceiver) readField(service, "screenOff"))
                    .onReceive(service, new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF)));
            if (pin != null) {
                device.sleep(); awaitPhotoCondition(keyguard::isDeviceLocked);
                assertFalse(PhotoSharingService.phoneUnlocked(context));
                assertTrue(PhotoSharingService.phonePermitsSession(context, active));
                AndroidVault lockedVault = new AndroidVault(context);
                try { assertThrows(AndroidVault.PhoneLockedException.class, lockedVault::unlock); }
                finally { lockedVault.close(); }
            }
            UUID query = UUID.randomUUID(); long imageId = android.content.ContentUris.parseId(image);
            server.queue(query, "GET", imageId, 0, 0, 0, false, null);
            ByteArrayOutputStream original = new ByteArrayOutputStream();
            for (;;) {
                RemotePhotoSession.Frame frame = server.take(); assertEquals("CHUNK", frame.action()); assertEquals(query, frame.requestId());
                assertEquals(original.size(), frame.offset()); original.write(frame.data()); if (!frame.more()) break;
            }
            assertArrayEquals("Background sharing must transfer the original bytes without reopening chat storage", photo, original.toByteArray());
            assertFalse(active.ended());
            if (pin != null) assertTrue("Original transfer must finish without unlocking the owner phone", keyguard.isDeviceLocked());
            var notifications = context.getSystemService(android.app.NotificationManager.class).getActiveNotifications();
            android.app.Notification notification = Arrays.stream(notifications).filter(value -> value.getId() == PhotoSharingService.NOTIFICATION).findFirst().orElseThrow().getNotification();
            assertEquals("Vanishr is active", notification.extras.getString(android.app.Notification.EXTRA_TITLE));
            assertTrue((notification.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0);
            assertTrue(notification.actions[0].actionIntent.isImmutable()); assertEquals("End access", notification.actions[0].title.toString());
            assertEquals(notification.actions[0].actionIntent, notification.deleteIntent);
            assertNotNull(notification.publicVersion);
            assertEquals(R.drawable.ic_stat_vanishr, notification.getSmallIcon().getResId());
            assertEquals(R.drawable.ic_stat_vanishr, notification.publicVersion.getSmallIcon().getResId());
            assertEquals("Vanishr is active", notification.publicVersion.extras.getString(android.app.Notification.EXTRA_TITLE));
            assertNull("Lock-screen notification must not disclose the contact", notification.publicVersion.extras.getString(android.app.Notification.EXTRA_TEXT));
            assertEquals(notification.actions[0].actionIntent, notification.publicVersion.actions[0].actionIntent);
            notification.publicVersion.actions[0].actionIntent.send();
            awaitPhotoCondition(() -> active.ended() && !PhotoSharingService.busy());
            assertFalse(active.approvedOwner());
            assertEquals(true, readField(prepared.vault, "closed"));
            assertThrows(SecurityException.class, () -> prepared.vault.get("identity"));
        } finally {
            unlockPhotoFixture(pin);
            context.getContentResolver().delete(image, null, null);
        }
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
        notificationTransport(reference -> null);
        var reportedPresence = new java.util.concurrent.atomic.AtomicReference<ContactPresence.Status>();
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            if (chain.request().url().encodedPath().equals("/presence")) {
                ContactPresence.Status state = reportedPresence.get();
                return syntheticResponse(chain.request(), 200, state == null ? List.of() : List.of(state));
            }
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                assertNotNull(text(root(activity), "Friend Public"));
                assertFalse(descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label
                        && Set.of("@friend_name", "Connected", "Offline", "Working").contains(label.getText().toString())));
                TextView status = (TextView) readField(activity, "contactStatus");
                assertEquals(View.GONE, status.getVisibility());
                setField(engine, "realtimeReady", true);
            });
            for (String label : List.of("Online", "Typing", "Last seen 23 hours ago")) {
                scenario.onActivity(activity -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    ContactPresence.Status state = label.startsWith("Last seen") ? new ContactPresence.Status(contact, 0, 0, 82_800_000L)
                            : new ContactPresence.Status(contact, 12_000, label.equals("Typing") ? 5000 : 0);
                    reportedPresence.set(state);
                    engine.presence().accept(new ContactPresence.Status[]{state}, List.of(contact), now, now);
                    invoke(activity, "refreshContactStatus");
                });
                awaitMainCondition(scenario, activity -> label.contentEquals(((TextView) readField(activity, "contactStatus")).getText()));
            }
            scenario.onActivity(activity -> {
                TextView status = (TextView) readField(activity, "contactStatus");
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
            awaitMainCondition(scenario, activity -> "Last seen 23 hours ago".contentEquals(((TextView) readField(activity, "contactStatus")).getText()));
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

    @Test public void contactStatusNeverBlinksWhileNewAnswersAndSyncPassesKeepArriving() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        List<String> shown = Collections.synchronizedList(new ArrayList<>());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                setField(engine, "realtimeReady", true);
                ((TextView) readField(activity, "contactStatus")).addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence value, int start, int before, int count) { shown.add(value.toString()); }
                    @Override public void afterTextChanged(android.text.Editable value) { }
                });
                long now = android.os.SystemClock.elapsedRealtime();
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(contact), now, now);
                invoke(activity, "refreshContactStatus");
            });
            awaitMainCondition(scenario, activity -> "Online".contentEquals(((TextView) readField(activity, "contactStatus")).getText())
                    && readField(activity, "shownPresence") != null);
            for (int pass = 0; pass < 8; pass++) {
                scenario.onActivity(activity -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(contact), now, now);
                    invoke(activity, "refreshContactStatus");
                    TextView status = (TextView) readField(activity, "contactStatus");
                    assertEquals("A fresh answer must not blank the header while it is being re-checked", "Online", status.getText().toString());
                    assertEquals(View.VISIBLE, status.getVisibility());
                    var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
                    try { worker.submit(() -> invoke(activity, "syncOnce")).get(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitMainCondition(scenario, activity -> readField(activity, "checkedPresence") == engine.presence().state(peerId)
                        && readField(activity, "shownPresence") != null);
                scenario.onActivity(activity -> {
                    TextView status = (TextView) readField(activity, "contactStatus");
                    assertEquals("Online", status.getText().toString()); assertEquals(View.VISIBLE, status.getVisibility());
                });
            }
            assertEquals("The status text must appear once and never change or empty in between", List.of("Online"), shown);
        }
    }

    @Test public void contactStatusStillDisappearsTheMomentTheIdentityOrTheAnswerGoesAway() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        ChatEngine.Contact contact = new ChatEngine.Contact(peerId, peerDevice, peer.identityKey());
        ChatEngine.Contact other = new ChatEngine.Contact(peerId, UUID.randomUUID(), peer.identityKey());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                setField(activity, "selectedPeer", peerId); inject(activity);
                setField(engine, "realtimeReady", true);
                long now = android.os.SystemClock.elapsedRealtime();
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(contact), now, now);
                invoke(activity, "refreshContactStatus");
            });
            awaitMainCondition(scenario, activity -> "Online".contentEquals(((TextView) readField(activity, "contactStatus")).getText()));
            scenario.onActivity(activity -> {
                TextView status = (TextView) readField(activity, "contactStatus");
                long now = android.os.SystemClock.elapsedRealtime();
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(other, 12_000, 0)}, List.of(other), now, now);
                invoke(activity, "refreshContactStatus");
                assertEquals("A different device or key must never inherit the shown status", "", status.getText().toString());
                assertEquals(View.GONE, status.getVisibility());
            });
            scenario.onActivity(activity -> {
                long now = android.os.SystemClock.elapsedRealtime();
                engine.presence().accept(new ContactPresence.Status[]{new ContactPresence.Status(contact, 12_000, 0)}, List.of(contact), now, now);
                invoke(activity, "refreshContactStatus");
            });
            awaitMainCondition(scenario, activity -> "Online".contentEquals(((TextView) readField(activity, "contactStatus")).getText()));
            scenario.onActivity(activity -> {
                TextView status = (TextView) readField(activity, "contactStatus");
                long now = android.os.SystemClock.elapsedRealtime();
                engine.presence().accept(new ContactPresence.Status[0], List.of(contact), now, now);
                invoke(activity, "refreshContactStatus");
                assertEquals("", status.getText().toString()); assertEquals(View.GONE, status.getVisibility());
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
            awaitMessages(scenario);
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
                SecureSheet choices = dialog(activity);
                assertEquals("Only view once, 12 hours and 24 hours are offered", 3, choices.getListView().getCount());
                choices.getListView().performItemClick(choices.getListView().getChildAt(2), 2, 2);
                assertEquals(ChatEnvelope.Expiry.HOURS_24, readField(activity, "expiry"));
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
            if (BuildConfig.PLAY_STORE) {
                scenario.onActivity(activity -> {
                    inject(activity); invoke(activity, "accountDialog");
                    assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Open Google Play"));
                    setField(activity, "availableUpdate", release);
                    invoke(activity, "dismissContent");
                    invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, false);
                    assertTrue(dialog(activity) == null || !dialog(activity).isShowing());
                    invoke(activity, "maybePromptUpdate", new Class<?>[]{boolean.class}, true);
                    assertTrue(dialog(activity) == null || !dialog(activity).isShowing());
                    invoke(activity, "checkForUpdates", new Class<?>[]{boolean.class}, false);
                    assertFalse((boolean) readField(activity, "checkingUpdates"));
                });
                return;
            }
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

    @Test public void vaultAndEngineReadsStayCheapWithManyContactsAndMessages() throws Exception {
        signedInFixture(); conversationsFixture();
        List<ChatEngine.Peer> many = manyVerifiedContacts(40, 3);
        String contactName = "contact/" + many.get(5).userId();
        long started = android.os.SystemClock.elapsedRealtimeNanos();
        for (int index = 0; index < 1000; index++) vault.get(contactName).clone();
        long gets = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000;
        perf("1000 vault.get calls (ms)", gets);
        started = android.os.SystemClock.elapsedRealtimeNanos();
        for (int index = 0; index < 100; index++) engine.peers();
        long peers = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000;
        perf("100 engine.peers() calls with 43 contacts (ms)", peers);
        started = android.os.SystemClock.elapsedRealtimeNanos();
        for (int index = 0; index < 20; index++) engine.entries(many.get(5).userId());
        long entries = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000;
        perf("20 engine.entries(peer) calls with 120+ messages (ms)", entries);
        started = android.os.SystemClock.elapsedRealtimeNanos();
        for (int index = 0; index < 20; index++) engine.unreadCounts();
        long unread = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000;
        perf("20 engine.unreadCounts() calls with 120+ messages (ms)", unread);
        // Before the fix these took about 2400 ms, 19900 ms and 6800 ms on the same emulator.
        assertTrue("Vault reads must not pay for a lock check each time; took " + gets + "ms", gets < 1_000);
        assertTrue("Listing contacts must stay cheap; took " + peers + "ms", peers < 5_000);
        assertTrue("Listing one conversation must stay cheap; took " + entries + "ms", entries < 3_000);
        assertTrue("Counting unread messages must stay cheap; took " + unread + "ms", unread < 3_000);
        assertEquals(many.get(5), engine.peer(many.get(5).userId()));
        assertNull(engine.peer(UUID.randomUUID()));
    }

    @Test public void vaultReadsNeverWaitForAnInFlightCommitAndOnlySeeCommittedState() throws Exception {
        signedInFixture();
        vault.transaction(() -> { vault.put("probe/a", "old".getBytes(StandardCharsets.UTF_8)); return null; });
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = java.util.concurrent.Executors.newSingleThreadExecutor();
        var commit = holder.submit(() -> vault.transaction(() -> {
            vault.put("probe/a", "new".getBytes(StandardCharsets.UTF_8));
            vault.put("probe/b", "added".getBytes(StandardCharsets.UTF_8));
            assertEquals("A transaction must see its own writes", "new", new String(vault.get("probe/a"), StandardCharsets.UTF_8));
            inside.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }));
        try {
            assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS));
            long started = android.os.SystemClock.elapsedRealtime();
            assertEquals("Other threads must see the last committed value", "old", new String(vault.get("probe/a"), StandardCharsets.UTF_8));
            assertNull(vault.get("probe/b"));
            assertFalse(vault.names("probe/").contains("probe/b"));
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            assertTrue("Reads must not wait for another thread's commit; took " + elapsed + "ms", elapsed < 500);
        } finally { release.countDown(); }
        commit.get(10, java.util.concurrent.TimeUnit.SECONDS);
        holder.shutdownNow();
        assertEquals("new", new String(vault.get("probe/a"), StandardCharsets.UTF_8));
        assertEquals("added", new String(vault.get("probe/b"), StandardCharsets.UTF_8));
        long revision = vault.revision();
        assertThrows(IllegalStateException.class, () -> vault.transaction(() -> {
            vault.put("probe/a", "failed".getBytes(StandardCharsets.UTF_8));
            vault.remove("probe/b");
            throw new IllegalStateException("Synthetic failure");
        }));
        assertEquals("A failed transaction must publish nothing", revision, vault.revision());
        assertEquals("new", new String(vault.get("probe/a"), StandardCharsets.UTF_8));
        assertEquals("added", new String(vault.get("probe/b"), StandardCharsets.UTF_8));
        byte[] copy = vault.get("probe/a");
        vault.transaction(() -> { vault.put("probe/a", "newer".getBytes(StandardCharsets.UTF_8)); return null; });
        assertEquals("A copy handed out earlier must stay intact when its record is replaced", "new", new String(copy, StandardCharsets.UTF_8));
        vault.close();
        assertThrows("A closed vault must refuse reads", SecurityException.class, () -> vault.get("probe/a"));
        assertThrows(SecurityException.class, () -> vault.names("probe/"));
    }

    @Test public void unreadCountsMatchCountingEachConversationSeparately() throws Exception {
        signedInFixture(); conversationsFixture();
        List<ChatEngine.Peer> many = manyVerifiedContacts(4, 6);
        UUID blocked = many.get(1).userId();
        vault.transaction(() -> { vault.put("blocked/" + blocked, new byte[]{1}); return null; });
        bulkEntry(many.get(2).userId(), many.get(2).deviceId(), false, "Expired synthetic message", -7_200_000L, "DELIVERED");
        ChatEngine.Entry opened = engine.entries(many.get(3).userId()).stream().filter(entry -> !entry.outgoing()).findFirst().orElseThrow();
        engine.markRead(List.of(opened));
        Map<UUID, Long> counts = engine.unreadCounts();
        List<UUID> conversations = new ArrayList<>(List.of(peerId));
        for (ChatEngine.Peer contact : many) conversations.add(contact.userId());
        for (UUID id : conversations) {
            long counted = engine.entries(id).stream().filter(entry -> !entry.outgoing() && !entry.state().equals("READ")).count();
            assertEquals("Unread messages of " + id, counted, counts.getOrDefault(id, 0L).longValue());
        }
        assertEquals("A blocked account's messages are never counted", 0L, counts.getOrDefault(blocked, 0L).longValue());
        assertEquals(4L, counts.get(many.get(0).userId()).longValue());
        assertEquals("An expired message is not unread", 4L, counts.get(many.get(2).userId()).longValue());
        assertEquals("A message that was read is not unread", 3L, counts.get(many.get(3).userId()).longValue());
        assertEquals("The view-once message still counts", 2L, counts.get(peerId).longValue());
    }

    @Test public void readReceiptsForAWholeScreenAreOneCommitAndSkipVanishedMessages() throws Exception {
        signedInFixture(); conversationsFixture();
        List<ChatEngine.Entry> unread = new ArrayList<>();
        for (int index = 0; index < 5; index++) unread.add(entry(false, "Batch " + index, null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -index * 1000L));
        ChatEngine.Entry vanished = entry(false, "Gone before the receipt", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -9000);
        vault.transaction(() -> { vault.remove("entry/" + vanished.id()); vault.remove("body/" + vanished.id()); return null; });
        List<ChatEngine.Entry> batch = new ArrayList<>(unread); batch.add(vanished);
        long revision = vault.revision();
        engine.markRead(batch);
        assertEquals("Receipts for one screen must be a single encrypted commit", revision + 1, vault.revision());
        for (ChatEngine.Entry entry : unread) {
            assertEquals("READ", RelayApi.JSON.fromJson(new String(vault.get("entry/" + entry.id()), StandardCharsets.UTF_8), ChatEngine.Entry.class).state());
            ChatEngine.Ack ack = RelayApi.JSON.fromJson(new String(vault.get("ack/" + entry.id()), StandardCharsets.UTF_8), ChatEngine.Ack.class);
            assertEquals("read", ack.action());
        }
        assertNull("A message that vanished must not be resurrected", vault.get("entry/" + vanished.id()));
        assertNull(vault.get("ack/" + vanished.id()));
        revision = vault.revision();
        engine.markRead(batch);
        assertEquals("Repeating receipts must not write again", revision, vault.revision());
        engine.markRead(List.of());
        assertEquals(revision, vault.revision());
    }

    @Test public void aReadReceiptStoredWhileDeliveredIsBeingSentIsNotLost() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        ChatEngine.Entry unread = engine.entries(peerId).stream()
                .filter(entry -> !entry.outgoing() && entry.expiry() != ChatEnvelope.Expiry.VIEW_ONCE && entry.state().equals("DELIVERED")).findFirst().orElseThrow();
        vault.transaction(() -> { write("ack/" + unread.id(), new ChatEngine.Ack(unread.id(), unread.expiresAt(), "delivered")); return null; });
        List<String> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            String path = chain.request().url().encodedPath();
            if (path.startsWith("/messages/" + unread.id())) {
                sent.add(path);
                // The reader worker opens the chat while "delivered" is on the wire and stores the read receipt under the same name.
                if (path.endsWith("/delivered")) {
                    try { engine.markRead(List.of(unread)); } catch (Exception failure) { throw new IOException("Synthetic receipt race failed"); }
                }
            }
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        invoke(engine, "flushAcks");
        assertEquals(List.of("/messages/" + unread.id() + "/delivered"), sent);
        assertNotNull("The read receipt must not be deleted together with the delivered one", vault.get("ack/" + unread.id()));
        ChatEngine.Ack kept = RelayApi.JSON.fromJson(new String(vault.get("ack/" + unread.id()), StandardCharsets.UTF_8), ChatEngine.Ack.class);
        assertEquals("The stored receipt must be the read one", "read", kept.action());
        invoke(engine, "flushAcks");
        assertEquals("/messages/" + unread.id() + "/read", sent.get(1));
        assertNull("A receipt that was sent is removed", vault.get("ack/" + unread.id()));
        assertTrue(engine.hasEntry(unread.id()));
        assertFalse("A message that is gone must be recognised as gone", engine.hasEntry(UUID.randomUUID()));
    }

    @Test public void aGroupReadReceiptStoredWhileDeliveredIsBeingSentIsNotLost() throws Exception {
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
            if (path.endsWith("/delivered") || path.endsWith("/read")) {
                acknowledgements.add(path);
                // The reader worker opens the chat while "delivered" is on the wire and stores the read receipt under the same name.
                if (path.endsWith("/delivered")) {
                    try { engine.markRead(List.of(engine.entries(groupId).get(0))); } catch (Exception failure) { throw new IOException("Synthetic receipt race failed"); }
                }
                return syntheticResponse(request,200,Map.of());
            }
            throw new IOException("Unexpected synthetic group request");
        }));
        createFixtureKey(AndroidVault.contentAlias(deadline,messageId,userId));
        engine.groups().sync();
        assertEquals(1,acknowledgements.size()); assertTrue(acknowledgements.get(0).endsWith("/delivered"));
        assertEquals("READ",engine.entries(groupId).get(0).state());
        assertNotNull("The read receipt must not be deleted together with the delivered one",vault.get("group-ack/"+messageId));
        GroupChat.Ack kept=RelayApi.JSON.fromJson(new String(vault.get("group-ack/"+messageId),StandardCharsets.UTF_8),GroupChat.Ack.class);
        assertEquals("The stored receipt must be the read one","read",kept.action());
        engine.groups().sync();
        assertTrue(acknowledgements.get(1).endsWith("/read"));
        assertNull("A receipt that was sent is removed",vault.get("group-ack/"+messageId));
    }

    @Test public void afterCommitActionsRunOnlyOnceThePublishedStateIsReadableAndNeverOnRollback() throws Exception {
        signedInFixture();
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            vault.transaction(() -> {
                vault.put("probe/a", "1".getBytes(StandardCharsets.UTF_8));
                vault.afterCommit(() -> {
                    try { seen.add("committed:" + new String(executor.submit(() -> vault.get("probe/a")).get(5, java.util.concurrent.TimeUnit.SECONDS), StandardCharsets.UTF_8)); }
                    catch (Exception failure) { seen.add("unreadable"); }
                });
                assertTrue("Nothing may run before the commit", seen.isEmpty());
                return null;
            });
        } finally { executor.shutdownNow(); }
        assertEquals("The action must run after other threads can read the change", List.of("committed:1"), seen);
        vault.afterCommit(() -> seen.add("immediate"));
        assertEquals("Outside a transaction the action runs at once", List.of("committed:1", "immediate"), seen);
        assertThrows(IllegalStateException.class, () -> vault.transaction(() -> {
            vault.put("probe/a", "2".getBytes(StandardCharsets.UTF_8));
            vault.afterCommit(() -> seen.add("rolled back"));
            throw new IllegalStateException("Synthetic failure");
        }));
        assertFalse("A rolled back transaction must not announce a change", seen.contains("rolled back"));
        vault.transaction(() -> { vault.afterCommit(() -> seen.add("unchanged")); return null; });
        assertTrue("A transaction that succeeds without changes still completes its actions", seen.contains("unchanged"));
        seen.clear();
        vault.transaction(() -> {
            vault.transaction(() -> { vault.put("probe/b", "3".getBytes(StandardCharsets.UTF_8)); vault.afterCommit(() -> seen.add("inner")); return null; });
            assertTrue("A nested transaction waits for the outer commit", seen.isEmpty());
            return null;
        });
        assertEquals(List.of("inner"), seen);
    }

    @Test public void aProfilePhotoSignalIsNeverSeenBeforeThePhotoCanBeRead() throws Exception {
        signedInFixture();
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity());
        saveProfilePeer(remote);
        List<ProfilePhotos.Packet> incoming = new ArrayList<>(); List<ProfilePhotos.Send> outgoing = new ArrayList<>();
        profileTransport(remote, incoming, outgoing);
        engine.photos().sync();
        ProfileEnvelope request = decryptProfile(remote, outgoing.remove(0));
        byte[] image = SafeImages.profilePhoto(photo);
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        var violation = new java.util.concurrent.atomic.AtomicReference<String>();
        var observed = new java.util.concurrent.atomic.AtomicBoolean();
        long before = engine.photos().generation();
        Thread poller = new Thread(() -> {
            while (running.get()) {
                // The avatar refresh reads the generation and then the photo; the photo must already be there.
                if (engine.photos().generation() != before) {
                    observed.set(true);
                    if (vault.get("profile-photo-cache/" + peerId) == null) violation.set("The generation moved before the photo could be read");
                    return;
                }
            }
        });
        poller.start();
        try {
            engine.photos().receive(profilePacket(remote, ProfileEnvelope.Action.UPDATE, request.requestId(), 2, image, System.currentTimeMillis() + 60_000));
            long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
            while (!observed.get() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(5);
        } finally { running.set(false); poller.join(5_000); Arrays.fill(image, (byte) 0); }
        assertTrue("The generation must change when a photo arrives", observed.get());
        assertNull(violation.get());
        assertNotNull(engine.photos().contact(peerId));
    }

    // Like entry(), but the content key is already gone when the record is published, so no refresh can ever read it.
    private ChatEngine.Entry entryWithoutItsKey(String text, long offset) throws Exception {
        UUID id = UUID.randomUUID(); long created = System.currentTimeMillis() + offset; long deadline = created + ChatEnvelope.Expiry.HOUR_1.milliseconds;
        String alias = AndroidVault.contentAlias(deadline, id);
        createFixtureKey(alias);
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId, created, deadline, ChatEnvelope.Expiry.HOUR_1, text, null);
        ChatEngine.Entry entry = new ChatEngine.Entry(id, peerId, deadline, ChatEnvelope.Expiry.HOUR_1, false, false, "DELIVERED");
        byte[] content = RelayApi.JSON.toJson(new ChatEngine.Content(envelope, null)).getBytes(StandardCharsets.UTF_8);
        byte[] sealed;
        try { sealed = vault.seal(alias, content); }
        finally { Arrays.fill(content, (byte) 0); }
        AndroidVault.deleteContentKey(deadline, id, null);
        vault.transaction(() -> { write("entry/" + id, entry); vault.put("body/" + id, sealed); return null; });
        return entry;
    }

    @Test public void aMessageThatVanishesWhileTheChatOpensIsSkippedButARealFailureIsStillReported() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            // The key of an erased message disappears a moment before its record does. The records are published without their key,
            // so a background refresh that happens to run now cannot read them and make the outcome depend on timing.
            ChatEngine.Entry visible = entry(false, "Visible after the vanished one", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -4000);
            ChatEngine.Entry vanishing = entryWithoutItsKey("About to vanish", -5000);
            var remover = new Thread(() -> {
                try {
                    Thread.sleep(250);
                    vault.transaction(() -> { vault.remove("entry/" + vanishing.id()); vault.remove("body/" + vanishing.id()); return null; });
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            remover.start();
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            awaitMessages(scenario);
            remover.join(5_000);
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                assertNotNull("The rest of the chat must still load", text(root(activity), "Visible after the vanished one"));
                assertTrue(descendants(root(activity)).stream().noneMatch(view -> view instanceof TextView label && "About to vanish".contentEquals(label.getText())));
            });
            // The same failure on a message that still exists is a real problem and must stop the refresh.
            ChatEngine.Entry broken = entryWithoutItsKey("Broken but present", -3000);
            ChatEngine.Entry later = entry(false, "Hidden by the failure", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -2000);
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            awaitMessages(scenario);
            scenario.onActivity(activity -> assertTrue("A real decryption failure must not be hidden",
                    descendants(root(activity)).stream().noneMatch(view -> view instanceof TextView label && "Hidden by the failure".contentEquals(label.getText()))));
            assertTrue(engine.hasEntry(later.id())); assertTrue(engine.hasEntry(broken.id()));
        }
    }

    @Test public void savedContactsPrepareLocallyAndOnlyUnknownOnesNeedTheNetwork() throws Exception {
        signedInFixture(); conversationsFixture();
        assertTrue("A saved contact needs no network round trip", engine.prepareSavedConversation(peer));
        ChatEngine.Peer stranger = new ChatEngine.Peer(UUID.randomUUID(), UUID.randomUUID(), peer.identityKey(), "Stranger");
        assertFalse("An unknown contact still needs its introduction", engine.prepareSavedConversation(stranger));
        ChatEngine.Peer changed = new ChatEngine.Peer(peerId, UUID.randomUUID(), peer.identityKey(), peer.name());
        assertThrows("A changed identity must fail closed", SecurityException.class, () -> engine.prepareSavedConversation(changed));
        vault.transaction(() -> { vault.put("blocked/" + peerId, new byte[]{1}); return null; });
        assertThrows("A blocked account must fail closed", SecurityException.class, () -> engine.prepareSavedConversation(peer));
    }

    @Test public void aSyncYieldsBetweenStepsWhenAnActionIsWaiting() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> { paths.add(chain.request().url().encodedPath()); return chain.proceed(chain.request()); });
        setField(engine.groupApi(), "client", builder.build());
        assertFalse("A waiting action must stop the sync at its first safe point", engine.sync(() -> true));
        assertFalse("No later step may start", paths.contains("/messages/pending"));
        paths.clear();
        assertTrue(engine.sync(() -> false));
        assertTrue(paths.contains("/messages/pending"));
        assertTrue("The outgoing receipts step must run when nothing waits", paths.stream().anyMatch(path -> path.startsWith("/messages/status")));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        paths.clear();
        assertFalse(engine.sync(() -> checks.incrementAndGet() >= 3));
        assertTrue("The pass must stop part way, not finish", paths.stream().noneMatch(path -> path.startsWith("/messages/status")));
    }

    @Test public void aRunningSyncStepsAsideForAUserActionAndThenResumes() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var acted = new java.util.concurrent.CountDownLatch(1);
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var statusCalls = new java.util.concurrent.atomic.AtomicInteger();
        var statusAtAction = new java.util.concurrent.atomic.AtomicInteger(-1);
        var resumed = new java.util.concurrent.CountDownLatch(1);
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            String path = chain.request().url().encodedPath();
            if (path.equals("/messages/pending")) {
                if (polls.incrementAndGet() == 1) {
                    started.countDown();
                    try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Synthetic sync interrupted"); }
                } else resumed.countDown();
            }
            if (path.startsWith("/messages/status")) statusCalls.incrementAndGet();
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { inject(activity); invoke(activity, "queueSync"); });
            try {
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(activity -> invoke(activity, "act", new Class<?>[]{Runnable.class},
                        (Runnable) () -> { statusAtAction.set(statusCalls.get()); acted.countDown(); }));
            } finally { release.countDown(); }
            assertTrue("The waiting action must run", acted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("The sync must stop before its next step instead of finishing first", 0, statusAtAction.get());
            assertTrue("The sync must resume behind the action", resumed.await(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test public void aLockedPhoneStopsVaultReadsAfterTheShortUnlockCache() throws Exception {
        signedInFixture();
        String pin = InstrumentationRegistry.getArguments().getString("devicePin");
        org.junit.Assume.assumeTrue("This test needs the generated emulator credential", pin != null);
        assertTrue("Use only the generated emulator credential", pin.matches("[0-9]{6}"));
        var keyguard = context.getSystemService(android.app.KeyguardManager.class);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertNotNull(vault.get("account"));
        boolean passed = false;
        try {
            device.sleep(); awaitPhotoCondition(keyguard::isDeviceLocked);
            Thread.sleep(AndroidVault.UNLOCK_CACHE_MILLIS + 150);
            assertThrows("Reads must fail closed once the phone is locked", AndroidVault.PhoneLockedException.class, () -> vault.get("account"));
            assertThrows(AndroidVault.PhoneLockedException.class, () -> vault.names(""));
            assertThrows(AndroidVault.PhoneLockedException.class, () -> vault.transaction(() -> null));
            passed = true;
        } finally {
            // A failure to unlock must not hide an assertion failure from the checks above.
            try { unlockAfterSleep(pin); }
            catch (Throwable unlockFailure) { if (passed) throw unlockFailure; }
        }
        assertNotNull("Unlocking the phone restores access without reopening the vault", vault.get("account"));
    }

    // The keyguard needs a moment to finish turning the screen off; waking it at once can leave the PIN pad unopened.
    private void unlockAfterSleep(String pin) throws Exception {
        Thread.sleep(2_000);
        AssertionError last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { unlockPhotoFixture(pin); return; }
            catch (AssertionError notReady) { last = notReady; Thread.sleep(3_000); }
        }
        throw last;
    }

    @Test public void openingAChatDoesNotWaitForAnEncryptedStorageCommit() throws Exception {
        signedInFixture(); conversationsFixture();
        manyVerifiedContacts(40, 3);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitMainCondition(scenario, activity -> readField(activity, "contactRows") != null && ((LinearLayout) readField(activity, "contactRows")).getChildCount() > 10);
            var holder = java.util.concurrent.Executors.newSingleThreadExecutor();
            AndroidVault active = (AndroidVault) readField(scenarioEngine(scenario), "vault");
            holder.execute(() -> {
                synchronized (active) {
                    locked.countDown();
                    try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }
            });
            try {
                assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long[] tap = new long[1];
                scenario.onActivity(activity -> {
                    View row = contactRow(activity, "Contact 020");
                    long started = android.os.SystemClock.elapsedRealtime();
                    row.performClick();
                    tap[0] = android.os.SystemClock.elapsedRealtime() - started;
                    assertNotNull(readField(activity, "conversationName"));
                });
                perf("verified contact tap on the main thread while a storage commit is running (ms)", tap[0]);
                assertTrue("Opening a chat must not wait for another thread's storage commit; took " + tap[0] + "ms", tap[0] < 1_000);
            } finally { release.countDown(); holder.shutdownNow(); }
            scenario.onActivity(activity -> invoke(activity, "hideContent"));
        }
    }

    private ChatEngine scenarioEngine(ActivityScenario<MainActivity> scenario) {
        ChatEngine[] holder = new ChatEngine[1];
        scenario.onActivity(activity -> holder[0] = (ChatEngine) readField(activity, "engine"));
        return holder[0];
    }

    @Test public void anAutomaticContactOpensImmediatelyWhileTheWorkLaneIsBusy() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        ChatEngine.Contact official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(own.publicIdentity()));
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        List<AdminOnboarding.Introduction> contacts = new ArrayList<>();
        for (int index = 1; index <= 64; index++) contacts.add(introduction(new UUID(0, index), UUID.randomUUID(), remote, String.format(Locale.ROOT, "acct_%03d", index)));
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, contacts, null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().first();
        for (int index = 0; index < 20; index++)
            for (int entry = 0; entry < 3; entry++) bulkEntry(contacts.get(index).userId(), contacts.get(index).deviceId(), entry == 0, "Synthetic message " + entry, -(entry * 1000L), "DELIVERED");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            long[] render = new long[1];
            scenario.onActivity(activity -> {
                long started = android.os.SystemClock.elapsedRealtime();
                inject(activity);
                render[0] = android.os.SystemClock.elapsedRealtime() - started;
            });
            perf("admin chat list render on the main thread with 64 automatic contacts and 60 messages (ms)", render[0]);
            // Before the fix this took about 26 s, because every row rescanned every stored message.
            assertTrue("The admin's list must not rescan all messages per contact; took " + render[0] + "ms", render[0] < 8_000);
            var started = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            scenario.onActivity(activity -> ((java.util.concurrent.ExecutorService) readField(activity, "work")).execute(() -> {
                started.countDown();
                try { release.await(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }));
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long tappedAt = android.os.SystemClock.elapsedRealtime();
                scenario.onActivity(activity -> contactRow(activity, "acct_010").performClick());
                awaitMainCondition(scenario, activity -> readField(activity, "selectedPeer") != null);
                long opened = android.os.SystemClock.elapsedRealtime() - tappedAt;
                perf("admin automatic contact tap to open conversation while the work lane is blocked (ms)", opened);
                assertTrue("A saved automatic contact must open without the work lane; took " + opened + "ms", opened < 1_500);
                scenario.onActivity(activity -> assertFalse("Opening a saved contact must not leave the screen busy", (boolean) readField(activity, "busy")));
                awaitMainCondition(scenario, activity -> ((Map<?, ?>) readField(activity, "messageRows")).size() == 3);
                scenario.onActivity(activity -> assertNotNull(text(root(activity), "Synthetic message 1")));
                assertEquals("The messages must have loaded while the work lane was still blocked", 1, release.getCount());
            } finally { release.countDown(); }
            scenario.onActivity(activity -> invoke(activity, "hideContent"));
        }
    }

    // ==== 0.6.1: long chats, keyboard, capacity ====

    private void longChat(int total) throws Exception {
        for (int index = 7; index < total; index++)
            bulkEntry(peerId, peerDevice, index % 2 == 0, "Synthetic message " + index + " with several words so that the chat is long enough",
                    -(total - index) * 1000L, "READ");
    }

    private ScrollView messageScroll(MainActivity activity) { return (ScrollView) readField(activity, "messageScroll"); }

    private int hiddenBelowViewport(ActivityScenario<MainActivity> scenario) {
        int[] hidden = new int[1];
        scenario.onActivity(activity -> {
            ScrollView scroll = messageScroll(activity);
            hidden[0] = scroll.getChildAt(0).getHeight() - (scroll.getScrollY() + scroll.getHeight());
        });
        return hidden[0];
    }

    private int imeBottom(MainActivity activity) {
        WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        return insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom;
    }

    // The keyboard animates in; wait until its height has stopped changing.
    private void awaitKeyboardSettled(ActivityScenario<MainActivity> scenario, boolean open) throws Exception {
        int[] last = {-1}; int stable = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + 20_000;
        while (stable < 5 && android.os.SystemClock.elapsedRealtime() < deadline) {
            int[] now = new int[1];
            scenario.onActivity(activity -> now[0] = imeBottom(activity));
            boolean expected = open ? now[0] > 0 : now[0] == 0;
            stable = expected && now[0] == last[0] ? stable + 1 : 0;
            last[0] = now[0];
            Thread.sleep(100);
        }
        assertEquals("The keyboard must finish " + (open ? "opening" : "closing"), 5, stable);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void openKeyboard(ActivityScenario<MainActivity> scenario) throws Exception {
        java.util.concurrent.CountDownLatch visible = new java.util.concurrent.CountDownLatch(1);
        int[] center = new int[2];
        ViewTreeObserver.OnGlobalLayoutListener[] listener = new ViewTreeObserver.OnGlobalLayoutListener[1];
        scenario.onActivity(activity -> {
            EditText input = (EditText) readField(activity, "composer"); input.requestFocus();
            input.getLocationOnScreen(center); center[0] += input.getWidth() / 2; center[1] += input.getHeight() / 2;
            View decor = activity.getWindow().getDecorView();
            listener[0] = () -> { if (imeBottom(activity) > 0) visible.countDown(); };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
            input.post(() -> androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), input)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime()));
        });
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(center[0], center[1]);
        try { assertTrue("Keyboard should open", visible.await(15, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { scenario.onActivity(activity -> activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener[0])); }
        awaitKeyboardSettled(scenario, true);
    }

    private void closeKeyboard(ActivityScenario<MainActivity> scenario) throws Exception {
        scenario.onActivity(activity -> activity.getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(((View) readField(activity, "composer")).getWindowToken(), 0));
        awaitKeyboardSettled(scenario, false);
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    public void theKeyboardPushesTheNewestMessagesUpWithTheComposer() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(40);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("A chat opens at its newest message", 0, hiddenBelowViewport(scenario));
            openKeyboard(scenario);
            int hidden = hiddenBelowViewport(scenario);
            perf("newest messages hidden behind the keyboard (px)", hidden);
            assertTrue("The newest messages must stay above the composer when the keyboard opens; " + hidden + "px were hidden", hidden <= 2);
            scenario.onActivity(activity -> {
                LinearLayout list = (LinearLayout) readField(activity, "messages");
                View newest = list.getChildAt(list.getChildCount() - 1);
                ScrollView scroll = messageScroll(activity);
                int[] newestAt = new int[2], scrollAt = new int[2];
                newest.getLocationOnScreen(newestAt); scroll.getLocationOnScreen(scrollAt);
                assertTrue("The newest message ends above the composer, not behind the keyboard",
                        newestAt[1] + newest.getHeight() <= scrollAt[1] + scroll.getHeight() + 2);
            });
            snapshot(scenario, "61-keyboard-newest-visible");
            entry(false, "A brand new reply while the keyboard is open", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", 0);
            scenario.onActivity(activity -> invoke(activity, "refreshMessages", new Class<?>[]{ChatEngine.Peer.class}, peer));
            awaitMessages(scenario);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("A message that arrives with the keyboard open is followed", hiddenBelowViewport(scenario) <= 2);
            scenario.onActivity(activity -> {
                assertTrue("The keyboard stays open", imeBottom(activity) > 0);
                assertTrue("Typing is not interrupted", ((EditText) readField(activity, "composer")).hasFocus());
                assertNotNull(text(root(activity), "A brand new reply while the keyboard is open"));
            });
            closeKeyboard(scenario);
            assertTrue("Closing the keyboard keeps the newest message in view", hiddenBelowViewport(scenario) <= 2);
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 30)
    public void aChatScrolledUpKeepsTheSameMessagesAboveTheComposerWhenTheKeyboardOpens() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(40);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> messageScroll(activity).scrollTo(0, 3000));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            int[] anchor = new int[2];
            // The last message that starts above the bottom edge of the list, and how far above that edge it starts.
            java.util.function.Consumer<int[]> measure = result -> scenario.onActivity(activity -> {
                ScrollView scroll = messageScroll(activity); LinearLayout list = (LinearLayout) readField(activity, "messages");
                int bottomEdge = scroll.getScrollY() + scroll.getHeight();
                if (result[0] == 0) for (int index = 0; index < list.getChildCount(); index++) if (list.getChildAt(index).getTop() < bottomEdge) result[0] = index + 1;
                result[1] = bottomEdge - list.getChildAt(result[0] - 1).getTop();
            });
            measure.accept(anchor);
            assertTrue("A message sits on the bottom edge of the list", anchor[0] > 0);
            openKeyboard(scenario);
            int[] after = {anchor[0], 0};
            measure.accept(after);
            assertEquals("The message that was on the bottom edge is still the one just above the composer", anchor[1], after[1], 2);
            closeKeyboard(scenario);
            int[] closed = {anchor[0], 0};
            measure.accept(closed);
            assertEquals("Closing the keyboard puts it back", anchor[1], closed[1], 2);
        }
    }

    @Test public void aLongChatShowsItsNewestMessagesBeforeTheOlderOnesAreReady() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(100);
        List<ChatEngine.Entry> expected = engine.entries(peerId);
        assertEquals(100, expected.size());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
            long[] started = new long[2];
            var finished = new java.util.concurrent.CountDownLatch(1);
            scenario.onActivity(activity -> {
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                started[0] = android.os.SystemClock.elapsedRealtime();
                setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                new Runnable() {
                    @Override public void run() {
                        int size = ((Map<?, ?>) readField(activity, "messageRows")).size();
                        if (size > 0 && (sizes.isEmpty() || sizes.get(sizes.size() - 1) != size)) {
                            sizes.add(size);
                            if (sizes.size() == 1) started[1] = android.os.SystemClock.elapsedRealtime();
                        }
                        if (size > 0 && !(boolean) readField(activity, "messageRefreshQueued")) finished.countDown();
                        else handler.postDelayed(this, 2);
                    }
                }.run();
            });
            assertTrue("The whole chat must finish loading", finished.await(60, java.util.concurrent.TimeUnit.SECONDS));
            perf("first rows of a 100 message chat appear after (ms)", started[1] - started[0]);
            perf("all 100 rows are ready after (ms)", android.os.SystemClock.elapsedRealtime() - started[0]);
            assertTrue("The first messages must appear after one small batch, not after the whole history: " + sizes, sizes.get(0) <= 32);
            assertTrue("The rest must follow in several steps: " + sizes, sizes.size() >= 3);
            scenario.onActivity(activity -> {
                Map<?, ?> rows = (Map<?, ?>) readField(activity, "messageRows");
                assertEquals(100, rows.size());
                LinearLayout list = (LinearLayout) readField(activity, "messages");
                List<View> order = new ArrayList<>();
                for (int index = 0; index < list.getChildCount(); index++) if (list.getChildAt(index).getTag() instanceof Long) order.add(list.getChildAt(index));
                assertEquals(expected.size(), order.size());
                for (int index = 0; index < expected.size(); index++)
                    assertSame("Messages stay in the order they were sent, however they were loaded; row " + index,
                            readField(rows.get(expected.get(index).id()), "alignment"), order.get(index));
                assertNotNull(text(root(activity), "Synthetic message 99 with several words so that the chat is long enough"));
                ScrollView shown = messageScroll(activity);
                int hiddenBelow = shown.getChildAt(0).getHeight() - (shown.getScrollY() + shown.getHeight());
                assertTrue("The chat is scrolled to its newest message: " + hiddenBelow + " px hidden below, scrollY " + shown.getScrollY()
                        + ", content " + shown.getChildAt(0).getHeight() + ", viewport " + shown.getHeight() + ", stages " + sizes, hiddenBelow <= 2);
            });
        }
    }

    // The reader can post the next stage in the few milliseconds between one stage's rows being added and the scroll to the bottom for
    // that stage being queued, so a stage can be applied after the previous rows have been laid out but before their scroll has run.
    // It must still follow the bottom instead of treating the chat as scrolled away from it. The two stages are applied by hand
    // with a layout pass in between so that the order is the same every time.
    @Test public void aStageAppliedBeforeTheScrollForThePreviousOneHasRunStillFollowsTheBottom() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(100);
        List<ChatEngine.Entry> all = engine.entries(peerId);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                invoke(activity, "resetConversation");
                LinearLayout list = (LinearLayout) readField(activity, "messages");
                list.removeAllViews();
                messageScroll(activity).scrollTo(0, 0);
                View decor = activity.getWindow().getDecorView();
                int widthSpec = View.MeasureSpec.makeMeasureSpec(decor.getWidth(), View.MeasureSpec.EXACTLY);
                int heightSpec = View.MeasureSpec.makeMeasureSpec(decor.getHeight(), View.MeasureSpec.EXACTLY);
                decor.measure(widthSpec, heightSpec); decor.layout(0, 0, decor.getWidth(), decor.getHeight());
                assertTrue("A chat that has just been opened is at its end", list.getHeight() - messageScroll(activity).getScrollY() - messageScroll(activity).getHeight() < dpPx(100));
                Map<UUID, String> text = new java.util.HashMap<>();
                for (ChatEngine.Entry entry : all)
                    text.put(entry.id(), "Synthetic message with several words so that the chat is long enough to wrap onto a second line here");
                Class<?>[] types = {ChatEngine.Peer.class, List.class, Map.class, Map.class, Map.class};
                invoke(activity, "applyMessages", types, peer, new ArrayList<>(all.subList(all.size() - 16, all.size())), text, new java.util.HashMap<UUID, String>(), Map.of());
                decor.measure(widthSpec, heightSpec); decor.layout(0, 0, decor.getWidth(), decor.getHeight());
                assertTrue("The first stage must be taller than the screen for this to be the case being tested",
                        list.getHeight() > messageScroll(activity).getHeight() + dpPx(100));
                invoke(activity, "applyMessages", types, peer, new ArrayList<>(all.subList(all.size() - 32, all.size())), text, new java.util.HashMap<UUID, String>(), Map.of());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals(32, ((Map<?, ?>) readField(activity, "messageRows")).size()));
            int hidden = hiddenBelowViewport(scenario);
            assertTrue("The newest message is in view, not the oldest of what was loaded first: " + hidden + " px hidden below", hidden <= 2);
        }
    }

    @Test public void olderMessagesBeyondTheFirstPageLoadWhenTheUserScrollsUpToThem() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(130);
        List<ChatEngine.Entry> expected = engine.entries(peerId);
        assertEquals(130, expected.size());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Map<?, ?> rows = (Map<?, ?>) readField(activity, "messageRows");
                assertEquals("Only the newest page is prepared when the chat opens", 100, rows.size());
                assertFalse("The oldest messages are not decrypted yet", rows.containsKey(expected.get(0).id()));
                assertTrue(rows.containsKey(expected.get(expected.size() - 1).id()));
                assertTrue((boolean) readField(activity, "historyMore"));
            });
            ChatEngine.Entry firstShown = expected.get(30);
            int[] offset = new int[1];
            scenario.onActivity(activity -> {
                ScrollView scroll = messageScroll(activity);
                scroll.scrollTo(0, 0);
                View row = (View) readField(((Map<?, ?>) readField(activity, "messageRows")).get(firstShown.id()), "alignment");
                offset[0] = row.getTop() - scroll.getScrollY();
            });
            awaitMainCondition(scenario, activity -> ((Map<?, ?>) readField(activity, "messageRows")).size() == 130
                    && !(boolean) readField(activity, "messageRefreshQueued"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Map<?, ?> rows = (Map<?, ?>) readField(activity, "messageRows");
                assertTrue(rows.containsKey(expected.get(0).id()));
                assertFalse((boolean) readField(activity, "historyMore"));
                ScrollView scroll = messageScroll(activity);
                View row = (View) readField(rows.get(firstShown.id()), "alignment");
                assertEquals("The message the user was looking at has not moved", offset[0], row.getTop() - scroll.getScrollY(), 2);
                assertTrue("Older messages are above it", ((View) readField(rows.get(expected.get(0).id()), "alignment")).getTop() < row.getTop());
            });
        }
    }

    // Relay and remote contact for a back-and-forth chat with the real engine.
    private final class ToFro {
        final SignalClient remote;
        final List<ChatEngine.Incoming> incoming = new ArrayList<>();
        final List<ChatEngine.Send> outgoing = new ArrayList<>();
        volatile java.util.concurrent.CountDownLatch statusGate;
        final java.util.concurrent.CountDownLatch statusReached = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger ackCalls = new java.util.concurrent.atomic.AtomicInteger();
        volatile int failAckCall = -1;
        // Messages whose receipts the relay answers "not found" to, as it does while two accounts are blocked from each other.
        final Set<UUID> refused = java.util.concurrent.ConcurrentHashMap.newKeySet();

        ToFro(boolean localStarts) throws Exception {
            remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
            peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "Aarav Mehta");
            vault.transaction(() -> { engine.groupSignal().verifyPeer(peerId, remote.publicIdentity()); write("contact/" + peerId, peer); return null; });
            remote.verifyPeer(userId, engine.groupSignal().publicIdentity());
            if (localStarts) engine.groupSignal().establish(peerId, remote.generatePreKey(Instant.now()), Instant.now());
            else remote.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
            setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
            engine.groupApi().close();
            setField(engine, "api", syntheticApi(chain -> {
                var request = chain.request(); String path = request.url().encodedPath();
                if (path.equals("/messages/pending")) { synchronized (incoming) { return syntheticResponse(request, 200, new ArrayList<>(incoming)); } }
                if (path.equals("/messages") && request.method().equals("POST")) {
                    okio.Buffer body = new okio.Buffer(); request.body().writeTo(body);
                    ChatEngine.Send sent = RelayApi.JSON.fromJson(body.readUtf8(), ChatEngine.Send.class);
                    synchronized (outgoing) { outgoing.add(sent); }
                    return syntheticResponse(request, 200, new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt()));
                }
                if (path.equals("/messages/status")) {
                    statusReached.countDown();
                    java.util.concurrent.CountDownLatch gate = statusGate;
                    if (gate != null) {
                        try { gate.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Synthetic status call interrupted"); }
                    }
                    synchronized (outgoing) { return syntheticResponse(request, 200, outgoing.stream().map(sent -> new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt())).toList()); }
                }
                if (path.matches("/messages/[0-9a-f-]+/[a-z]+") || request.method().equals("DELETE") && path.startsWith("/messages/")) {
                    int call = ackCalls.incrementAndGet();
                    if (call == failAckCall) return syntheticResponse(request, 500, Map.of("error", "synthetic"));
                    UUID id = UUID.fromString(path.split("/")[2]);
                    if (refused.contains(id)) return syntheticResponse(request, 404, Map.of("error", "not_found"));
                    synchronized (incoming) { incoming.removeIf(message -> message.id().equals(id)); }
                    return syntheticResponse(request, 200, null);
                }
                if (path.equals("/keys")) return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
                if (path.equals("/account/profile")) return syntheticResponse(request, 200, new ChatEngine.Profile(userId, "alex", "Alex"));
                if (path.equals("/users/id/" + peerId + "/profile")) return syntheticResponse(request, 200, new ChatEngine.Profile(peerId, "friend", peer.name()));
                if (path.equals("/users/id/" + peerId)) return syntheticResponse(request, 200, new ChatEngine.Contact(peerId, peerDevice, peer.identityKey()));
                if (Set.of("/groups", "/profile/packets", "/remote-photos", "/presence").contains(path)) return syntheticResponse(request, 200, List.of());
                if (path.equals("/events")) return syntheticResponse(request, 503, Map.of("error", "fixture_offline"));
                return syntheticResponse(request, 404, Map.of("error", "unhandled_in_fixture"));
            }));
        }

        // The remote contact has to read the first message it was sent before it can answer on that session.
        void readFirst() throws Exception {
            ChatEngine.Send sent; synchronized (outgoing) { sent = outgoing.get(0); }
            byte[] plaintext = remote.decrypt(userId, new SignalClient.Packet(sent.type(), sent.ciphertext()));
            Arrays.fill(plaintext, (byte) 0);
        }

        ChatEngine.Incoming reply(String text) throws Exception {
            UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + ChatEnvelope.Expiry.HOUR_1.milliseconds;
            ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId, now, deadline, ChatEnvelope.Expiry.HOUR_1, text, null);
            SignalClient.Packet packet = remote.encrypt(userId, RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
            ChatEngine.Incoming message = new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null);
            synchronized (incoming) { incoming.add(message); }
            return message;
        }
    }

    @Test public void aReceivedMessageIsShownBeforeTheRestOfTheSyncPassFinishes() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(false);
        // An earlier message of ours makes the pass ask the relay for receipts, after incoming messages have been stored.
        entry(true, "Our earlier message", null, ChatEnvelope.Expiry.HOUR_1, "QUEUED", -30_000);
        var gate = new java.util.concurrent.CountDownLatch(1);
        relay.statusGate = gate;
        relay.reply("A reply that must not wait for the rest of the pass");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            long woke = android.os.SystemClock.elapsedRealtime();
            scenario.onActivity(activity -> invoke(activity, "queueSync"));
            try {
                assertTrue("The pass must reach its receipts step", relay.statusReached.await(20, java.util.concurrent.TimeUnit.SECONDS));
                awaitMainCondition(scenario, activity -> hasRowText(activity, "A reply that must not wait for the rest of the pass"));
                perf("received message shown while the rest of the sync pass is still running (ms)", android.os.SystemClock.elapsedRealtime() - woke);
                assertEquals("The pass is still waiting, yet the message is already on screen", 1, gate.getCount());
            } finally { gate.countDown(); }
            awaitMessages(scenario);
        }
    }

    private boolean hasRowText(MainActivity activity, String text) {
        for (Object row : ((Map<?, ?>) readField(activity, "messageRows")).values())
            if (text.contentEquals(((TextView) readField(row, "body")).getText())) return true;
        return false;
    }

    @Test public void typingAndSendingStayQuickInAChatOfThreeHundredMessages() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(300);
        assertEquals(300, engine.entries(peerId).size());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            long[] worst = new long[2];
            for (int round = 0; round < 4; round++) {
                scenario.onActivity(activity -> {
                    EditText input = (EditText) readField(activity, "composer"); input.requestFocus();
                    long started = android.os.SystemClock.elapsedRealtime();
                    for (int index = 0; index < 12; index++) input.append("a");
                    worst[0] = Math.max(worst[0], android.os.SystemClock.elapsedRealtime() - started);
                    started = android.os.SystemClock.elapsedRealtime();
                    ((View) readField(activity, "sendControl")).performClick();
                    worst[1] = Math.max(worst[1], android.os.SystemClock.elapsedRealtime() - started);
                    assertNotNull(text(root(activity), "Sending"));
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                Thread.sleep(250);
            }
            perf("12 keystrokes in a 300 message chat, worst of 4 (ms)", worst[0]);
            perf("send tap in a 300 message chat, worst of 4 (ms)", worst[1]);
            assertTrue("Typing must not depend on the length of the chat; took " + worst[0] + "ms", worst[0] < 250);
            assertTrue("The send tap must not depend on the length of the chat; took " + worst[1] + "ms", worst[1] < 250);
        }
    }

    @Test public void theExpiryTickOnlyWalksTheMessagesWhenOneIsDue() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(60);
        // Eight seconds left: long enough to measure the idle ticks first, short enough to watch it expire.
        ChatEngine.Entry soon = entry(false, "About to expire", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -(3_600_000L - 8_000L));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            int[] scans = new int[1];
            scenario.onActivity(activity -> {
                assertTrue(((Map<?, ?>) readField(activity, "messageRows")).containsKey(soon.id()));
                Runnable tick = (Runnable) readField(activity, "expiryTick");
                scans[0] = (int) readField(activity, "rowScans");
                for (int index = 0; index < 40; index++) tick.run();
                assertEquals("With nothing due the tick must not walk the messages", scans[0], (int) readField(activity, "rowScans"));
            });
            awaitMainCondition(scenario, activity -> !((Map<?, ?>) readField(activity, "messageRows")).containsKey(soon.id()));
            scenario.onActivity(activity -> {
                assertTrue("The tick walked the messages when one expired", (int) readField(activity, "rowScans") > scans[0]);
                assertNull(descendants(root(activity)).stream().filter(view -> view instanceof TextView label && "About to expire".contentEquals(label.getText())).findFirst().orElse(null));
                int again = (int) readField(activity, "rowScans");
                Runnable tick = (Runnable) readField(activity, "expiryTick");
                for (int index = 0; index < 40; index++) tick.run();
                assertEquals("Idle again until the next one is due", again, (int) readField(activity, "rowScans"));
            });
        }
    }

    private ChatEngine.Entry plainRecord(boolean outgoing, String state, long deadline, boolean image) {
        return new ChatEngine.Entry(UUID.randomUUID(), peerId, deadline, ChatEnvelope.Expiry.HOUR_1, outgoing, image, state);
    }

    private void storeRecords(List<ChatEngine.Entry> records) throws Exception {
        vault.transaction(() -> { for (ChatEngine.Entry record : records) write("entry/" + record.id(), record); return null; });
    }

    // Plain records are enough to count against the limit: it is about how many a phone holds, not what they say.
    @Test public void aFullPhoneMakesRoomByRemovingWhatWouldExpireSoonestInsteadOfRefusing() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(true);
        engine.liveLimit = 40;
        long base = System.currentTimeMillis() + 600_000L;
        List<ChatEngine.Entry> unread = new ArrayList<>(), unsent = new ArrayList<>(), removable = new ArrayList<>(), all = new ArrayList<>();
        // The earliest deadlines of all, but not yet read: never taken.
        for (int index = 0; index < 5; index++) unread.add(plainRecord(false, "DELIVERED", base + index * 1000L, false));
        // Still waiting to be sent: never taken either.
        for (int index = 0; index < 3; index++) unsent.add(plainRecord(true, "PENDING", base + 10_000L + index * 1000L, false));
        for (int index = 0; index < 32; index++) removable.add(plainRecord(index % 2 == 0, index % 2 == 0 ? "QUEUED" : "READ", base + 100_000L + index * 1000L, false));
        all.addAll(unread); all.addAll(unsent); all.addAll(removable);
        storeRecords(all);
        vault.transaction(() -> {
            for (ChatEngine.Entry waiting : unsent)
                write("outbox/" + waiting.id(), new ChatEngine.Outbox(new ChatEngine.Send(waiting.id(), peerId, peerDevice, ChatEnvelope.Expiry.HOUR_1, waiting.expiresAt(), 1, new byte[]{1}, null), null));
            return null;
        });
        assertEquals(40, vault.names("entry/").size());
        engine.send(peer, "A message that needs room", null, ChatEnvelope.Expiry.HOUR_1);
        assertEquals("Two were made room for and one was added", 39, vault.names("entry/").size());
        for (int index = 0; index < 2; index++) assertNull("The messages that would expire soonest go first", vault.get("entry/" + removable.get(index).id()));
        for (ChatEngine.Entry kept : all) if (!removable.subList(0, 2).contains(kept)) assertNotNull(vault.get("entry/" + kept.id()));
        assertTrue("Nobody is told that a message was removed", vault.names("ack/").isEmpty());
        assertTrue("The new message is stored", engine.entries(peerId).stream().anyMatch(entry -> all.stream().noneMatch(record -> record.id().equals(entry.id()))));
        assertTrue("The new message really was sent", relay.outgoing.stream().anyMatch(sent -> all.stream().noneMatch(record -> record.id().equals(sent.id()))));
        // Sending again does not remove more: there is room now.
        engine.send(peer, "And another", null, ChatEnvelope.Expiry.HOUR_1);
        assertEquals(40, vault.names("entry/").size());
    }

    @Test public void anIncomingMessageAtTheLimitIsStoredAfterMakingRoom() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(false);
        engine.liveLimit = 40;
        long base = System.currentTimeMillis() + 600_000L;
        List<ChatEngine.Entry> read = new ArrayList<>();
        for (int index = 0; index < 40; index++) read.add(plainRecord(index % 2 == 0, index % 2 == 0 ? "QUEUED" : "READ", base + index * 1000L, false));
        storeRecords(read);
        ChatEngine.Incoming arriving = relay.reply("Arrives at a phone that is full");
        engine.sync();
        assertTrue("The message is stored, not left waiting", engine.entries(peerId).stream().anyMatch(entry -> entry.id().equals(arriving.id())));
        assertEquals(39, vault.names("entry/").size());
        for (int index = 0; index < 2; index++) assertNull(vault.get("entry/" + read.get(index).id()));
        assertNotNull(vault.get("seen/" + arriving.id()));
        synchronized (relay.incoming) { assertTrue("It was acknowledged", relay.incoming.isEmpty()); }
    }

    @Test public void aPhoneHoldingOnlyUnreadMessagesNeverRemovesThemAndRefusesWithAnExplanation() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(true);
        engine.liveLimit = 40;
        long base = System.currentTimeMillis() + 600_000L;
        List<ChatEngine.Entry> unread = new ArrayList<>();
        for (int index = 0; index < 40; index++) unread.add(plainRecord(false, "DELIVERED", base + index * 1000L, false));
        storeRecords(unread);
        ChatEngine.CapacityReached full = assertThrows(ChatEngine.CapacityReached.class,
                () -> engine.send(peer, "There is nothing that may be removed", null, ChatEnvelope.Expiry.HOUR_1));
        assertEquals("Local message capacity reached", full.getMessage());
        assertEquals("Not one unread message was touched", 40, vault.names("entry/").size());
        assertTrue("Nothing was sent", relay.outgoing.isEmpty());
        assertTrue("A refused message leaves nothing queued", vault.names("outbox/").isEmpty());
    }

    @Test public void anIncomingMessageWaitsAtTheRelayWhileEverythingIsUnreadAndArrivesOnceOneHasBeenRead() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(false);
        engine.liveLimit = 40;
        long base = System.currentTimeMillis() + 600_000L;
        List<ChatEngine.Entry> unread = new ArrayList<>();
        for (int index = 0; index < 40; index++) unread.add(plainRecord(false, "DELIVERED", base + index * 1000L, false));
        storeRecords(unread);
        ChatEngine.Incoming waiting = relay.reply("Waits at the relay");
        engine.sync();
        assertEquals(40, vault.names("entry/").size());
        assertNull(vault.get("seen/" + waiting.id()));
        synchronized (relay.incoming) { assertEquals("The relay keeps what was not accepted", 1, relay.incoming.size()); }
        engine.markRead(List.of(unread.get(5)));
        engine.sync();
        assertTrue(engine.entries(peerId).stream().anyMatch(entry -> entry.id().equals(waiting.id())));
        assertNull("The one that had been read made room", vault.get("entry/" + unread.get(5).id()));
        assertNotNull("An unread one was not touched", vault.get("entry/" + unread.get(4).id()));
        synchronized (relay.incoming) { assertTrue("Once stored it is acknowledged", relay.incoming.isEmpty()); }
    }

    @Test public void aStoreFullOfPhotosMakesRoomBeforeItsHardLimit() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(true);
        long base = System.currentTimeMillis() + 600_000L;
        byte[] body = new byte[3 << 20];
        List<ChatEngine.Entry> photos = new ArrayList<>(), texts = new ArrayList<>();
        ChatEngine.Entry unreadPhoto = plainRecord(false, "DELIVERED", base, true);
        for (int index = 0; index < 6; index++) photos.add(plainRecord(index % 2 == 0, index % 2 == 0 ? "QUEUED" : "READ", base + 1000L + index * 1000L, true));
        for (int index = 0; index < 5; index++) texts.add(plainRecord(true, "QUEUED", base + 500L + index * 100L, false));
        vault.transaction(() -> {
            write("entry/" + unreadPhoto.id(), unreadPhoto); vault.put("body/" + unreadPhoto.id(), body);
            for (ChatEngine.Entry photo : photos) { write("entry/" + photo.id(), photo); vault.put("body/" + photo.id(), body); }
            for (ChatEngine.Entry text : texts) write("entry/" + text.id(), text);
            return null;
        });
        assertTrue("The store is past its soft limit", vault.sizeBytes() > (20L << 20));
        engine.send(peer, "Needs room for its commit", null, ChatEnvelope.Expiry.HOUR_1);
        assertTrue("The store was brought down well below its limit: " + vault.sizeBytes(), vault.sizeBytes() <= (13L << 20));
        assertNotNull("An unread photo is never taken", vault.get("body/" + unreadPhoto.id()));
        for (ChatEngine.Entry text : texts) assertNotNull("Making room for size removes photos, not short messages", vault.get("entry/" + text.id()));
        int kept = 0;
        for (ChatEngine.Entry photo : photos) if (vault.get("entry/" + photo.id()) != null) kept++;
        assertTrue("The photos that would expire soonest were the ones removed", kept >= 1 && kept <= 3
                && vault.get("entry/" + photos.get(photos.size() - 1).id()) != null && vault.get("entry/" + photos.get(0).id()) == null);
        assertEquals("The send went through", 1, relay.outgoing.size());
    }

    @Test public void theOpenChatDropsTheMessagesThatWereMadeRoomForAndTheirKeysAreGone() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(true);
        engine.liveLimit = 40;
        List<ChatEngine.Entry> stored = new ArrayList<>();
        for (int index = 0; index < 40; index++)
            stored.add(entry(index % 2 == 0, "Stored message " + index, null, ChatEnvelope.Expiry.HOUR_1, index % 2 == 0 ? "QUEUED" : "READ", -(40 - index) * 1000L));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                assertEquals(40, ((Map<?, ?>) readField(activity, "messageRows")).size());
                ((EditText) readField(activity, "composer")).setText("A message that needs room");
                ((View) readField(activity, "sendControl")).performClick();
            });
            awaitMainCondition(scenario, activity -> ((Map<?, ?>) readField(activity, "messageRows")).size() == 39
                    && hasRowText(activity, "A message that needs room") && !(boolean) readField(activity, "messageRefreshQueued"));
            scenario.onActivity(activity -> {
                Map<?, ?> rows = (Map<?, ?>) readField(activity, "messageRows");
                for (int index = 0; index < 2; index++) assertFalse("The oldest message left the screen", rows.containsKey(stored.get(index).id()));
                assertTrue("The next oldest is still there", rows.containsKey(stored.get(2).id()));
                assertTrue(((List<?>) readField(activity, "pendingSends")).isEmpty());
                assertTrue("No send failed", descendants(root(activity)).stream().noneMatch(view -> view instanceof TextView label && "Not sent".contentEquals(label.getText())));
            });
        }
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        for (int index = 0; index < 2; index++)
            assertFalse("A removed message's key is deleted, so nothing can decrypt it any more",
                    keys.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(stored.get(index).expiresAt(), stored.get(index).id()))));
        assertTrue(keys.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(stored.get(2).expiresAt(), stored.get(2).id()))));
    }

    @Test public void acknowledgementsAreRemovedInOneCommitAndWhatTheRelayAcceptedSurvivesARejection() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(true);
        Method flush = ChatEngine.class.getDeclaredMethod("flushAcks"); flush.setAccessible(true);
        java.util.function.Supplier<List<UUID>> queue = () -> {
            List<UUID> ids = new ArrayList<>();
            try {
                vault.transaction(() -> {
                    for (int index = 0; index < 5; index++) {
                        UUID id = UUID.randomUUID(); ids.add(id);
                        write("ack/" + id, new ChatEngine.Ack(id, System.currentTimeMillis() + 3_600_000L, "delivered"));
                    }
                    return null;
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
            return ids;
        };
        queue.get();
        long revision = vault.revision();
        flush.invoke(engine);
        assertTrue(vault.names("ack/").isEmpty());
        assertEquals("Five receipts are one encrypted commit, not five", revision + 1, vault.revision());
        assertEquals(5, relay.ackCalls.get());
        queue.get();
        relay.failAckCall = relay.ackCalls.get() + 3;
        revision = vault.revision();
        InvocationTargetException rejected = assertThrows(InvocationTargetException.class, () -> flush.invoke(engine));
        assertTrue(rejected.getCause() instanceof RelayApi.ApiFailure);
        assertEquals("The two the relay accepted are removed, the rest wait for the next pass", 3, vault.names("ack/").size());
        assertEquals("...in a single commit", revision + 1, vault.revision());
    }

    @Test public void unchangedMessageRecordsAreNotParsedAgainWhenAChatIsListed() throws Exception {
        signedInFixture(); conversationsFixture(); longChat(150);
        long started = android.os.SystemClock.elapsedRealtime();
        assertEquals(150, engine.entries(peerId).size());
        long cold = android.os.SystemClock.elapsedRealtime() - started;
        int before = engine.entryParses.get();
        started = android.os.SystemClock.elapsedRealtime();
        List<ChatEngine.Entry> again = engine.entries(peerId);
        long warm = android.os.SystemClock.elapsedRealtime() - started;
        perf("listing a 150 message chat, first call (ms)", cold);
        perf("listing a 150 message chat, second call (ms)", warm);
        assertEquals("Nothing changed, so nothing is parsed again", before, engine.entryParses.get());
        ChatEngine.Entry changed = again.get(10);
        vault.transaction(() -> { write("entry/" + changed.id(), changed.withState("QUEUED")); return null; });
        List<ChatEngine.Entry> listed = engine.entries(peerId);
        assertEquals("Only the record that changed is parsed", before + 1, engine.entryParses.get());
        assertEquals("QUEUED", listed.stream().filter(entry -> entry.id().equals(changed.id())).findFirst().orElseThrow().state());
        engine.erase(changed, null);
        assertEquals(149, engine.entries(peerId).size());
        assertNull(engine.entry(changed.id()));
        int parsed = engine.entryParses.get();
        engine.purge();
        assertEquals("Purging reads from the same cache", parsed, engine.entryParses.get());
        assertEquals(149, engine.entries(peerId).size());
    }

    @Test public void aRemovedMessageKeepsItsSeenMarkerOnlyWhileTheRelayCouldStillHandItOverAgain() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(false);
        List<ChatEngine.Incoming> received = new ArrayList<>();
        for (int index = 0; index < 6; index++) { received.add(relay.reply("Received " + index)); Thread.sleep(5); }
        // The relay will accept the receipts for the first three and answer "not found" to the other three, as it does while the two
        // accounts are blocked from each other, and then it still has those and lists them again.
        for (int index = 3; index < 6; index++) relay.refused.add(received.get(index).id());
        engine.sync();
        assertEquals(6, engine.entries(peerId).size());
        assertEquals("Only the refused receipts are kept", 3, vault.names("ack/").size());
        for (int index = 0; index < 6; index++) {
            assertNotNull(vault.get("seen/" + received.get(index).id()));
            byte[] ack = vault.get("ack/" + received.get(index).id());
            if (index < 3) assertNull("Accepted, so the receipt is gone", ack);
            else assertEquals("Refused, so it is held rather than dropped", ChatEngine.HELD,
                    RelayApi.JSON.fromJson(new String(ack, StandardCharsets.UTF_8), ChatEngine.Ack.class).action());
        }
        assertEquals(6, relay.ackCalls.get());
        engine.markRead(engine.entries(peerId));
        engine.sync();
        assertEquals("Six receipts for the read messages, and the held ones were not sent a second time before that", 12, relay.ackCalls.get());
        engine.sync();
        assertEquals("A held receipt is never sent again", 12, relay.ackCalls.get());
        assertEquals(3, vault.names("ack/").size());
        synchronized (relay.incoming) { assertEquals("The relay still lists the three it refused", 3, relay.incoming.size()); }

        // A full phone removes the four that expire first: three whose receipts the relay accepted and one it refused.
        engine.liveLimit = 3;
        assertTrue(engine.makeRoom(0));
        assertEquals(2, vault.names("entry/").size());
        for (int index = 0; index < 4; index++) assertNull(vault.get("entry/" + received.get(index).id()));
        for (int index = 0; index < 3; index++) assertNull("Nothing can hand it over again, so no marker is kept", vault.get("seen/" + received.get(index).id()));
        assertNotNull("The relay may hand this one over again, so its marker stays with its held receipt", vault.get("seen/" + received.get(3).id()));
        assertNotNull(vault.get("ack/" + received.get(3).id()));
        for (int index = 4; index < 6; index++) assertNotNull(vault.get("seen/" + received.get(index).id()));
        assertEquals(3, vault.names("seen/").size());
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        for (int index = 0; index < 6; index++)
            assertEquals("A removed message has lost its key, the others still have theirs", index >= 4,
                    keys.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(received.get(index).expiresAt(), received.get(index).id(), userId))));

        // And it does: the removed message comes back from the relay and is recognised instead of failing to decrypt a second time.
        engine.sync();
        assertEquals("It was not stored again", 2, engine.entries(peerId).size());
        assertNull(vault.get("entry/" + received.get(3).id()));
        assertEquals(12, relay.ackCalls.get());

        // A message whose receipt is still waiting to be sent keeps its marker too, however the relay will answer it.
        ChatEngine.Incoming waiting = relay.reply("Received and read, its read receipt not sent yet");
        engine.sync();
        engine.markRead(engine.entries(peerId).stream().filter(entry -> entry.id().equals(waiting.id())).toList());
        assertNotNull(vault.get("ack/" + waiting.id()));
        engine.liveLimit = 1;
        assertTrue(engine.makeRoom(0));
        assertTrue(engine.entries(peerId).isEmpty());
        assertNotNull("Its receipt has not gone out yet, so the marker stays", vault.get("seen/" + waiting.id()));
        assertNotNull(vault.get("ack/" + waiting.id()));
    }

    @Test public void aRemovalThatDoesNotCommitNeverCostsAMessageItsKey() throws Exception {
        signedInFixture();
        ToFro relay = new ToFro(true);
        engine.liveLimit = 40;
        List<ChatEngine.Entry> stored = new ArrayList<>();
        for (int index = 0; index < 40; index++)
            stored.add(entry(index % 2 == 0, "Stored message " + index, null, ChatEnvelope.Expiry.HOUR_1, index % 2 == 0 ? "QUEUED" : "READ", -(40 - index) * 1000L));
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        // The removal runs inside an outer transaction that then fails, so the commit that would remove the records never happens.
        IOException failed = assertThrows(IOException.class, () -> vault.transaction(() -> {
            assertTrue(engine.makeRoom(0));
            throw new IOException("The commit failed");
        }));
        assertEquals("The commit failed", failed.getMessage());
        assertEquals(40, vault.names("entry/").size());
        for (int index = 0; index < 2; index++) {
            ChatEngine.Entry victim = stored.get(index);
            assertTrue("The rolled back message is still there", engine.hasEntry(victim.id()));
            assertTrue("...and its key was not touched",
                    keys.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(victim.expiresAt(), victim.id()))));
            assertEquals("...so a screen can still open it", "Stored message " + index, engine.content(victim, false, false).envelope().text());
        }
        // Done for real, the same removal takes the records and then the keys.
        assertTrue(engine.makeRoom(0));
        assertEquals(38, vault.names("entry/").size());
        for (int index = 0; index < 2; index++) {
            ChatEngine.Entry victim = stored.get(index);
            assertFalse(engine.hasEntry(victim.id()));
            assertFalse(keys.containsAlias(AndroidVault.currentAlias(AndroidVault.contentAlias(victim.expiresAt(), victim.id()))));
        }
        assertTrue(engine.hasEntry(stored.get(2).id()));
    }

    @Test public void aGroupReceiptTheRelayRefusedIsHeldAndItsMessageIsNotReceivedAgainAfterBeingRemovedForRoom() throws Exception {
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
            if (path.endsWith("/delivered") || path.endsWith("/read")) {
                acknowledgements.add(path);
                return syntheticResponse(request,404,Map.of("error","not_found"));
            }
            throw new IOException("Unexpected synthetic group request");
        }));
        createFixtureKey(AndroidVault.contentAlias(deadline,messageId,userId));
        engine.groups().sync();
        assertEquals(1,acknowledgements.size());
        assertNotNull("A refused receipt is kept",vault.get("group-ack/"+messageId));
        assertEquals("A refused receipt is held, not dropped",ChatEngine.HELD,
                RelayApi.JSON.fromJson(new String(vault.get("group-ack/"+messageId),StandardCharsets.UTF_8),GroupChat.Ack.class).action());
        engine.groups().sync();
        assertEquals("A held receipt is not sent again",1,acknowledgements.size());
        // Read (set directly, since opening it would queue a new receipt) and then removed to make room, with the relay still listing it.
        ChatEngine.Entry stored=engine.entries(groupId).get(0);
        vault.transaction(() -> { write("entry/"+stored.id(),stored.withState("READ")); return null; });
        engine.liveLimit=1;
        assertTrue(engine.makeRoom(0));
        assertTrue(engine.entries(groupId).isEmpty());
        assertNotNull("The relay may hand it over again, so its marker stays",vault.get("seen/"+messageId));
        engine.groups().sync();
        assertTrue("It is recognised, not received a second time",engine.entries(groupId).isEmpty());
        assertEquals(1,acknowledgements.size());
    }

    // ==== end 0.6.1 ====

    // ==== 0.6.2: signed settings that change without a new version ====

    private static final long HOUR_MS = 3_600_000L, DAY_MS = 24 * HOUR_MS;

    private RemoteSettings.Settings remoteSettings(Set<RemoteSettings.Flag> off, RemoteSettings.Notice notice, RemoteSettings.Update update) {
        long now = System.currentTimeMillis();
        return new RemoteSettings.Settings("test", 9, now - HOUR_MS, now + DAY_MS, off, notice, update);
    }

    private RemoteSettings.Notice remoteNotice(String id, boolean important, String path) {
        return new RemoteSettings.Notice(id, "Heads up " + id, "A notice " + id + " from the owner.", important, path,
                RemoteSettings.Build.ALL, 0, Integer.MAX_VALUE);
    }

    private boolean hasText(MainActivity activity, String value) {
        return descendants(root(activity)).stream().anyMatch(view -> view instanceof TextView label && value.contentEquals(label.getText()));
    }

    // Stands in for the browser: it matches only a browsable view of exactly this https page, and keeps it from really opening.
    private android.app.Instrumentation.ActivityMonitor pageMonitor(String host, String path) {
        android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_VIEW);
        filter.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
        filter.addDataScheme("https");
        filter.addDataAuthority(host, null);
        filter.addDataPath(path, android.os.PatternMatcher.PATTERN_LITERAL);
        return InstrumentationRegistry.getInstrumentation().addMonitor(filter, null, true);
    }

    // The signed settings are read on a background lane once the screen is up; a test waits for that before it puts its own in place.
    private ActivityScenario<MainActivity> launchSettled() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        awaitMainCondition(scenario, activity -> (boolean) readField(activity, "settingsLoaded"));
        return scenario;
    }

    @Test public void theEmbeddedKeysAreLoadedAfterTheScreenIsUpAndAStoredFileIsCheckedAgainstThemOnStart() throws Exception {
        signedInFixture(); conversationsFixture();
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        byte[] foreign = signedSettings(generator.generateKeyPair(), "t1", 7,
                "\"notice\":{\"id\":\"x\",\"title\":\"Not from the owner\",\"body\":\"Signed by somebody else.\",\"level\":\"important\"}");
        assertTrue(context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE).edit()
                .putString("envelope", new String(foreign, StandardCharsets.UTF_8)).putLong("floor.t1", 7).commit());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream list = context.getResources().openRawResource(R.raw.ota_keys)) {
            byte[] chunk = new byte[1024];
            for (int read; (read = list.read(chunk)) > 0; ) bytes.write(chunk, 0, read);
        }
        Map<String, java.security.PublicKey> shipped = RemoteSettings.keys(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        assertFalse("The app ships at least one key", shipped.isEmpty());
        assertTrue("and at most three", shipped.size() <= 3);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                assertEquals("The keys in use are the ones the app ships", shipped, readField(activity, "settingsKeys"));
                assertEquals("A stored file signed by anybody else is not in force", 0, ((RemoteSettings.Settings) readField(activity, "remote")).sequence());
                assertFalse(hasText(activity, "Not from the owner"));
            });
        }
    }

    @Test public void aSignedNoticeShowsOnTheChatListOpensAPageOnTheDownloadSiteAndStaysDismissed() throws Exception {
        signedInFixture(); conversationsFixture();
        var monitor = pageMonitor("vanishr-download.vercel.app", "/security/");
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                assertFalse("Nothing is shown until settings have been accepted", hasText(activity, "Heads up n1"));
                setField(activity, "remote", remoteSettings(Set.of(), remoteNotice("n1", false, "/security/"), null));
                inject(activity);
                assertTrue(hasText(activity, "Heads up n1"));
                assertTrue(hasText(activity, "A notice n1 from the owner."));
                assertTrue(hasText(activity, "Dismiss"));
                text(root(activity), "Learn more").performClick();
            });
            assertEquals("Learn more opens exactly that page on the download site", 1, monitor.getHits());
            snapshot(scenario, "70-signed-notice");
            scenario.onActivity(activity -> {
                text(root(activity), "Dismiss").performClick();
                assertEquals("n1", context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE).getString("dismissed-notice", ""));
                assertFalse(hasText(activity, "Heads up n1"));
                invoke(activity, "render");
                assertFalse("A dismissed notice stays dismissed when the screen is drawn again", hasText(activity, "Heads up n1"));
                setField(activity, "remote", remoteSettings(Set.of(), remoteNotice("n2", true, null), null));
                invoke(activity, "render");
                assertTrue("A different notice is shown", hasText(activity, "Heads up n2"));
                assertFalse("No path, no link", hasText(activity, "Learn more"));
                setField(activity, "remote", RemoteSettings.Settings.NONE);
                invoke(activity, "render");
                assertFalse(hasText(activity, "Heads up n2"));
            });
        } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor); }
    }

    @Test public void aNoticeForTheOtherBuildOrAnExpiredFileIsNotShown() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                long now = System.currentTimeMillis();
                RemoteSettings.Notice forPlay = new RemoteSettings.Notice("p", "Only for Play", "Body", false, null, RemoteSettings.Build.PLAY, 0, Integer.MAX_VALUE);
                setField(activity, "remote", remoteSettings(Set.of(), forPlay, null));
                inject(activity);
                assertFalse("This build is not a Google Play build", hasText(activity, "Only for Play"));
                setField(activity, "playBuild", true);
                invoke(activity, "render");
                assertTrue(hasText(activity, "Only for Play"));
                RemoteSettings.Notice newer = new RemoteSettings.Notice("v", "Only for newer", "Body", false, null, RemoteSettings.Build.ALL, BuildConfig.VERSION_CODE + 1, Integer.MAX_VALUE);
                setField(activity, "remote", remoteSettings(Set.of(), newer, null));
                invoke(activity, "render");
                assertFalse("Not for this version", hasText(activity, "Only for newer"));
                setField(activity, "remote", new RemoteSettings.Settings("test", 9, now - 2 * DAY_MS, now - 1, Set.of(RemoteSettings.Flag.GROUPS), remoteNotice("old", false, null), null));
                invoke(activity, "render");
                assertFalse("A file stops counting the moment it expires", hasText(activity, "Heads up old"));
                assertTrue((boolean) invoke(activity, "allowed", new Class<?>[]{RemoteSettings.Flag.class}, RemoteSettings.Flag.GROUPS));
            });
        }
    }

    @Test public void aNewerVersionIsHintedAtOnlyOnGooglePlayBuildsAndAnUnsupportedOneCannotBeDismissed() throws Exception {
        signedInFixture(); conversationsFixture();
        var monitor = pageMonitor("play.google.com", "/store/apps/details");
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                RemoteSettings.Update available = new RemoteSettings.Update(BuildConfig.VERSION_CODE + 1, "9.9.9", 0);
                setField(activity, "remote", remoteSettings(Set.of(), null, available));
                inject(activity);
                assertFalse("The direct-download build has its own update prompt", hasText(activity, "Update available"));
                setField(activity, "playBuild", true);
                invoke(activity, "render");
                assertTrue(hasText(activity, "Update available"));
                assertTrue(hasText(activity, "Vanishr 9.9.9 is on Google Play."));
                text(root(activity), "Open Google Play").performClick();
                text(root(activity), "Dismiss").performClick();
                assertFalse(hasText(activity, "Update available"));
                invoke(activity, "render");
                assertFalse("A dismissed hint stays dismissed", hasText(activity, "Update available"));
                RemoteSettings.Update unsupported = new RemoteSettings.Update(BuildConfig.VERSION_CODE + 1, "9.9.9", BuildConfig.VERSION_CODE + 1);
                setField(activity, "remote", remoteSettings(Set.of(), null, unsupported));
                invoke(activity, "render");
                assertTrue(hasText(activity, "Please update Vanishr"));
                assertFalse("Not dismissible", hasText(activity, "Dismiss"));
                assertTrue(hasText(activity, "Open Google Play"));
                assertTrue("Chats are still listed: it is a notice, not a lock", hasText(activity, "Aarav Mehta"));
            });
            assertEquals("Open Google Play goes to the app's own listing", 1, monitor.getHits());
        } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor); }
    }

    @Test public void theSignedSettingsCanTurnOffCreatingGroupsSendingImagesAndOpeningPhotosOnlyByName() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Entry photoOnce = entry(false, null, photo, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -5_000);
        assertEquals("Sending images is turned off for now. Text messages still work.", RemoteSettings.Flag.IMAGES.refusal);
        assertEquals("Opening photos is turned off for now.", RemoteSettings.Flag.PHOTOS.refusal);
        assertEquals("Creating groups is turned off for now.", RemoteSettings.Flag.GROUPS.refusal);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "remote", remoteSettings(Set.of(), null, null));
                inject(activity);
                invoke(activity, "newGroupDialog");
                assertNotNull("Everything is on unless the signed settings say otherwise", dialog(activity));
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "New group"));
                invoke(activity, "dismissContent");
                invoke(activity, "viewContent", new Class<?>[]{ChatEngine.Entry.class}, photoOnce);
                assertNotNull("A view-once photo asks first", dialog(activity));
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "View once"));
                invoke(activity, "dismissContent");
                setField(activity, "selectedPeer", peerId); invoke(activity, "render");
                invoke(activity, "attachmentDialog");
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Send an image"));
                invoke(activity, "dismissContent");
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.IMAGES), null, null));
                invoke(activity, "attachmentDialog");
                assertNull("Images are off: no picker", dialog(activity));
                assertFalse("A queued photo is refused too, whatever started it", (boolean) invoke(activity, "enqueueSend",
                        new Class<?>[]{ChatEngine.Peer.class, String.class, byte[].class, ChatEnvelope.Expiry.class}, peer, null, new byte[]{1, 2, 3}, ChatEnvelope.Expiry.HOUR_1));
                assertTrue(((List<?>) readField(activity, "pendingSends")).isEmpty());
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.PHOTOS), null, null));
                invoke(activity, "viewContent", new Class<?>[]{ChatEngine.Entry.class}, photoOnce);
                assertNull("Photos are off: the view-once photo is not even offered", dialog(activity));
                setField(activity, "selectedPeer", null); invoke(activity, "render");
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.GROUPS), null, null));
                invoke(activity, "newGroupDialog");
                assertNull("Groups are off: no new group", dialog(activity));
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.GROUPS), null, null));
                invoke(activity, "attachmentDialog");
                assertNotNull("Turning one feature off leaves the others alone", dialog(activity));
                invoke(activity, "dismissContent");
                assertTrue("Turning photos off did not use up the view-once photo", engine.hasEntry(photoOnce.id()));
                assertEquals("DELIVERED", engine.entry(photoOnce.id()).state());
            });
        }
    }

    @Test public void theProfileShowsWhichSettingsAreInForce() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                inject(activity); invoke(activity, "accountDialog");
                assertNotNull(text(dialog(activity).getWindow().getDecorView(), "Settings: as built"));
                invoke(activity, "dismissContent");
                setField(activity, "remote", remoteSettings(Set.of(), null, null));
                invoke(activity, "accountDialog");
                assertTrue(descendants(dialog(activity).getWindow().getDecorView()).stream().anyMatch(view -> view instanceof TextView label
                        && label.getText().toString().startsWith("Settings 9, valid until ")));
            });
        }
    }

    private byte[] signedSettings(java.security.KeyPair pair, String keyId, long sequence, String extra) throws Exception {
        // An hour ago, as the signing tool would have it, but never before the floor this build holds every file to.
        return signedSettings(pair, keyId, sequence, Math.max(System.currentTimeMillis() - HOUR_MS, RemoteSettings.BUILD_ISSUED_FLOOR), extra);
    }

    private byte[] signedSettings(java.security.KeyPair pair, String keyId, long sequence, long issuedAt, String extra) throws Exception {
        long now = System.currentTimeMillis();
        byte[] payload = ("{\"schema\":1,\"sequence\":" + sequence + ",\"issuedAt\":" + issuedAt + ",\"expiresAt\":" + (Math.max(now, issuedAt) + DAY_MS)
                + (extra.isEmpty() ? "" : "," + extra) + "}").getBytes(StandardCharsets.UTF_8);
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update("vanishr-ota-settings-v1\0".getBytes(StandardCharsets.US_ASCII));
        signature.update(payload);
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        return ("{\"v\":1,\"key\":\"" + keyId + "\",\"payload\":\"" + encoder.encodeToString(payload) + "\",\"sig\":\"" + encoder.encodeToString(signature.sign()) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test public void theDailyCheckFetchesVerifiesAndAppliesSettingsAndIgnoresAnythingElse() throws Exception {
        signedInFixture(); conversationsFixture();
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair owner = generator.generateKeyPair(), stranger = generator.generateKeyPair();
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>(
                signedSettings(owner, "t1", 3, "\"notice\":{\"id\":\"first\",\"title\":\"From the signed file\",\"body\":\"Accepted.\",\"level\":\"info\"}"));
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean offline = new java.util.concurrent.atomic.AtomicBoolean();
        okhttp3.OkHttpClient transport = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertEquals(RemoteSettings.URL, chain.request().url().toString());
            assertNull(chain.request().header("Authorization"));
            assertNull(chain.request().header("Cookie"));
            if (offline.get()) throw new IOException("Offline");
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(served.get(), okhttp3.MediaType.get("application/json"))).build();
        }).build();
        android.content.SharedPreferences stored = context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "settingsKeys", Map.of("t1", owner.getPublic()));
                setField(activity, "settingsSource", (java.util.function.Supplier<RemoteSettings>) () -> new RemoteSettings(transport));
                inject(activity);
                invoke(activity, "checkRemoteSettings");
                assertEquals("Checked a moment ago, so nothing is asked of the network", 0, requests.get());
            });
            Runnable due = () -> assertTrue(stored.edit().putLong("last-check", 0).commit());
            java.util.function.Consumer<byte[]> check = body -> {
                served.set(body); due.run();
                try {
                    scenario.onActivity(activity -> invoke(activity, "checkRemoteSettings"));
                    awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingSettings"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            };
            java.util.function.IntSupplier sequence = () -> {
                int[] value = new int[1];
                scenario.onActivity(activity -> value[0] = (int) ((RemoteSettings.Settings) readField(activity, "remote")).sequence());
                return value[0];
            };

            check.accept(served.get());
            assertEquals(1, requests.get());
            assertEquals("A signed file from the owner's key is applied", 3, sequence.getAsInt());
            scenario.onActivity(activity -> assertTrue(hasText(activity, "From the signed file")));
            assertEquals(3, stored.getLong("floor.t1", 0));
            assertNotNull(stored.getString("envelope", null));
            assertTrue("Checked now", !RemoteSettings.due(System.currentTimeMillis(), stored.getLong("last-check", 0)));
            scenario.onActivity(activity -> invoke(activity, "checkRemoteSettings"));
            assertEquals("Not asked again within the day", 1, requests.get());

            check.accept(signedSettings(owner, "t1", 2, "\"features\":{\"images\":false}"));
            assertEquals(2, requests.get());
            assertEquals("An older signed file is not put back", 3, sequence.getAsInt());
            assertEquals(3, stored.getLong("floor.t1", 0));
            assertTrue("...and the check counts as done", !RemoteSettings.due(System.currentTimeMillis(), stored.getLong("last-check", 0)));

            byte[] tampered = signedSettings(owner, "t1", 5, "\"features\":{\"images\":false}");
            tampered[tampered.length - 20] ^= 1;
            for (byte[] refused : List.of(tampered, signedSettings(stranger, "t1", 5, "\"features\":{\"images\":false}"),
                    signedSettings(owner, "unknown", 5, "\"features\":{\"images\":false}"), "<html>not settings</html>".getBytes(StandardCharsets.UTF_8))) {
                check.accept(refused);
                assertEquals("Refused whole: the settings in force are unchanged", 3, sequence.getAsInt());
                long lastCheck = stored.getLong("last-check", 0), asOf = System.currentTimeMillis();
                assertFalse("A failure is not retried at once...", RemoteSettings.due(asOf, lastCheck));
                assertTrue("...but in about two hours", RemoteSettings.due(asOf + RemoteSettings.RETRY_INTERVAL + 60_000, lastCheck));
            }
            int before = requests.get();
            offline.set(true);
            check.accept(served.get());
            assertEquals(before + 1, requests.get());
            assertEquals("Offline changes nothing and shows nothing", 3, sequence.getAsInt());
            scenario.onActivity(activity -> assertTrue(hasText(activity, "From the signed file")));
            offline.set(false);

            check.accept(signedSettings(owner, "t1", 4, "\"features\":{\"images\":false}"));
            assertEquals(4, sequence.getAsInt());
            scenario.onActivity(activity -> {
                assertFalse(((RemoteSettings.Settings) readField(activity, "remote")).enabled(RemoteSettings.Flag.IMAGES));
                assertFalse("The notice of the file that replaced it is gone", hasText(activity, "From the signed file"));
            });
            assertEquals(4, stored.getLong("floor.t1", 0));

            // What is stored is checked again whenever it is loaded.
            long now = System.currentTimeMillis();
            RemoteSettingsStore store = new RemoteSettingsStore(stored);
            assertEquals(4, store.load(Map.of("t1", owner.getPublic()), now).sequence());
            assertEquals("Under another key it no longer counts", 0, store.load(Map.of("t1", stranger.getPublic()), now).sequence());
            assertEquals("Once expired it no longer counts", 0, store.load(Map.of("t1", owner.getPublic()), now + 3 * DAY_MS).sequence());
            String envelope = stored.getString("envelope", "");
            assertTrue(stored.edit().putString("envelope", envelope.replace("\"v\":1", "\"v\":2")).commit());
            assertEquals("A changed file on the phone is not used", 0, store.load(Map.of("t1", owner.getPublic()), now).sequence());
            assertTrue(stored.edit().putString("envelope", envelope).commit());
            assertEquals(4, store.load(Map.of("t1", owner.getPublic()), now).sequence());
            assertFalse("The same sequence again is not newer", store.accept(envelope.getBytes(StandardCharsets.UTF_8), store.load(Map.of("t1", owner.getPublic()), now), now));
        }
    }

    @Test public void aNoticeCanNeverPushTheChatListOutOfReachWhateverItSays() throws Exception {
        signedInFixture(); conversationsFixture();
        // A notice the verifier would refuse, on purpose: the layout has to hold by itself and not only because of the text rules.
        RemoteSettings.Notice huge = new RemoteSettings.Notice("big", "T".repeat(60), "x" + "\n".repeat(398) + "y", true, "/security/",
                RemoteSettings.Build.ALL, 0, Integer.MAX_VALUE);
        RemoteSettings.Update unsupported = new RemoteSettings.Update(BuildConfig.VERSION_CODE + 1, "9.9.9", BuildConfig.VERSION_CODE + 1);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "playBuild", true);
                setField(activity, "remote", remoteSettings(Set.of(), huge, unsupported));
                inject(activity);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            snapshot(scenario, "71-long-notice");
            scenario.onActivity(activity -> {
                android.widget.ScrollView area = (android.widget.ScrollView) readField(activity, "noticeArea");
                View screen = root(activity);
                View list = (View) ((View) readField(activity, "contactRows")).getParent();
                assertTrue("The notice is shown", area.getHeight() > 0);
                assertTrue("It never takes more than 40% of the screen: " + area.getHeight() + " of " + screen.getHeight(), area.getHeight() <= screen.getHeight() * .4f + 1);
                assertTrue("It does not fit, so it scrolls", area.getChildAt(0).getHeight() > area.getHeight());
                assertTrue("The chat list keeps a good part of the screen: " + list.getHeight() + " of " + screen.getHeight(), list.getHeight() >= screen.getHeight() * .3f);
                assertTrue("and is on screen", list.getGlobalVisibleRect(new android.graphics.Rect()));
                assertTrue(hasText(activity, "Aarav Mehta"));
                area.scrollTo(0, area.getChildAt(0).getHeight());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                android.widget.ScrollView area = (android.widget.ScrollView) readField(activity, "noticeArea");
                assertTrue("Scrolled to its end, the card under the notice can be reached", area.getScrollY() > 0);
                View update = text(root(activity), "Open Google Play");
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue(update.getGlobalVisibleRect(visible) && visible.height() >= update.getHeight() / 2);
            });
        }
    }

    @Test public void aNoticeAndAnUpdateHintAreDismissedSeparatelyAndStayDismissed() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "playBuild", true);
                RemoteSettings.Update available = new RemoteSettings.Update(BuildConfig.VERSION_CODE + 1, "9.9.9", 0);
                setField(activity, "remote", remoteSettings(Set.of(), remoteNotice("n1", false, null), available));
                inject(activity);
                assertTrue(hasText(activity, "Heads up n1"));
                assertTrue(hasText(activity, "Update available"));
                text(root(activity), "Dismiss").performClick();
                assertFalse("The notice is gone", hasText(activity, "Heads up n1"));
                assertTrue("The update hint is not", hasText(activity, "Update available"));
                invoke(activity, "render");
                assertFalse(hasText(activity, "Heads up n1"));
                assertTrue("Drawn again, only the notice is still gone", hasText(activity, "Update available"));
                text(root(activity), "Dismiss").performClick();
                assertFalse(hasText(activity, "Update available"));
                assertFalse("Dismissing the hint did not bring the notice back", hasText(activity, "Heads up n1"));
                invoke(activity, "render");
                assertFalse(hasText(activity, "Heads up n1"));
                assertFalse(hasText(activity, "Update available"));
                RemoteSettings.Update later = new RemoteSettings.Update(BuildConfig.VERSION_CODE + 2, "9.9.10", 0);
                setField(activity, "remote", remoteSettings(Set.of(), remoteNotice("n2", false, null), later));
                invoke(activity, "render");
                assertTrue("A different notice is shown", hasText(activity, "Heads up n2"));
                assertTrue("and so is a hint about a later version", hasText(activity, "Vanishr 9.9.10 is on Google Play."));
                // The hint about exactly this version is what stays dismissed. Anything else the owner publishes next is shown, an earlier
                // version included, so that a hint with a mistyped version, once dismissed, does not silence the corrected one.
                setField(activity, "remote", remoteSettings(Set.of(), null, later));
                invoke(activity, "render");
                text(root(activity), "Dismiss").performClick();
                assertFalse(hasText(activity, "Update available"));
                setField(activity, "remote", remoteSettings(Set.of(), null, available));
                invoke(activity, "render");
                assertTrue("An earlier version than the dismissed one is a different hint", hasText(activity, "Vanishr 9.9.9 is on Google Play."));
                setField(activity, "remote", remoteSettings(Set.of(), null, later));
                invoke(activity, "render");
                assertFalse("The one that was dismissed stays dismissed", hasText(activity, "Update available"));
            });
        }
    }

    @Test public void aKeyThatSignedTheHighestNumberDoesNotBlockTheKeyThatReplacesIt() throws Exception {
        signedInFixture(); conversationsFixture();
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair owner = generator.generateKeyPair(), successor = generator.generateKeyPair();
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>();
        okhttp3.OkHttpClient transport = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> new okhttp3.Response.Builder().request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(served.get(), okhttp3.MediaType.get("application/json"))).build()).build();
        android.content.SharedPreferences stored = context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "settingsKeys", Map.of("t1", owner.getPublic()));
                setField(activity, "settingsSource", (java.util.function.Supplier<RemoteSettings>) () -> new RemoteSettings(transport));
                inject(activity);
            });
            java.util.function.Consumer<byte[]> check = body -> {
                served.set(body);
                assertTrue(stored.edit().putLong("last-check", 0).commit());
                try {
                    scenario.onActivity(activity -> invoke(activity, "checkRemoteSettings"));
                    awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingSettings"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            };
            java.util.function.Supplier<RemoteSettings.Settings> inForce = () -> {
                RemoteSettings.Settings[] value = new RemoteSettings.Settings[1];
                scenario.onActivity(activity -> value[0] = (RemoteSettings.Settings) readField(activity, "remote"));
                return value[0];
            };
            check.accept(signedSettings(owner, "t1", RemoteSettings.MAX_SEQUENCE, ""));
            assertEquals("The highest number there is is taken", RemoteSettings.MAX_SEQUENCE, inForce.get().sequence());
            assertEquals(RemoteSettings.MAX_SEQUENCE, stored.getLong("floor.t1", 0));
            check.accept(signedSettings(owner, "t1", 7, "\"features\":{\"images\":false}"));
            assertEquals("That key has used its numbers up", RemoteSettings.MAX_SEQUENCE, inForce.get().sequence());
            assertTrue(inForce.get().enabled(RemoteSettings.Flag.IMAGES));
            // A later app version carries the successor in place of the retired key.
            scenario.onActivity(activity -> setField(activity, "settingsKeys", Map.of("t2", successor.getPublic())));
            check.accept(signedSettings(successor, "t2", 1, "\"features\":{\"groups\":false}"));
            RemoteSettings.Settings now = inForce.get();
            assertEquals("t2", now.keyId());
            assertEquals(1, now.sequence());
            assertFalse(now.enabled(RemoteSettings.Flag.GROUPS));
            assertEquals(1, stored.getLong("floor.t2", 0));
            assertEquals(RemoteSettings.MAX_SEQUENCE, stored.getLong("floor.t1", 0));
        }
    }

    @Test public void anOlderFileOfAnotherListedKeyIsNotPutBackOverANewerOneAndAFutureDatedFileDoesNotHoldBackTheNext() throws Exception {
        signedInFixture(); conversationsFixture();
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair first = generator.generateKeyPair(), second = generator.generateKeyPair();
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>();
        okhttp3.OkHttpClient transport = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> new okhttp3.Response.Builder().request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(served.get(), okhttp3.MediaType.get("application/json"))).build()).build();
        android.content.SharedPreferences stored = context.getSharedPreferences("remote-settings", Context.MODE_PRIVATE);
        // Six steps back from now and no earlier than this build's floor, so that every time below is in the past and none is before it.
        long now = System.currentTimeMillis();
        long step = Math.min(HOUR_MS, (now - RemoteSettings.BUILD_ISSUED_FLOOR) / 6);
        assertTrue("The device clock is behind the floor this build holds files to", step > 0);
        long base = now - 6 * step;
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "settingsKeys", Map.of("t1", first.getPublic(), "t2", second.getPublic()));
                setField(activity, "settingsSource", (java.util.function.Supplier<RemoteSettings>) () -> new RemoteSettings(transport));
                inject(activity);
            });
            java.util.function.Consumer<byte[]> check = body -> {
                served.set(body);
                assertTrue(stored.edit().putLong("last-check", 0).commit());
                try {
                    scenario.onActivity(activity -> invoke(activity, "checkRemoteSettings"));
                    awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingSettings"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            };
            java.util.function.Supplier<RemoteSettings.Settings> inForce = () -> {
                RemoteSettings.Settings[] value = new RemoteSettings.Settings[1];
                scenario.onActivity(activity -> value[0] = (RemoteSettings.Settings) readField(activity, "remote"));
                return value[0];
            };
            check.accept(signedSettings(first, "t1", 49, base + step, ""));
            assertEquals(49, inForce.get().sequence());
            check.accept(signedSettings(second, "t2", 51, base + 3 * step, "\"features\":{\"images\":false}"));
            assertEquals("A newer file of the other key is taken", 51, inForce.get().sequence());
            assertFalse(inForce.get().enabled(RemoteSettings.Flag.IMAGES));
            assertEquals(base + 3 * step, stored.getLong("issued-high", 0));
            // Number 50 has never been seen from the first key, but the file was issued before the one taken since: putting it back
            // would switch images on again.
            check.accept(signedSettings(first, "t1", 50, base + 2 * step, ""));
            assertEquals("An old file of the first key is not put back", 51, inForce.get().sequence());
            assertFalse("so images stay off", inForce.get().enabled(RemoteSettings.Flag.IMAGES));
            assertEquals(49, stored.getLong("floor.t1", 0));
            assertEquals(base + 3 * step, stored.getLong("issued-high", 0));
            check.accept(signedSettings(first, "t1", 52, base + 4 * step, "\"features\":{\"groups\":false}"));
            assertEquals(52, inForce.get().sequence());
            assertEquals(base + 4 * step, stored.getLong("issued-high", 0));
            // A file dated as far ahead as the phone allows is taken, but the time kept stops at the phone's own clock, so the owner's next
            // file, issued at the real time, is not held back by it.
            long before = System.currentTimeMillis();
            check.accept(signedSettings(second, "t2", 60, before + RemoteSettings.CLOCK_SKEW - 60_000, "\"features\":{\"photos\":false}"));
            long after = System.currentTimeMillis();
            assertEquals(60, inForce.get().sequence());
            long kept = stored.getLong("issued-high", 0);
            assertTrue("The issue time kept is no later than the phone's own clock: " + kept, kept >= before && kept <= after);
            check.accept(signedSettings(second, "t2", 61, after, "\"features\":{\"groups\":false}"));
            assertEquals("The file that really follows is taken", 61, inForce.get().sequence());
        }
    }

    private Object pendingSend(ChatEngine.Peer to, String text, byte[] image) throws Exception {
        Class<?> type = Class.forName("app.vanishr.android.MainActivity$PendingSend");
        var constructor = type.getDeclaredConstructor(ChatEngine.Peer.class, String.class, byte[].class, ChatEnvelope.Expiry.class, ChatEnvelope.Reference.class);
        constructor.setAccessible(true);
        return constructor.newInstance(to, text, image, ChatEnvelope.Expiry.HOUR_1, null);
    }

    @Test public void aRetryOfAnUnsentImageIsRefusedOnceImagesAreSwitchedOff() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.IMAGES), null, null));
                inject(activity);
                try {
                    Object unsent = pendingSend(peer, null, new byte[]{1, 2, 3});
                    setField(unsent, "failed", true);
                    invoke(activity, "dispatchSend", new Class<?>[]{unsent.getClass()}, unsent);
                    assertTrue("Refused: it is still an unsent message", (boolean) readField(unsent, "failed"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }

    @Test public void aViewOncePhotoIsNotUsedUpWhenPhotosAreSwitchedOffWhileItsConfirmationIsOpen() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Entry photoOnce = entry(false, null, photo, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -5_000);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "remote", remoteSettings(Set.of(), null, null));
                inject(activity);
                invoke(activity, "viewContent", new Class<?>[]{ChatEngine.Entry.class}, photoOnce);
                assertNotNull("The confirmation is up", text(dialog(activity).getWindow().getDecorView(), "View once"));
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.PHOTOS), null, null));
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            Thread.sleep(1_500);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNull("No photo is shown", readField(activity, "photoViewer"));
                assertTrue("and the view-once photo is still there", engine.hasEntry(photoOnce.id()));
                assertEquals("DELIVERED", engine.entry(photoOnce.id()).state());
            });
        }
    }

    @Test public void aGroupDialogThatIsAlreadyOpenCannotCreateAGroupOnceGroupsAreSwitchedOff() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "remote", remoteSettings(Set.of(), null, null));
                inject(activity);
                invoke(activity, "newGroupDialog");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View decor = dialog(activity).getWindow().getDecorView();
                descendants(decor).stream().filter(view -> view instanceof EditText).map(view -> (EditText) view).findFirst().orElseThrow().setText("Lunch club");
                descendants(decor).stream().filter(view -> view instanceof MaterialCheckBox).map(view -> (MaterialCheckBox) view).findFirst().orElseThrow().setChecked(true);
                setField(activity, "remote", remoteSettings(EnumSet.of(RemoteSettings.Flag.GROUPS), null, null));
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            Thread.sleep(1_500);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertTrue("The dialog is still up", dialog(activity).isShowing());
                assertTrue("No group was started: not even its title was written", vault.names("group-title/").isEmpty());
            });
        }
    }

    // ==== end 0.6.2 ====

    // ==== 0.6.3: the official admin changes phone ====

    private static java.security.KeyPair adminAuthorityPair() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String fingerprintOf(UUID account, SignalClient identity) throws Exception {
        return ChatEngine.safetyNumber(account, Base64.getEncoder().encodeToString(identity.publicIdentity())).replace(" ", "").toLowerCase(Locale.ROOT);
    }

    // What the app holds after it has verified a certificate (the signature itself is covered by AdminIdentityTest and below).
    private static AdminIdentity.Certificate certificate(UUID account, UUID device, SignalClient identity, long sequence) throws Exception {
        return new AdminIdentity.Certificate("t1", sequence, account, device, fingerprintOf(account, identity));
    }

    private static byte[] signedAdminIdentity(java.security.KeyPair pair, String keyId, long sequence, UUID account, UUID device, String fingerprint) throws Exception {
        byte[] payload = ("{\"schema\":1,\"sequence\":" + sequence + ",\"userId\":\"" + account + "\",\"deviceId\":\"" + device + "\",\"fingerprint\":\"" + fingerprint + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update("vanishr-admin-identity-v1\0".getBytes(StandardCharsets.US_ASCII));
        signature.update(payload);
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        return ("{\"v\":1,\"key\":\"" + keyId + "\",\"payload\":\"" + encoder.encodeToString(payload) + "\",\"sig\":\"" + encoder.encodeToString(signature.sign()) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    // This phone holds the official admin as it was before the pin changed: saved as an automatic contact, with its identity verified.
    private void saveRetiredAdmin(AdminOnboarding.Introduction retired, SignalClient retiredIdentity) throws Exception {
        vault.transaction(() -> {
            engine.groupSignal().verifyPeer(peerId, retiredIdentity.publicIdentity());
            write("contact/" + peerId, retired.peer());
            write("admin-contact/" + peerId, true);
            return null;
        });
    }

    @Test public void aSavedOfficialAdminIsReplacedByTheNewPinnedIdentityAndNeverByAnyOtherOne() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient stranger = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(before.peer()), List.of(before), null));
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        adminTransport(directory, new ArrayList<>(), outgoing, retired.generatePreKey(Instant.now()));
        engine.onboarding().refresh();
        engine.send(before.peer(), "before the change", null, ChatEnvelope.Expiry.HOUR_1);
        assertTrue("A session with the retired identity exists", engine.groupSignal().hasSession(peerId));
        assertEquals(before.peer(), engine.peer(peerId));

        // The relay now shows the owner's new phone, which this build has not been told about: nothing is replaced and the warning shows.
        directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(after.peer()), List.of(after), null));
        AdminOnboarding.Failure refused = assertThrows(AdminOnboarding.Failure.class, () -> engine.onboarding().first());
        engine.onboarding().blocked(refused);
        assertNotNull(engine.onboarding().problem());
        assertEquals(before.peer(), engine.peer(peerId));
        assertTrue(engine.groupSignal().hasSession(peerId));
        assertFalse("Nothing is pending while the pin is still the retired identity", engine.onboarding().rotationPending(peerId));

        // A certificate for some other identity is not the one the relay shows: still refused, still nothing replaced.
        engine.onboarding().adopt(certificate(peerId, UUID.randomUUID(), stranger, 2));
        assertNull("Adopting clears the warning, the next look at the relay brings it back", engine.onboarding().problem());
        assertThrows(AdminOnboarding.Failure.class, () -> engine.onboarding().first());
        assertThrows(SecurityException.class, () -> engine.onboarding().prepare(before.peer()));
        assertEquals(before.peer(), engine.peer(peerId));
        assertTrue(engine.groupSignal().hasSession(peerId));
        assertThrows("The retired identity is still the one that is verified", SecurityException.class,
                () -> engine.groupSignal().verifyPeer(peerId, current.publicIdentity()));

        // The certificate for the identity the relay shows: the saved contact is replaced, with a fresh start for the session.
        engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
        long generation = engine.onboarding().generation();
        assertTrue(engine.onboarding().rotationPending(peerId));
        engine.onboarding().first();
        assertNull(engine.onboarding().problem());
        assertEquals(after.peer(), engine.peer(peerId));
        assertFalse(engine.onboarding().rotationPending(peerId));
        assertTrue(engine.groupSignal().isVerified(peerId));
        assertFalse("The old session is gone", engine.groupSignal().hasSession(peerId));
        assertThrows("The retired identity can no longer be verified", SecurityException.class,
                () -> engine.groupSignal().verifyPeer(peerId, retired.publicIdentity()));
        engine.groupSignal().verifyPeer(peerId, current.publicIdentity());
        assertTrue(engine.onboarding().automatic(peerId));
        assertFalse("Pinned by the app, not verified by the person", engine.independentlyVerified(peerId));
        assertTrue(engine.sharesPresence(peerId));
        assertTrue(engine.onboarding().generation() >= generation);

        // And the conversation goes on with the new identity.
        adminTransport(directory, new ArrayList<>(), outgoing, current.generatePreKey(Instant.now()));
        engine.send(after.peer(), "after the change", null, ChatEnvelope.Expiry.HOUR_1);
        assertEquals(2, outgoing.size());
        current.verifyPeer(userId, engine.groupSignal().publicIdentity());
        ChatEngine.Send sent = outgoing.get(1);
        byte[] plaintext = current.decrypt(userId, new SignalClient.Packet(sent.type(), sent.ciphertext()));
        try { assertEquals("after the change", RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ChatEnvelope.class).text()); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    @Test public void aContactThatIsNotTheOfficialAdminIsNeverReplacedByAPinChange() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        ChatEngine.Contact official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(own.publicIdentity()));
        SignalClient first = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient second = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        var introduced = introduction(peerId, peerDevice, first, "newcomer");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, List.of(introduced), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.onboarding().refresh();
        assertEquals(introduced.peer(), engine.peer(peerId));
        // On the admin's own phone a new account that comes back with another identity is exactly what a pin change must not touch.
        var changed = introduction(peerId, peerDevice, second, "newcomer");
        directory.set(new AdminOnboarding.Page(userId, official, List.of(changed), null));
        // It is kept for review, never saved over the identity that is pinned, and no longer fails the whole directory.
        engine.onboarding().first();
        assertEquals(changed.peer(), engine.onboarding().changed(peerId));
        assertEquals(introduced.peer(), engine.peer(peerId));
        assertThrows(SecurityException.class, () -> engine.onboarding().prepare(changed.peer()));
        assertFalse(engine.onboarding().rotationPending(peerId));
        engine.onboarding().adopt(certificate(userId, deviceId, own, 7));
        engine.onboarding().first();
        assertEquals("A new pin does not make anyone else's changed identity acceptable", introduced.peer(), engine.peer(peerId));
        assertEquals(changed.peer(), engine.onboarding().changed(peerId));
        assertThrows(SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, second.publicIdentity()));
    }

    // The pin is the new identity (as after adopt) while this phone still holds the retired official admin from before.
    private AdminOnboarding.Introduction[] retiredAdminSetup(SignalClient retired, SignalClient current, UUID currentDevice) throws Exception {
        signedInFixture(adminPin(peerId, currentDevice, current));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        saveRetiredAdmin(before, retired);
        assertTrue(engine.onboarding().rotationPending(peerId));
        return new AdminOnboarding.Introduction[]{before, after};
    }

    @Test public void anIncomingMessageFromTheNewAdminDeviceReplacesTheRetiredContactBeforeItIsRead() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        var setup = retiredAdminSetup(retired, current, currentDevice);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId,
                RemotePhotoSession.contact(setup[1].peer()), List.of(setup[1]), null));
        List<ChatEngine.Incoming> incoming = new ArrayList<>();
        adminTransport(directory, incoming, new ArrayList<>(), null);
        current.verifyPeer(userId, engine.groupSignal().publicIdentity());
        current.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 120_000;
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, currentDevice, userId, deviceId, now, deadline, ChatEnvelope.Expiry.HOUR_1, "from the new admin phone", null);
        SignalClient.Packet packet = current.encrypt(userId, RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
        incoming.add(new ChatEngine.Incoming(id, peerId, currentDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null));
        engine.sync();
        assertTrue("The message was read", engine.entries(peerId).stream().anyMatch(entry -> id.equals(entry.id()) && !entry.outgoing()));
        assertEquals(setup[1].peer(), engine.peer(peerId));
        assertFalse(engine.onboarding().rotationPending(peerId));
        assertTrue(engine.onboarding().automatic(peerId));
        assertTrue(engine.groupSignal().isVerified(peerId));
        engine.groupSignal().verifyPeer(peerId, current.publicIdentity());
        assertThrows("The retired identity was replaced, not added to", SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, retired.publicIdentity()));
    }

    @Test public void anAdminThePersonVerifiedByHandIsReplacedByThePinnedIdentityAndIsAutomaticAfterwards() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        var setup = retiredAdminSetup(retired, current, currentDevice);
        // Verified by hand while the old identity was the pinned one: that contact has no automatic marker. The pin has moved since, so it
        // is no longer verified for anything, although it is still the saved contact.
        vault.transaction(() -> { vault.remove("admin-contact/" + peerId); return null; });
        assertTrue(engine.onboarding().rotationPending(peerId));
        assertFalse(engine.independentlyVerified(peerId));
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId,
                RemotePhotoSession.contact(setup[1].peer()), List.of(setup[1]), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        engine.prepareConversation(setup[0].peer());
        assertEquals(setup[1].peer(), engine.peer(peerId));
        assertTrue(engine.onboarding().automatic(peerId));
        assertFalse("The new identity is pinned by the app, not verified by the person", engine.independentlyVerified(peerId));
        assertTrue(engine.groupSignal().isVerified(peerId));
        assertThrows("The identity the person verified is gone, not kept beside the new one", SecurityException.class,
                () -> engine.groupSignal().verifyPeer(peerId, retired.publicIdentity()));
    }

    @Test public void aMessageFromAnIdentityThatIsNeitherTheRetiredNorThePinnedOneIsNotReadAndReplacesNothing() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient impostor = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID(), impostorDevice = UUID.randomUUID();
        var setup = retiredAdminSetup(retired, current, currentDevice);
        var fake = introduction(peerId, impostorDevice, impostor, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId,
                RemotePhotoSession.contact(fake.peer()), List.of(fake), null));
        List<ChatEngine.Incoming> incoming = new ArrayList<>();
        adminTransport(directory, incoming, new ArrayList<>(), null);
        impostor.verifyPeer(userId, engine.groupSignal().publicIdentity());
        impostor.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 120_000;
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, impostorDevice, userId, deviceId, now, deadline, ChatEnvelope.Expiry.HOUR_1, "not the admin", null);
        SignalClient.Packet packet = impostor.encrypt(userId, RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
        incoming.add(new ChatEngine.Incoming(id, peerId, impostorDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null));
        assertThrows(AdminOnboarding.Failure.class, engine::sync);
        assertTrue(engine.entries(peerId).stream().noneMatch(entry -> id.equals(entry.id())));
        assertEquals("Nothing was replaced", setup[0].peer(), engine.peer(peerId));
        assertTrue(engine.onboarding().rotationPending(peerId));
        assertThrows(SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, impostor.publicIdentity()));
    }

    @Test public void openingTheAdminChatAfterAPinChangeTakesTheNewIdentityFromTheNetworkInsteadOfFailing() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        var setup = retiredAdminSetup(retired, current, currentDevice);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId,
                RemotePhotoSession.contact(setup[1].peer()), List.of(setup[1]), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        assertFalse("The saved copy is the retired one, so the network has to say what the admin is now", engine.prepareSavedConversation(setup[0].peer()));
        engine.prepareConversation(setup[0].peer());
        assertEquals(setup[1].peer(), engine.peer(peerId));
        assertTrue("Afterwards it opens locally again", engine.prepareSavedConversation(setup[1].peer()));
        assertTrue(engine.automaticConnection(peerId));
    }

    @Test public void openingTheAdminChatWhenTheRelayShowsAnUnpinnedIdentityStillFailsClosed() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient impostor = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        var setup = retiredAdminSetup(retired, current, UUID.randomUUID());
        var fake = introduction(peerId, UUID.randomUUID(), impostor, "vanishr");
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId,
                RemotePhotoSession.contact(fake.peer()), List.of(fake), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        assertFalse(engine.prepareSavedConversation(setup[0].peer()));
        assertThrows(AdminOnboarding.Failure.class, () -> engine.prepareConversation(setup[0].peer()));
        assertEquals(setup[0].peer(), engine.peer(peerId));
        assertFalse(engine.automaticConnection(peerId));
        assertThrows(SecurityException.class, () -> engine.send(setup[0].peer(), "must not be sent", null, ChatEnvelope.Expiry.HOUR_1));
    }

    @Test public void theAdminsNewPhoneIsRecognisedOnlyOnceItsIdentityIsPinnedAndACertificateCanOnlyMoveTheDeviceAndTheIdentity() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        SignalClient retired = new SignalClient(userId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(userId, UUID.randomUUID(), retired));
        assertTrue(engine.onboarding().admin());
        assertFalse("The account matches, this phone's identity does not", engine.onboarding().verifiedAdmin());
        AdminOnboarding.Pin original = engine.onboarding().pin();
        long generation = engine.onboarding().generation();
        engine.onboarding().adopt(null);
        engine.onboarding().adopt(certificate(UUID.randomUUID(), deviceId, own, 4));
        assertEquals("A certificate for another account changes nothing", original, engine.onboarding().pin());
        assertEquals(generation, engine.onboarding().generation());
        assertFalse(engine.onboarding().verifiedAdmin());

        engine.onboarding().adopt(certificate(userId, deviceId, own, 5));
        assertTrue("This phone's device and identity are the pinned ones now", engine.onboarding().verifiedAdmin());
        assertEquals("The relay stays what the app was built with", original.origin(), engine.onboarding().pin().origin());
        assertEquals(userId, engine.onboarding().pin().userId());
        assertEquals(deviceId, engine.onboarding().pin().deviceId());
        assertTrue(engine.onboarding().generation() > generation);
        long adopted = engine.onboarding().generation();
        engine.onboarding().adopt(certificate(userId, deviceId, own, 6));
        assertEquals("The same identity again changes nothing", adopted, engine.onboarding().generation());

        // Nothing but the certificate decides who the admin is: the identity of another phone takes it away again.
        engine.onboarding().adopt(certificate(userId, deviceId, retired, 8));
        assertFalse(engine.onboarding().verifiedAdmin());
        engine.onboarding().adopt(certificate(userId, UUID.randomUUID(), own, 9));
        assertFalse("The right identity on another device is not the admin either", engine.onboarding().verifiedAdmin());
    }

    private okhttp3.OkHttpClient certificateTransport(java.util.concurrent.atomic.AtomicReference<byte[]> served, java.util.concurrent.atomic.AtomicInteger requests) {
        return new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertEquals(AdminIdentity.URL, chain.request().url().toString());
            assertEquals("GET", chain.request().method());
            assertNull(chain.request().header("Authorization"));
            assertNull(chain.request().header("Cookie"));
            byte[] body = served.get();
            if (body == null) throw new IOException("Offline");
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(body, okhttp3.MediaType.get("application/json"))).build();
        }).build();
    }

    @Test public void aMismatchOfTheAdminIdentityAsksForTheCertificateAndOnlyAVerifiedNewerOneIsFollowed() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient other = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        saveRetiredAdmin(before, retired);
        // The relay still shows the identity the pin knows while the refusals are tried, so that no sync of its own asks for anything.
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(before.peer()), List.of(before), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        java.security.KeyPair authority = adminAuthorityPair(), stranger = adminAuthorityPair();
        UUID admin = peerId;
        String currentFingerprint = fingerprintOf(admin, current);
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient transport = certificateTransport(served, requests);
        android.content.SharedPreferences stored = context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE);
        AdminOnboarding.Pin retiredPin = engine.onboarding().pin();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                setField(activity, "adminIdentitySource", (java.util.function.Supplier<RemoteSettings>) () -> RemoteSettings.forAdminIdentity(transport));
                inject(activity);
            });
            java.util.function.Consumer<byte[]> ask = body -> {
                served.set(body);
                try {
                    scenario.onActivity(activity -> {
                        assertTrue(stored.edit().remove("last-attempt").commit());
                        invoke(activity, "requestAdminIdentity", new Class<?>[]{ChatEngine.class}, engine);
                    });
                    awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingAdminIdentity"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            };
            long newer = AdminIdentity.BUILD_SEQUENCE_FLOOR + 1;
            byte[] good = signedAdminIdentity(authority, "t1", newer, admin, currentDevice, currentFingerprint);
            byte[] tampered = good.clone(); tampered[tampered.length - 6] ^= 1;
            int asked = 0;
            for (byte[] refused : List.of(
                    signedAdminIdentity(stranger, "t1", newer, admin, currentDevice, currentFingerprint),
                    signedAdminIdentity(authority, "unknown", newer, admin, currentDevice, currentFingerprint),
                    signedAdminIdentity(authority, "t1", newer, UUID.randomUUID(), currentDevice, currentFingerprint),
                    signedAdminIdentity(authority, "t1", AdminIdentity.BUILD_SEQUENCE_FLOOR, admin, currentDevice, currentFingerprint),
                    signedAdminIdentity(authority, "t1", newer, admin, currentDevice, currentFingerprint.toUpperCase(Locale.ROOT)),
                    tampered, "<html>not a certificate</html>".getBytes(StandardCharsets.UTF_8), new byte[0])) {
                ask.accept(refused);
                assertEquals("It was asked for", ++asked, requests.get());
                assertEquals("Nothing that does not verify, or is not newer, is followed", retiredPin, engine.onboarding().pin());
                assertFalse(stored.contains("envelope"));
            }
            ask.accept(null);
            assertEquals("An unreachable site changes nothing either", retiredPin, engine.onboarding().pin());

            // Not more than once in ten minutes, whatever the screen keeps asking.
            served.set(good);
            scenario.onActivity(activity -> { invoke(activity, "requestAdminIdentity", new Class<?>[]{ChatEngine.class}, engine); invoke(activity, "requestAdminIdentity", new Class<?>[]{ChatEngine.class}, engine); });
            awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingAdminIdentity"));
            assertEquals("The retry period was not over, so nothing was asked", asked + 1, requests.get());
            // A restart of the app does not make it due again: the time of the last request is kept with the certificate, not in the screen.
            scenario.onActivity(activity -> {
                setField(activity, "adminStore", null);
                invoke(activity, "requestAdminIdentity", new Class<?>[]{ChatEngine.class}, engine);
            });
            awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingAdminIdentity"));
            assertEquals("Still not due after a restart", asked + 1, requests.get());

            // The relay now shows the owner's new phone, and this is the first moment a sync of its own can meet it.
            directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(after.peer()), List.of(after), null));
            ask.accept(good);
            assertEquals(asked + 2, requests.get());
            assertEquals("The certificate was followed", certificate(admin, currentDevice, current, newer).pin(retiredPin.origin()), engine.onboarding().pin());
            assertNotNull("and kept, exactly as it came", stored.getString("envelope", null));
            assertEquals(new String(good, StandardCharsets.UTF_8), stored.getString("envelope", null));
            // The next sync (it was asked for) replaces the retired contact, so the chat list no longer carries the warning.
            awaitMainCondition(scenario, activity -> !engine.onboarding().rotationPending(peerId) && engine.onboarding().problem() == null);
            assertEquals(after.peer(), engine.peer(peerId));
            assertTrue(engine.groupSignal().isVerified(peerId));
            // The same certificate, or an older one, is not taken again.
            ask.accept(good);
            assertEquals(good.length, stored.getString("envelope", "").getBytes(StandardCharsets.UTF_8).length);
            ask.accept(signedAdminIdentity(authority, "t1", 2, admin, UUID.randomUUID(), fingerprintOf(admin, other)));
            assertEquals("A lower number is not put back", currentDevice, engine.onboarding().pin().deviceId());
            assertEquals(new String(good, StandardCharsets.UTF_8), stored.getString("envelope", null));
        }
    }

    @Test public void aStoredCertificateIsCheckedAgainWhenTheEngineLoadsAndOneThatNoLongerHoldsIsIgnored() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        AdminOnboarding.Pin retiredPin = engine.onboarding().pin();
        java.security.KeyPair authority = adminAuthorityPair(), stranger = adminAuthorityPair();
        byte[] good = signedAdminIdentity(authority, "t1", 4, peerId, currentDevice, fingerprintOf(peerId, current));
        android.content.SharedPreferences stored = context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            java.util.function.Function<byte[], AdminOnboarding.Pin> start = envelope -> {
                AdminOnboarding.Pin[] pin = new AdminOnboarding.Pin[1];
                assertTrue(envelope == null ? stored.edit().clear().commit() : stored.edit().clear().putString("envelope", new String(envelope, StandardCharsets.UTF_8)).commit());
                scenario.onActivity(activity -> {
                    setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                    setField(engine.onboarding(), "pin", retiredPin);
                    invoke(activity, "applyStoredAdminIdentity", new Class<?>[]{ChatEngine.class}, engine);
                    pin[0] = engine.onboarding().pin();
                });
                return pin[0];
            };
            assertEquals("Nothing stored: the built-in identity stands", retiredPin, start.apply(null));
            assertEquals("A stored certificate that verifies is followed from the start",
                    certificate(peerId, currentDevice, current, 4).pin(retiredPin.origin()), start.apply(good));
            assertEquals("One signed by anybody else is not", retiredPin, start.apply(signedAdminIdentity(stranger, "t1", 4, peerId, currentDevice, fingerprintOf(peerId, current))));
            byte[] altered = good.clone(); altered[altered.length - 6] ^= 1;
            assertEquals("Nor one that was altered on the phone", retiredPin, start.apply(altered));
            assertEquals("Nor garbage", retiredPin, start.apply("not a certificate".getBytes(StandardCharsets.UTF_8)));
            assertEquals("Nor one for another account", retiredPin, start.apply(signedAdminIdentity(authority, "t1", 4, UUID.randomUUID(), currentDevice, fingerprintOf(peerId, current))));
            AdminOnboarding.Pin atFloor = start.apply(signedAdminIdentity(authority, "t1", AdminIdentity.BUILD_SEQUENCE_FLOOR, peerId, currentDevice, fingerprintOf(peerId, current)));
            assertEquals("One this build already stands for is not used, and is dropped", retiredPin, atFloor);
            assertFalse(stored.contains("envelope"));
        }
    }

    @Test public void aRealSyncThatMeetsAnUnknownAdminIdentityFollowsTheCertificateAndClearsTheWarning() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        saveRetiredAdmin(before, retired);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(after.peer()), List.of(after), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        setField(engine, "nextKeyCheck", System.currentTimeMillis() + 300_000);
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
        java.security.KeyPair authority = adminAuthorityPair();
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>(
                signedAdminIdentity(authority, "t1", AdminIdentity.BUILD_SEQUENCE_FLOOR + 1, peerId, currentDevice, fingerprintOf(peerId, current)));
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient transport = certificateTransport(served, requests);
        AdminOnboarding.Pin retiredPin = engine.onboarding().pin();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                setField(activity, "adminIdentitySource", (java.util.function.Supplier<RemoteSettings>) () -> RemoteSettings.forAdminIdentity(transport));
                inject(activity);
                invoke(activity, "queueSync");
            });
            awaitMainCondition(scenario, activity -> engine.onboarding().pin().deviceId().equals(currentDevice) && !engine.onboarding().rotationPending(peerId)
                    && engine.onboarding().problem() == null);
            assertNotEquals(retiredPin, engine.onboarding().pin());
            assertEquals("The certificate was asked for once, because the sync met an identity it did not know", 1, requests.get());
            assertEquals(after.peer(), engine.peer(peerId));
            assertTrue(engine.groupSignal().isVerified(peerId));
            scenario.onActivity(activity -> assertFalse("The warning is gone from the chat list", hasText(activity, "Official admin identity changed. A reviewed app update is required.")));
        }
    }

    // Waits until what the screen queued on its two background lanes (the sync and the update lane) has run.
    private void drainLanes(ActivityScenario<MainActivity> scenario) throws Exception {
        java.util.concurrent.ExecutorService[] lanes = new java.util.concurrent.ExecutorService[2];
        scenario.onActivity(activity -> {
            lanes[0] = (java.util.concurrent.ExecutorService) readField(activity, "work");
            lanes[1] = (java.util.concurrent.ExecutorService) readField(activity, "updateWork");
        });
        for (java.util.concurrent.ExecutorService lane : lanes) lane.submit(() -> { }).get(15, java.util.concurrent.TimeUnit.SECONDS);
        awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingAdminIdentity"));
    }

    @Test public void onlyAnIdentityMismatchAsksForTheCertificateAndNoOtherFailureOfTheDirectoryDoes() throws Exception {
        SignalClient pinned = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient unknown = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(peerId, peerDevice, pinned));
        var admin = introduction(peerId, peerDevice, pinned, "vanishr");
        var first = introduction(UUID.randomUUID(), UUID.randomUUID(), new SignalClient(UUID.randomUUID(), new DeviceSecurityTest.MemoryVault()), "newcomer");
        var second = introduction(UUID.randomUUID(), UUID.randomUUID(), new SignalClient(UUID.randomUUID(), new DeviceSecurityTest.MemoryVault()), "another");
        // The relay shows the pinned admin exactly, and then more accounts than an ordinary account is ever introduced to: refused, but the
        // admin's identity is what the app expects, so no certificate could change anything.
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(admin.peer()), List.of(first, second), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        setField(engine, "nextKeyCheck", System.currentTimeMillis() + 300_000);
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
        java.security.KeyPair authority = adminAuthorityPair();
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>("{}".getBytes(StandardCharsets.UTF_8));
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient transport = certificateTransport(served, requests);
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                setField(activity, "adminIdentitySource", (java.util.function.Supplier<RemoteSettings>) () -> RemoteSettings.forAdminIdentity(transport));
                inject(activity);
                invoke(activity, "queueSync");
            });
            awaitMainCondition(scenario, activity -> "Unrelated admin contacts were returned".equals(engine.onboarding().problem()));
            drainLanes(scenario);
            assertEquals("A refusal that is not about the admin's identity does not ask the download site anything", 0, requests.get());
            scenario.onActivity(activity -> assertFalse(context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE).contains("last-attempt")));

            // The same screen, the same engine: now the relay shows another identity for the admin, which is the one case that asks.
            directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(introduction(peerId, UUID.randomUUID(), unknown, "vanishr").peer()), List.of(), null));
            setField(engine.onboarding(), "nextRefresh", 0L);
            scenario.onActivity(activity -> invoke(activity, "queueSync"));
            awaitMainCondition(scenario, activity -> requests.get() == 1);
            drainLanes(scenario);
            assertEquals(1, requests.get());
            assertEquals("Official admin identity changed. A reviewed app update is required.", engine.onboarding().problem());
        }
    }

    @Test public void aDamagedStoredCertificateDoesNotLetAnOlderOneBackInAndTheCertificateItWasCanTakeItsPlace() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        saveRetiredAdmin(before, retired);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(before.peer()), List.of(before), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        setField(engine, "nextKeyCheck", System.currentTimeMillis() + 300_000);
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
        java.security.KeyPair authority = adminAuthorityPair();
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient transport = certificateTransport(served, requests);
        android.content.SharedPreferences stored = context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE);
        // Number 5 was taken before; the stored copy of it can no longer be used.
        assertTrue(stored.edit().clear().putString("envelope", "damaged").putLong("sequence", 5).commit());
        AdminOnboarding.Pin retiredPin = engine.onboarding().pin();
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                setField(activity, "adminIdentitySource", (java.util.function.Supplier<RemoteSettings>) () -> RemoteSettings.forAdminIdentity(transport));
                inject(activity);
            });
            java.util.function.Consumer<byte[]> ask = body -> {
                served.set(body);
                try {
                    scenario.onActivity(activity -> {
                        assertTrue(stored.edit().remove("last-attempt").commit());
                        invoke(activity, "requestAdminIdentity", new Class<?>[]{ChatEngine.class}, engine);
                    });
                    awaitMainCondition(scenario, activity -> !(boolean) readField(activity, "checkingAdminIdentity"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            };
            ask.accept(signedAdminIdentity(authority, "t1", AdminIdentity.BUILD_SEQUENCE_FLOOR + 1, peerId, currentDevice, fingerprintOf(peerId, current)));
            assertEquals(1, requests.get());
            assertEquals("A valid certificate older than the one that was taken is not put back because its stored copy is damaged", retiredPin, engine.onboarding().pin());
            assertEquals("damaged", stored.getString("envelope", null));
            assertEquals(5L, stored.getLong("sequence", 0));

            // The relay now shows the new phone, and the certificate that the damaged copy was is the one that takes its place.
            directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(after.peer()), List.of(after), null));
            byte[] five = signedAdminIdentity(authority, "t1", 5, peerId, currentDevice, fingerprintOf(peerId, current));
            ask.accept(five);
            awaitMainCondition(scenario, activity -> engine.onboarding().pin().deviceId().equals(currentDevice));
            assertEquals(new String(five, StandardCharsets.UTF_8), stored.getString("envelope", null));
            assertEquals(5L, stored.getLong("sequence", 0));
            awaitMainCondition(scenario, activity -> !engine.onboarding().rotationPending(peerId) && engine.onboarding().problem() == null);
            assertEquals(after.peer(), engine.peer(peerId));
        }
    }

    @Test public void anAdminThePersonVerifiedByHandStopsCountingAsVerifiedTheMomentThePinMovesAndOpeningItAsksTheNetwork() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        saveRetiredAdmin(before, retired);
        // Verified by hand while the old identity was the pinned one: a saved contact without the automatic marker.
        vault.transaction(() -> { vault.remove("admin-contact/" + peerId); return null; });
        assertTrue(engine.independentlyVerified(peerId));
        assertTrue(engine.sharesPresence(peerId));
        assertFalse(engine.onboarding().rotationPending(peerId));
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(after.peer()), List.of(after), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);

        // The pin moves, but nothing has replaced the saved contact yet: it is still there, and it is a contact for nothing.
        engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
        assertTrue(engine.onboarding().rotationPending(peerId));
        assertEquals(before.peer(), engine.peer(peerId));
        assertFalse("Not verified for groups, photos or the backup", engine.independentlyVerified(peerId));
        assertFalse("and no presence goes to it", engine.sharesPresence(peerId));
        assertFalse(engine.automaticConnection(peerId));

        // Opening it from the chat list does not take the shortcut for verified contacts: the network says what the admin is now.
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                inject(activity);
                invoke(activity, "openContact", new Class<?>[]{ChatEngine.Peer.class}, before.peer());
            });
            awaitMainCondition(scenario, activity -> after.peer().equals(engine.peer(peerId)) && peerId.equals(readField(activity, "selectedPeer")));
            assertFalse(engine.onboarding().rotationPending(peerId));
            assertTrue(engine.automaticConnection(peerId));
            assertFalse("The new identity is the app's introduction, not something the person verified", engine.independentlyVerified(peerId));
            assertTrue(engine.sharesPresence(peerId));
        }
    }

    @Test public void aGroupIOwnSendsNoKeysToTheRetiredAdminOnceThePinHasMoved() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        UUID groupId = UUID.randomUUID(), epoch = UUID.randomUUID();
        retired.verifyPeer(userId, engine.groupSignal().publicIdentity());
        engine.groupSignal().verifyPeer(peerId, retired.publicIdentity());
        ChatEngine.Peer admin = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(retired.publicIdentity()), "vanishr", "vanishr", null);
        GroupChat.Snapshot snapshot = new GroupChat.Snapshot(groupId, userId, 2, epoch, false, List.of(
                new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()), "alex", null, "ACTIVE", 0),
                new GroupChat.Member(peerId, peerDevice, admin.identityKey(), "vanishr", null, "ACTIVE", 0)));
        vault.transaction(() -> {
            write("contact/" + peerId, admin); write("group/" + groupId, new GroupChat.Conversation(snapshot, "Private launch", null));
            write("group-title/" + groupId, "Private launch"); write("group-allowed/" + groupId, snapshot.roster().members()); return null;
        });
        List<GroupChat.ControlSend> controls = new ArrayList<>(); List<GroupChat.Send> messages = new ArrayList<>();
        ((RelayApi) readField(engine, "api")).close();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request();
            String path = request.url().encodedPath();
            if (path.equals("/groups/" + groupId) && request.method().equals("GET")) return syntheticResponse(request, 200, snapshot);
            if (path.endsWith("/keys")) {
                try { return syntheticResponse(request, 200, List.of(new GroupChat.Claimed(peerId, retired.generatePreKey(Instant.now())))); }
                catch (Exception failure) { throw new IOException("Synthetic prekey fixture failed"); }
            }
            okio.Buffer buffer = new okio.Buffer(); request.body().writeTo(buffer); String body = buffer.readUtf8();
            if (path.endsWith("/controls")) { controls.addAll(RelayApi.JSON.fromJson(body, GroupChat.Controls.class).packets()); return syntheticResponse(request, 200, Map.of()); }
            assertTrue(path.endsWith("/messages"));
            GroupChat.Send message = RelayApi.JSON.fromJson(body, GroupChat.Send.class); messages.add(message);
            return syntheticResponse(request, 200, new GroupChat.Status(message.id(), message.expiresAt(), 1, 0, 0, "QUEUED"));
        }));
        UUID first = UUID.randomUUID(); long created = System.currentTimeMillis();
        createFixtureKey(AndroidVault.contentAlias(created + 3_600_000, first, userId));
        engine.groups().send(groupId, "First group message", null, ChatEnvelope.Expiry.HOUR_1, first, created, () -> { });
        assertEquals("Until the pin moves the admin is a verified member like any other", 2, controls.size());
        assertEquals(1, messages.size());

        engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
        UUID second = UUID.randomUUID(); long later = System.currentTimeMillis();
        createFixtureKey(AndroidVault.contentAlias(later + 3_600_000, second, userId));
        assertThrows("The retired identity is no longer a verified member, so nothing is approved or sent for it", SecurityException.class,
                () -> engine.groups().send(groupId, "Second group message", null, ChatEnvelope.Expiry.HOUR_1, second, later, () -> { }));
        assertEquals(2, controls.size());
        assertEquals(1, messages.size());
    }

    // A group in which the official admin is a member and I am not the owner: the admin's key arrives and my own key is published.
    private record AdminGroupRun(List<UUID> recipients, boolean adminKeyTaken, boolean adminControlSeen, boolean adminControlDeleted,
                                 boolean queuedAdminControlSent, boolean queuedOwnerControlSent, boolean queuedAdminControlLeft, boolean queuedMessagePosted) { }

    private AdminGroupRun runGroupWithTheAdminAsAMember(UUID adminId, UUID adminDevice, SignalClient retired, SignalClient owner) throws Exception {
        return runGroupWithTheAdminAsAMember(adminId, adminDevice, retired, owner, false);
    }

    // With queued set, one control for the admin's device, one for the owner and a message of mine were already waiting to be sent when the group is synchronised.
    private AdminGroupRun runGroupWithTheAdminAsAMember(UUID adminId, UUID adminDevice, SignalClient retired, SignalClient owner, boolean queued) throws Exception {
        String ownerKey = Base64.getEncoder().encodeToString(owner.publicIdentity());
        UUID groupId = UUID.randomUUID(), epoch = UUID.randomUUID();
        GroupChat.Snapshot group = new GroupChat.Snapshot(groupId, peerId, 2, epoch, false, List.of(
                new GroupChat.Member(peerId, peerDevice, ownerKey, "owner", null, "ACTIVE", 0),
                new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()), "alex", null, "ACTIVE", 0),
                new GroupChat.Member(adminId, adminDevice, Base64.getEncoder().encodeToString(retired.publicIdentity()), "vanishr", null, "ACTIVE", 0)));
        long deadline = System.currentTimeMillis() + 3_600_000;
        SignalGroup sending = new SignalGroup(new DeviceSecurityTest.MemoryVault(), groupId, epoch, adminId);
        GroupChat.ControlBody body = new GroupChat.ControlBody(1, "KEY", groupId, epoch, 2, adminId, userId, null, group.roster().digest(), sending.distribution(), deadline);
        SignalClient.Packet encrypted = retired.encrypt(userId, RelayApi.JSON.toJson(body).getBytes(StandardCharsets.UTF_8), Instant.now());
        GroupChat.Control control = new GroupChat.Control(UUID.randomUUID(), groupId, adminId, adminDevice, userId, deviceId, epoch, 2, deadline, encrypted.type(), encrypted.ciphertext());
        UUID queuedAdmin = UUID.randomUUID(), queuedOwner = UUID.randomUUID(), queuedMessage = UUID.randomUUID();
        vault.transaction(() -> {
            write("group/" + groupId, new GroupChat.Conversation(group, "Private group", null));
            write("group-approval/" + groupId + "/" + epoch, new GroupChat.Approval(group.roster(), "Private group", System.currentTimeMillis() + 86_400_000L));
            if (queued) {
                write("group-control-out/" + queuedAdmin, new GroupChat.QueuedControl(groupId, "group-mark/" + groupId + "/" + epoch + "/KEY/" + adminId,
                        new GroupChat.ControlSend(queuedAdmin, adminId, adminDevice, epoch, 2, deadline, 3, new byte[]{1, 2, 3})));
                write("group-control-out/" + queuedOwner, new GroupChat.QueuedControl(groupId, "group-mark/" + groupId + "/" + epoch + "/KEY/" + peerId,
                        new GroupChat.ControlSend(queuedOwner, peerId, peerDevice, epoch, 2, deadline, 3, new byte[]{4, 5, 6})));
                write("group-out/" + queuedMessage, new GroupChat.Outbox(groupId,
                        new GroupChat.Send(queuedMessage, epoch, 2, ChatEnvelope.Expiry.HOUR_1, deadline, new byte[]{7, 8, 9}, null, null)));
                write("entry/" + queuedMessage, new ChatEngine.Entry(queuedMessage, groupId, deadline, ChatEnvelope.Expiry.HOUR_1, true, false, "PENDING", userId, epoch, 2, userId));
            }
            return null;
        });
        List<GroupChat.ControlSend> posted = new ArrayList<>(); List<String> deleted = new ArrayList<>(); List<UUID> postedMessages = new ArrayList<>();
        ((RelayApi) readField(engine, "api")).close();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/groups")) return syntheticResponse(request, 200, List.of(group));
            if (path.equals("/groups/" + groupId + "/controls") && request.method().equals("GET")) return syntheticResponse(request, 200, List.of(control));
            if (path.startsWith("/groups/" + groupId + "/controls/") && request.method().equals("DELETE")) { deleted.add(path); return syntheticResponse(request, 200, Map.of()); }
            okio.Buffer buffer = new okio.Buffer();
            if (request.body() != null) request.body().writeTo(buffer);
            String text = buffer.readUtf8();
            if (path.endsWith("/keys")) {
                List<GroupChat.Claimed> answer = new ArrayList<>();
                try { for (UUID user : RelayApi.JSON.fromJson(text, GroupChat.Claims.class).users()) answer.add(new GroupChat.Claimed(user, (user.equals(peerId) ? owner : retired).generatePreKey(Instant.now()))); }
                catch (Exception failure) { throw new IOException("Synthetic prekey fixture failed"); }
                return syntheticResponse(request, 200, answer);
            }
            if (path.endsWith("/controls") && request.method().equals("POST")) { posted.addAll(RelayApi.JSON.fromJson(text, GroupChat.Controls.class).packets()); return syntheticResponse(request, 200, Map.of()); }
            if (path.equals("/groups/" + groupId + "/messages") && request.method().equals("POST")) {
                GroupChat.Send sent = RelayApi.JSON.fromJson(text, GroupChat.Send.class); postedMessages.add(sent.id());
                return syntheticResponse(request, 200, new GroupChat.Status(sent.id(), sent.expiresAt(), 1, 0, 0, "QUEUED"));
            }
            if (path.endsWith("/messages") || path.endsWith("/status")) return syntheticResponse(request, 200, List.of());
            throw new IOException("Unexpected synthetic group request");
        }));
        engine.groups().sync();
        return new AdminGroupRun(posted.stream().map(GroupChat.ControlSend::recipientId).toList(),
                vault.get("group-key/" + groupId + "/" + epoch + "/" + adminId) != null,
                vault.get("group-control-seen/" + control.id()) != null,
                deleted.contains("/groups/" + groupId + "/controls/" + control.id()),
                posted.stream().anyMatch(item -> item.id().equals(queuedAdmin)),
                posted.stream().anyMatch(item -> item.id().equals(queuedOwner)),
                vault.get("group-control-out/" + queuedAdmin) != null,
                postedMessages.contains(queuedMessage));
    }

    @Test public void aRetiredAdminInAGroupIAmNotTheOwnerOfGetsNoKeysFromMeAndNothingFromItIsBelieved() throws Exception {
        UUID adminId = UUID.randomUUID(), adminDevice = UUID.randomUUID(), currentDevice = UUID.randomUUID();
        SignalClient retired = new SignalClient(adminId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(adminId, new DeviceSecurityTest.MemoryVault());
        SignalClient owner = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(adminId, adminDevice, retired));
        String ownerKey = Base64.getEncoder().encodeToString(owner.publicIdentity());
        owner.verifyPeer(userId, engine.groupSignal().publicIdentity()); engine.groupSignal().verifyPeer(peerId, owner.publicIdentity());
        retired.verifyPeer(userId, engine.groupSignal().publicIdentity());
        retired.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
        vault.transaction(() -> { write("contact/" + peerId, new ChatEngine.Peer(peerId, peerDevice, ownerKey, "Owner", "owner", null)); return null; });

        AdminGroupRun before = runGroupWithTheAdminAsAMember(adminId, adminDevice, retired, owner);
        assertEquals("While the retired identity is the pinned one it is a member like any other: both get my key", Set.of(peerId, adminId), Set.copyOf(before.recipients()));
        assertEquals(2, before.recipients().size());
        assertTrue("and its key is taken", before.adminKeyTaken());
        assertTrue(before.adminControlSeen());

        engine.onboarding().adopt(certificate(adminId, currentDevice, current, 3));
        AdminGroupRun after = runGroupWithTheAdminAsAMember(adminId, adminDevice, retired, owner);
        assertEquals("Once the pin has moved only the owner gets my key", List.of(peerId), after.recipients());
        assertFalse("and nothing the retired identity sends is believed", after.adminKeyTaken());
        assertFalse(after.adminControlSeen());
        assertTrue("but it is taken off the relay's queue", after.adminControlDeleted());
    }

    @Test public void aMessageQueuedForTheAdminBeforeThePinMovedIsNotSentToTheRetiredDeviceAfterwards() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        var after = introduction(peerId, currentDevice, current, "vanishr");
        saveRetiredAdmin(before, retired);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(before.peer()), List.of(before), null));
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        adminTransport(directory, new ArrayList<>(), outgoing, null);
        setField(engine, "nextKeyCheck", System.currentTimeMillis() + 300_000);
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
        long expires = System.currentTimeMillis() + 3_600_000;
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        java.util.function.Consumer<UUID> queue = id -> {
            try {
                vault.transaction(() -> {
                    write("outbox/" + id, new ChatEngine.Outbox(new ChatEngine.Send(id, peerId, peerDevice, ChatEnvelope.Expiry.HOUR_1, expires, 3, new byte[]{1, 2, 3}, null), null));
                    write("entry/" + id, new ChatEngine.Entry(id, peerId, expires, ChatEnvelope.Expiry.HOUR_1, true, false, "PENDING", userId));
                    return null;
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        // While the retired identity is the pinned one, what is queued for the admin is sent as for any contact.
        queue.accept(first);
        engine.sync();
        assertEquals(List.of(first), outgoing.stream().map(ChatEngine.Send::id).toList());
        assertNull(vault.get("outbox/" + first));
        // The pin moves while something else is still waiting, encrypted for the device that is retired now.
        queue.accept(second);
        directory.set(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(after.peer()), List.of(after), null));
        engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
        engine.sync();
        assertEquals("Nothing was sent to the retired device", 1, outgoing.size());
        assertNull("and it is not kept to be tried again", vault.get("outbox/" + second));
        assertEquals("Not sent: contact unavailable", engine.entries(peerId).stream().filter(entry -> entry.id().equals(second)).findFirst().orElseThrow().state());
    }

    @Test public void aGroupMessageFromTheRetiredAdminIsNotReadEvenThoughItsKeyWasAcceptedBeforeThePinMoved() throws Exception {
        UUID adminId = UUID.randomUUID(), adminDevice = UUID.randomUUID(), currentDevice = UUID.randomUUID();
        SignalClient retired = new SignalClient(adminId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(adminId, new DeviceSecurityTest.MemoryVault());
        SignalClient owner = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(adminId, adminDevice, retired));
        String ownerKey = Base64.getEncoder().encodeToString(owner.publicIdentity());
        owner.verifyPeer(userId, engine.groupSignal().publicIdentity()); engine.groupSignal().verifyPeer(peerId, owner.publicIdentity());
        UUID groupId = UUID.randomUUID(), epoch = UUID.randomUUID();
        GroupChat.Snapshot group = new GroupChat.Snapshot(groupId, peerId, 2, epoch, false, List.of(
                new GroupChat.Member(peerId, peerDevice, ownerKey, "owner", null, "ACTIVE", 0),
                new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()), "alex", null, "ACTIVE", 0),
                new GroupChat.Member(adminId, adminDevice, Base64.getEncoder().encodeToString(retired.publicIdentity()), "vanishr", null, "ACTIVE", 0)));
        long created = System.currentTimeMillis(), deadline = created + 3_600_000;
        SignalGroup sending = new SignalGroup(new DeviceSecurityTest.MemoryVault(), groupId, epoch, adminId);
        byte[] distribution = sending.distribution();
        String keyMarker = "group-key/" + groupId + "/" + epoch + "/" + adminId;
        vault.transaction(() -> {
            write("contact/" + peerId, new ChatEngine.Peer(peerId, peerDevice, ownerKey, "Owner", "owner", null));
            write("group/" + groupId, new GroupChat.Conversation(group, "Private group", null));
            write("group-approval/" + groupId + "/" + epoch, new GroupChat.Approval(group.roster(), "Private group", deadline));
            // My key has been sent to everybody and the admin's key was accepted, all while the admin's earlier identity was the pinned one.
            for (UUID member : List.of(peerId, adminId))
                vault.put("group-mark/" + groupId + "/" + epoch + "/KEY/" + member, Long.toString(deadline).getBytes(StandardCharsets.US_ASCII));
            new SignalGroup(vault.accountVault(engine.account()), groupId, epoch, userId).accept(adminId, distribution);
            vault.put(keyMarker, Long.toString(deadline).getBytes(StandardCharsets.US_ASCII));
            return null;
        });
        java.util.function.Function<String, GroupChat.Message> fromTheAdmin = text -> {
            try {
                UUID id = UUID.randomUUID();
                GroupEnvelope envelope = new GroupEnvelope(1, groupId, epoch, 2, new ChatEnvelope(1, id, adminId, adminDevice, groupId, epoch, created, deadline, ChatEnvelope.Expiry.HOUR_1, text, null));
                createFixtureKey(AndroidVault.contentAlias(deadline, id, userId));
                return new GroupChat.Message(id, groupId, epoch, 2, adminId, adminDevice, ChatEnvelope.Expiry.HOUR_1, deadline,
                        sending.encrypt(RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8)), null);
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        GroupChat.Message early = fromTheAdmin.apply("Sent while that identity was the admin"), late = fromTheAdmin.apply("Sent after the pin moved");
        List<GroupChat.Message> inbox = new ArrayList<>(); List<String> acknowledgements = new ArrayList<>();
        ((RelayApi) readField(engine, "api")).close();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/groups")) return syntheticResponse(request, 200, List.of(group));
            if (path.equals("/groups/" + groupId + "/controls") || path.equals("/groups/" + groupId + "/status")) return syntheticResponse(request, 200, List.of());
            if (path.equals("/groups/" + groupId + "/messages") && request.method().equals("GET")) return syntheticResponse(request, 200, new ArrayList<>(inbox));
            if (path.matches("/groups/" + groupId + "/messages/[0-9a-f-]+/(delivered|read|delete)")) { acknowledgements.add(path); return syntheticResponse(request, 200, Map.of()); }
            throw new IOException("Unexpected synthetic group request");
        }));

        inbox.add(early);
        engine.groups().sync();
        assertEquals("While the earlier identity is the pinned one its message is read like anybody's", 1, engine.entries(groupId).size());
        assertEquals(adminId, engine.entries(groupId).get(0).senderId());

        engine.onboarding().adopt(certificate(adminId, currentDevice, current, 3));
        inbox.clear(); inbox.add(late);
        engine.groups().sync();
        assertEquals("Once the pin has moved what that identity sends is not read, although its key is still on the phone", 1, engine.entries(groupId).size());
        assertTrue("The key is still there, so it is the retirement and not a missing key that stops it", vault.get(keyMarker) != null);
        assertTrue("and the relay is asked to delete this phone's copy", acknowledgements.stream().anyMatch(path -> path.endsWith("/messages/" + late.id() + "/delete")));
        assertNotNull("so that it is not offered again", vault.get("seen/" + late.id()));
        assertTrue("Nothing of it was kept", engine.entries(groupId).stream().noneMatch(entry -> entry.id().equals(late.id())));
    }

    @Test public void aGroupKeyQueuedForTheRetiredAdminDeviceIsNotSentOnceThePinHasMoved() throws Exception {
        UUID adminId = UUID.randomUUID(), adminDevice = UUID.randomUUID(), currentDevice = UUID.randomUUID();
        SignalClient retired = new SignalClient(adminId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(adminId, new DeviceSecurityTest.MemoryVault());
        SignalClient owner = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(adminPin(adminId, adminDevice, retired));
        String ownerKey = Base64.getEncoder().encodeToString(owner.publicIdentity());
        owner.verifyPeer(userId, engine.groupSignal().publicIdentity()); engine.groupSignal().verifyPeer(peerId, owner.publicIdentity());
        retired.verifyPeer(userId, engine.groupSignal().publicIdentity());
        retired.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
        vault.transaction(() -> { write("contact/" + peerId, new ChatEngine.Peer(peerId, peerDevice, ownerKey, "Owner", "owner", null)); return null; });

        AdminGroupRun before = runGroupWithTheAdminAsAMember(adminId, adminDevice, retired, owner, true);
        assertTrue("While the device is the pinned one, what was waiting for it is sent", before.queuedAdminControlSent());
        assertTrue(before.queuedOwnerControlSent());
        assertTrue("and my own message goes out", before.queuedMessagePosted());

        engine.onboarding().adopt(certificate(adminId, currentDevice, current, 3));
        AdminGroupRun after = runGroupWithTheAdminAsAMember(adminId, adminDevice, retired, owner, true);
        assertFalse("Once the pin has moved it is not", after.queuedAdminControlSent());
        assertFalse("and it is not kept to be tried again", after.queuedAdminControlLeft());
        assertTrue("What was waiting for anybody else is sent as before", after.queuedOwnerControlSent());
        assertTrue("and the group is not held up by a member that is retired: my message still goes out", after.queuedMessagePosted());
    }

    @Test public void theCheckInsideTheReplacementRefusesAnyIdentityButThePinnedOneEvenWhenNobodyValidatedFirst() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient impostor = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        var setup = retiredAdminSetup(retired, current, currentDevice);
        java.lang.reflect.Method store = AdminOnboarding.class.getDeclaredMethod("store", ChatEngine.Peer.class);
        store.setAccessible(true);
        // Every caller of the replacement validates what the relay showed against the pin first. This calls it directly, as a caller that did
        // not would, with an identity that is not the pinned one: it must be the replacement itself that refuses it.
        var fake = introduction(peerId, UUID.randomUUID(), impostor, "vanishr");
        Throwable refused = assertThrows(Exception.class, () -> vault.transaction(() -> { store.invoke(engine.onboarding(), fake.peer()); return null; }));
        boolean mismatch = false;
        for (Throwable cause = refused; cause != null && !mismatch; cause = cause.getCause()) mismatch = cause instanceof AdminOnboarding.Mismatch;
        assertTrue("Refused because it is not the pinned identity", mismatch);
        assertEquals("Nothing was replaced", setup[0].peer(), engine.peer(peerId));
        assertThrows(SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, impostor.publicIdentity()));
        assertTrue(engine.onboarding().rotationPending(peerId));
        // The pinned identity, shown the same way, is what replaces it.
        vault.transaction(() -> { store.invoke(engine.onboarding(), setup[1].peer()); return null; });
        assertEquals(setup[1].peer(), engine.peer(peerId));
        assertFalse(engine.onboarding().rotationPending(peerId));
    }

    @Test public void aCertificateKeptFromAnEarlierRunIsFollowedWhenTheScreenLoadsTheEngine() throws Exception {
        signedInFixture();
        UUID admin = AdminOnboarding.OFFICIAL.userId(), currentDevice = UUID.randomUUID();
        SignalClient current = new SignalClient(admin, new DeviceSecurityTest.MemoryVault());
        java.security.KeyPair authority = adminAuthorityPair();
        long sequence = AdminIdentity.BUILD_SEQUENCE_FLOOR + 3;
        String expectedFingerprint = fingerprintOf(admin, current);
        byte[] kept = signedAdminIdentity(authority, "t1", sequence, admin, currentDevice, expectedFingerprint);
        android.content.SharedPreferences stored = context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE);
        assertTrue(stored.edit().clear().putString("envelope", new String(kept, StandardCharsets.UTF_8)).putLong("sequence", sequence).commit());
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            awaitMainCondition(scenario, activity -> readField(activity, "engine") != null);
            // The engine the screen loaded first could not check the certificate (it is signed by a key this build does not list). With the
            // key in place, the screen loads its engine again, exactly as it does on a start.
            scenario.onActivity(activity -> {
                assertEquals("Without its key the certificate changes nothing", AdminOnboarding.OFFICIAL, ((ChatEngine) readField(activity, "engine")).onboarding().pin());
                setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                ChatEngine first = (ChatEngine) readField(activity, "engine");
                setField(activity, "engine", null);
                if (first != engine) first.close();
                setField(activity, "loadingGeneration", -1);
                invoke(activity, "load");
            });
            awaitMainCondition(scenario, activity -> readField(activity, "engine") != null);
            scenario.onActivity(activity -> {
                AdminOnboarding.Pin pin = ((ChatEngine) readField(activity, "engine")).onboarding().pin();
                assertEquals("A certificate kept from an earlier run is followed from the start", currentDevice, pin.deviceId());
                assertEquals(expectedFingerprint, pin.fingerprint());
                assertEquals("and only the device and the identity moved", AdminOnboarding.OFFICIAL.origin(), pin.origin());
            });
        }
    }

    @Test public void aLivePhotoSessionWithTheRetiredAdminEndsAtItsNextStepInsteadOfSendingAnotherFrame() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        saveRetiredAdmin(before, retired);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(before.peer()), List.of(before), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        long deadline = System.currentTimeMillis() + 300_000;
        ChatEngine.Contact own = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()));
        // The user approved a photo session for the admin while the earlier identity was the pinned one.
        RemotePhotoSession.Session request = new RemotePhotoSession.Session(UUID.randomUUID(), RemotePhotoSession.contact(before.peer()), own,
                false, deadline, retired.generatePreKey(Instant.now()));
        RemotePhotoSession.Prepared prepared = new RemotePhotoSession.Prepared(engine, before.peer(), request);
        try (RemotePhotoSession session = new RemotePhotoSession(context, prepared)) {
            session.tick();
            assertFalse("While that identity is the admin's the session goes on", prepared.retired.getAsBoolean());
            engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
            assertTrue(prepared.retired.getAsBoolean());
            assertThrows("The next step ends the session, so nothing more is encrypted to the retired identity or read from it", SecurityException.class, session::tick);
        }
    }

    // An owner's photo session with the official admin's earlier identity, where the pin moves while the request for `movesAt` is on its way.
    // Returns the steps that reached the relay.
    private List<String> ownerPhotoStepWhileThePinMovesDuring(String movesAt) throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        var before = introduction(peerId, peerDevice, retired, "vanishr");
        saveRetiredAdmin(before, retired);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, RemotePhotoSession.contact(before.peer()), List.of(before), null));
        adminTransport(directory, new ArrayList<>(), new ArrayList<>(), null);
        long deadline = System.currentTimeMillis() + 300_000;
        ChatEngine.Contact own = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()));
        RemotePhotoSession.Session request = new RemotePhotoSession.Session(UUID.randomUUID(), RemotePhotoSession.contact(before.peer()), own,
                false, deadline, retired.generatePreKey(Instant.now()));
        RemotePhotoSession.Prepared prepared = new RemotePhotoSession.Prepared(engine, before.peer(), request);
        AdminIdentity.Certificate moved = certificate(peerId, currentDevice, current, 3);
        List<String> steps = new ArrayList<>();
        RelayApi photoApi = syntheticApi(chain -> {
            okhttp3.Request http = chain.request(); String path = http.url().encodedPath();
            String step = path.substring(path.lastIndexOf('/') + 1);
            steps.add(step);
            if (step.equals(movesAt)) engine.onboarding().adopt(moved);
            if (step.equals("accept"))
                return syntheticResponse(http, 200, new RemotePhotoSession.Session(request.id(), request.requester(), request.owner(), true, request.expiresAt(), request.key()));
            if (step.equals("exchange"))
                return syntheticResponse(http, 200, new RemotePhotoSession.Delivery(new RemotePhotoSession.Packet(UUID.randomUUID(), peerId, peerDevice,
                        System.currentTimeMillis() + 60_000, 3, new byte[]{1, 2, 3})));
            throw new IOException("Unexpected photo request");
        });
        setField(prepared.api, "client", readField(photoApi, "client"));
        try (RemotePhotoSession session = new RemotePhotoSession(context, prepared)) {
            setField(session, "connected", true);
            SecurityException refused = assertThrows(SecurityException.class, session::tick);
            assertEquals("The step ends because the other phone is no longer trusted, not because of what it sent", "The other phone is no longer trusted", refused.getMessage());
            assertTrue(prepared.retired.getAsBoolean());
        }
        return steps;
    }

    @Test public void aPhotoStepPreparedWhileThePinMovesIsNeverSentToTheRetiredPhone() throws Exception {
        assertEquals("Nothing was exchanged once the pin had moved", List.of("accept"), ownerPhotoStepWhileThePinMovesDuring("accept"));
    }

    @Test public void aPhotoExchangeOnItsWayWhenThePinMovesIsNotAnsweredByReadingTheRetiredPhone() throws Exception {
        assertEquals(List.of("accept", "exchange"), ownerPhotoStepWhileThePinMovesDuring("exchange"));
    }

    @Test public void aProfilePhotoRevocationQueuedForTheAdminBeforeThePinMovedIsNotSentToTheRetiredDevice() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        saveRetiredAdmin(introduction(peerId, peerDevice, retired, "vanishr"), retired);
        List<String> posted = new ArrayList<>();
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/profile/packets") && request.method().equals("GET")) return syntheticResponse(request, 200, List.of());
            if (path.equals("/profile/packets") && request.method().equals("POST")) { posted.add(path); return syntheticResponse(request, 200, null); }
            throw new IOException("Unexpected profile photo request");
        }));
        long deadline = System.currentTimeMillis() + 120_000;
        String identityKey = Base64.getEncoder().encodeToString(retired.publicIdentity());
        java.util.function.Consumer<UUID> queue = id -> {
            try {
                vault.transaction(() -> {
                    write("profile-photo-out/" + id, new ProfilePhotos.Queued(new ProfilePhotos.Send(id, peerId, peerDevice, deadline, 2, new byte[]{1}),
                            ProfileEnvelope.Action.REVOKE, id, identityKey, 1));
                    return null;
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        queue.accept(first);
        engine.photos().sync();
        assertEquals("While that identity is the pinned one a revocation is sent as to any contact", 1, posted.size());
        queue.accept(second);
        engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
        engine.photos().sync();
        assertEquals("Once the pin has moved nothing is sent to the retired device", 1, posted.size());
        assertNull("and it is not kept to be tried again", vault.get("profile-photo-out/" + second));
    }

    @Test public void aMessageFromTheRetiredAdminDeviceIsDeletedAtTheRelayInsteadOfStoppingEverySync() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        var setup = retiredAdminSetup(retired, current, currentDevice);
        var directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId,
                RemotePhotoSession.contact(setup[1].peer()), List.of(setup[1]), null));
        List<ChatEngine.Incoming> incoming = new ArrayList<>();
        adminTransport(directory, incoming, new ArrayList<>(), null);
        setField(engine, "nextKeyCheck", System.currentTimeMillis() + 300_000);
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
        // The pin is the new phone's. The old phone sent this before the change and it was still waiting at the relay.
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 120_000;
        incoming.add(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, 3, new byte[]{1, 2, 3}, null));
        engine.sync();
        assertTrue("The relay is asked to delete it", deletedMessages.contains("/messages/" + id));
        assertTrue("and it is not offered again", vault.get("seen/" + id) != null);
        assertTrue("Nothing was read from it", engine.entries(peerId).stream().noneMatch(entry -> id.equals(entry.id())));
        assertEquals("The sync went on to the end, so the saved contact is the new identity now", setup[1].peer(), engine.peer(peerId));
        assertFalse(engine.onboarding().rotationPending(peerId));
    }

    @Test public void aCertificateAnotherScreenAlreadyKeptIsTakenWithoutAskingAnybody() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        AdminOnboarding.Pin retiredPin = engine.onboarding().pin();
        java.security.KeyPair authority = adminAuthorityPair();
        String fingerprint = fingerprintOf(peerId, current);
        long newer = AdminIdentity.BUILD_SEQUENCE_FLOOR + 1;
        byte[] kept = signedAdminIdentity(authority, "t1", newer, peerId, currentDevice, fingerprint);
        java.util.concurrent.atomic.AtomicReference<byte[]> served = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient transport = certificateTransport(served, requests);
        android.content.SharedPreferences stored = context.getSharedPreferences("admin-identity", Context.MODE_PRIVATE);
        // Another screen of the app fetched it a moment ago: it is kept with its number, and that request is on record.
        assertTrue(stored.edit().clear().putString("envelope", new String(kept, StandardCharsets.UTF_8)).putLong("sequence", newer)
                .putLong("last-attempt", System.currentTimeMillis()).commit());
        try (ActivityScenario<MainActivity> scenario = launchSettled()) {
            scenario.onActivity(activity -> {
                setField(activity, "adminKeys", Map.of("t1", authority.getPublic()));
                setField(activity, "adminIdentitySource", (java.util.function.Supplier<RemoteSettings>) () -> RemoteSettings.forAdminIdentity(transport));
                inject(activity);
            });
            assertEquals("This engine was loaded before the certificate was kept", retiredPin, engine.onboarding().pin());
            scenario.onActivity(activity -> invoke(activity, "requestAdminIdentity", new Class<?>[]{ChatEngine.class}, engine));
            awaitMainCondition(scenario, activity -> engine.onboarding().pin().deviceId().equals(currentDevice));
            assertEquals(fingerprint, engine.onboarding().pin().fingerprint());
            assertEquals("It was taken from the store, without asking anybody", 0, requests.get());
        }
    }

    @Test public void aRemotePhotoRequestFromTheAdminsCertifiedNewIdentityIsMatchedInsteadOfWaitingOnAStaleSavedContactForever() throws Exception {
        SignalClient retired = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        SignalClient current = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        UUID currentDevice = UUID.randomUUID();
        signedInFixture(adminPin(peerId, peerDevice, retired));
        saveRetiredAdmin(introduction(peerId, peerDevice, retired, "vanishr"), retired);
        // The admin's phone has since been certified onto a new device, but this phone has not yet synced a chat or directory refresh
        // that would otherwise have replaced its saved contact for the admin (AdminOnboarding.store).
        engine.onboarding().adopt(certificate(peerId, currentDevice, current, 3));
        assertTrue("Fixture: the saved contact is stale relative to the pin", engine.onboarding().rotationPending(peerId));

        ChatEngine.Contact own = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()));
        ChatEngine.Contact newAdmin = new ChatEngine.Contact(peerId, currentDevice, Base64.getEncoder().encodeToString(current.publicIdentity()));
        RemotePhotoSession.Session pending = new RemotePhotoSession.Session(UUID.randomUUID(), newAdmin, own, false,
                System.currentTimeMillis() + 300_000, current.generatePreKey(Instant.now()));
        var newIntroduction = introduction(peerId, currentDevice, current, "vanishr");
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            okhttp3.Request request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/account/type")) return syntheticResponse(request, 200, new RemotePhotoSession.AccountType(userId, "USER"));
            if (path.equals("/remote-photos") && request.method().equals("GET")) return syntheticResponse(request, 200, List.of(pending));
            if (path.equals("/account/admin-contacts/" + peerId))
                return syntheticResponse(request, 200, new AdminOnboarding.Page(userId, newAdmin, List.of(newIntroduction), null));
            throw new IOException("Unexpected request in this bounded match-only scenario: " + path);
        }));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                inject(activity);
                // Bounds this test to the matching/self-heal step: the request is already "seen", so a live photo session is never
                // started here (that machinery is exercised by the other remote-photos tests).
                ((Map<UUID, Long>) readField(activity, "promptedPhotos")).put(pending.id(), System.currentTimeMillis() + 60_000);
                var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
                try { worker.submit(() -> invoke(activity, "photoRequestsOnce")).get(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure.getCause() == null ? failure : failure.getCause()); }
            });
            assertFalse("The saved contact self-heals to the admin's current, certified identity instead of staying stale",
                    engine.onboarding().rotationPending(peerId));
            assertEquals(newAdmin, RemotePhotoSession.contact(engine.peer(peerId)));
            assertTrue("The request is then recognised as coming from a trusted (official admin) contact, not silently ignored forever",
                    RemotePhotoSession.trustedContact(engine, engine.peer(peerId)));
        }
    }

    // ==== end 0.6.3 ====

    // ==== message replies, reactions, edits and delete-for-everyone ====

    /** A plain, non-admin synthetic relay for {@code peer}: messages, keys and an empty admin directory (so sync still completes),
     * and the plain identity lookup that both sending and the automatic profile-photo exchange make for a verified contact. */
    private final List<byte[]> uploadedMedia = new ArrayList<>();
    private final List<UUID> fixtureBlocked = new ArrayList<>();

    private void plainTransport(List<ChatEngine.Incoming> incoming, List<ChatEngine.Send> outgoing) {
        engine.groupApi().close();
        setField(engine, "api", syntheticApi(chain -> {
            var request = chain.request(); String path = request.url().encodedPath();
            if (path.equals("/account/admin-contacts")) return syntheticResponse(request, 200, new AdminOnboarding.Page(userId, null, List.of(), null));
            if (path.equals("/users/id/" + peerId)) return syntheticResponse(request, 200, RemotePhotoSession.contact(peer));
            if (path.equals("/account/profile")) return syntheticResponse(request, 200, new ChatEngine.Profile(userId, "alex", null));
            if (path.equals("/account/type")) return syntheticResponse(request, 200, new RemotePhotoSession.AccountType(userId, "USER"));
            if (path.equals("/account/blocks") && request.method().equals("GET")) return syntheticResponse(request, 200, List.copyOf(fixtureBlocked));
            if (path.startsWith("/account/blocks/") && request.method().equals("PUT")) {
                fixtureBlocked.add(UUID.fromString(path.substring("/account/blocks/".length())));
                return syntheticResponse(request, 200, null);
            }
            if (path.equals("/messages/pending")) {
                List<ChatEngine.Incoming> batch = new ArrayList<>(incoming); incoming.clear();
                return syntheticResponse(request, 200, batch);
            }
            if (path.equals("/messages") && request.method().equals("POST")) {
                okio.Buffer body = new okio.Buffer(); request.body().writeTo(body);
                ChatEngine.Send sent = RelayApi.JSON.fromJson(body.readUtf8(), ChatEngine.Send.class); outgoing.add(sent);
                return syntheticResponse(request, 200, new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt()));
            }
            if (path.startsWith("/messages/status")) return syntheticResponse(request, 200,
                outgoing.stream().map(sent -> new ChatEngine.Status(sent.id(), "QUEUED", sent.expiresAt())).toList());
            if (path.matches("/messages/[0-9a-f-]+") && request.method().equals("DELETE")) return syntheticResponse(request, 200, null);
            if (path.matches("/messages/[0-9a-f-]+/(delivered|read)")) return syntheticResponse(request, 200, null);
            if (path.startsWith("/media/") && request.method().equals("PUT")) {
                okio.Buffer body = new okio.Buffer(); request.body().writeTo(body); uploadedMedia.add(body.readByteArray());
                return syntheticResponse(request, 200, null);
            }
            if (path.equals("/keys")) return syntheticResponse(request, 200, new ChatEngine.KeyCount(16));
            if (Set.of("/groups", "/profile/packets", "/remote-photos", "/presence").contains(path)) return syntheticResponse(request, 200, List.of());
            if (path.equals("/events")) return syntheticResponse(request, 503, Map.of("error", "fixture_offline"));
            throw new AssertionError("Unexpected plain-transport fixture request: " + request.method() + " " + path);
        }));
        // Not under test here, and the fixture does not serve a profile for an ordinary (non-admin-directory) contact.
        setField(engine, "nextProfileCheck", System.currentTimeMillis() + 300_000);
    }

    /** {@code peer}/{@code peerId}/{@code peerDevice} become a saved, verified, session-ready contact of {@code remote}, with no network call. */
    private void establishedConversation(SignalClient remote) throws Exception {
        peer = new ChatEngine.Peer(peerId, peerDevice, Base64.getEncoder().encodeToString(remote.publicIdentity()), "Test peer");
        vault.transaction(() -> { engine.groupSignal().verifyPeer(peerId, remote.publicIdentity()); write("contact/" + peerId, peer); return null; });
        remote.verifyPeer(userId, engine.groupSignal().publicIdentity());
        // Both directions are independently established (as two real phones would be after their first messages), so either side
        // can encrypt first in these tests without needing the other to have sent something yet.
        engine.groupSignal().establish(peerId, remote.generatePreKey(Instant.now()), Instant.now());
        remote.establish(userId, engine.groupSignal().generatePreKey(Instant.now()), Instant.now());
    }

    private ChatEnvelope decryptControl(SignalClient remote, ChatEngine.Send sent) throws Exception {
        byte[] plaintext = remote.decrypt(userId, new SignalClient.Packet(sent.type(), sent.ciphertext()));
        try { return RelayApi.JSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), ChatEnvelope.class); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    private void deliverControl(SignalClient remote, List<ChatEngine.Incoming> incoming, String kind, ChatEnvelope.Action action) throws Exception {
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 3_600_000;
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId, now, deadline,
            ChatEnvelope.Expiry.HOUR_1, null, null, kind, null, action);
        SignalClient.Packet packet = remote.encrypt(userId, RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
        incoming.add(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null));
    }

    @Test public void aReactionIsSentAppliedLocallyAtOnceAndCanBeRemoved() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry message = entry(true, "Hello there", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        String emoji = ChatEnvelope.REACTIONS.iterator().next();
        engine.react(message, emoji);
        assertEquals("Exactly one control message is sent", 1, outgoing.size());
        assertEquals(emoji, engine.entry(message.id()).reactionMine());
        ChatEnvelope sent = decryptControl(remote, outgoing.get(0));
        assertEquals(ChatEnvelope.REACTION, sent.kind());
        assertEquals(message.id(), sent.action().targetId());
        assertEquals(emoji, sent.action().emoji());
        assertFalse("The control itself is never a visible message", engine.entries(peerId).stream().anyMatch(e -> e.id().equals(outgoing.get(0).id())));
        engine.react(message, null);
        assertNull(engine.entry(message.id()).reactionMine());
        assertEquals(2, outgoing.size());
    }

    @Test public void aReplySendsTheQuotedMessagesIdAndSnippetWithoutMarkingTheQuotedMessageItself() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        UUID originalId = UUID.randomUUID();
        engine.send(peer, "Original message", null, ChatEnvelope.Expiry.HOUR_1, null, originalId, System.currentTimeMillis(), () -> { });
        UUID replyId = UUID.randomUUID();
        ChatEnvelope.Reference reference = new ChatEnvelope.Reference(originalId, "Original mess\u2026");
        engine.send(peer, "Here is my reply", null, ChatEnvelope.Expiry.HOUR_1, reference, replyId, System.currentTimeMillis(), () -> { });
        assertEquals(reference, engine.content(engine.entry(replyId), false, false).envelope().reference());
        assertNull("The quoted message itself carries no reference", engine.content(engine.entry(originalId), false, false).envelope().reference());
        assertEquals("Both messages were sent over the wire", 2, outgoing.size());
        ChatEnvelope wire = decryptControl(remote, outgoing.get(1));
        assertEquals(reference, wire.reference());
        assertNull("A reply is an ordinary message, not a control kind", wire.kind());
    }

    @Test public void onlyTheSenderCanEditOrDeleteTheirOwnSentMessageForEveryone() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry theirs = entry(false, "From the other side", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        assertThrows(SecurityException.class, () -> engine.editMessage(theirs, "not allowed"));
        assertThrows(SecurityException.class, () -> engine.deleteForEveryone(theirs));
        assertNotNull("Neither call changed anything", engine.entry(theirs.id()));
    }

    @Test public void anEditReplacesTheTextSentToThePeerAndMarksTheMessageEdited() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry message = entry(true, "Original text", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        engine.editMessage(message, "Updated text");
        assertEquals("Updated text", engine.content(engine.entry(message.id()), false, false).envelope().text());
        assertTrue(engine.entry(message.id()).edited());
        assertEquals(message.expiresAt(), engine.entry(message.id()).expiresAt());
        ChatEnvelope sent = decryptControl(remote, outgoing.get(0));
        assertEquals(ChatEnvelope.EDIT, sent.kind());
        assertEquals("Updated text", sent.action().text());
        assertThrows("Blank text is refused before anything is sent", IllegalArgumentException.class, () -> engine.editMessage(message, "   "));
    }

    @Test public void aDeleteForEveryoneRemovesTheMessageLocallyAndTellsThePeer() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry message = entry(true, "To be withdrawn", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        engine.deleteForEveryone(message);
        assertNull(engine.entry(message.id()));
        ChatEnvelope sent = decryptControl(remote, outgoing.get(0));
        assertEquals(ChatEnvelope.DELETE, sent.kind());
        assertEquals(message.id(), sent.action().targetId());
    }

    @Test public void anIncomingReactionEditOrDeleteOnlyAppliesToAnEntryFromThatSameSender() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry fromPeer = entry(false, "Sent by the peer", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        deliverControl(remote, incoming, ChatEnvelope.REACTION, new ChatEnvelope.Action(fromPeer.id(), ChatEnvelope.REACTIONS.iterator().next(), null));
        UUID unrelated = UUID.randomUUID();
        deliverControl(remote, incoming, ChatEnvelope.REACTION, new ChatEnvelope.Action(unrelated, ChatEnvelope.REACTIONS.iterator().next(), null));
        engine.sync();
        assertEquals("The entry really sent by this peer is reacted to", ChatEnvelope.REACTIONS.iterator().next(), engine.entry(fromPeer.id()).reactionTheirs());
        assertNull("A reaction naming an id that is not an entry from this peer changes nothing", engine.entry(unrelated));
    }

    @Test public void anIncomingEditOrDeleteNeverAppliesToAMessageThisPhoneSentToThatPeer() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry mine = entry(true, "Sent by me", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        deliverControl(remote, incoming, ChatEnvelope.EDIT, new ChatEnvelope.Action(mine.id(), null, "forged edit"));
        engine.sync();
        assertEquals("Sent by me", engine.content(engine.entry(mine.id()), false, false).envelope().text());
        assertFalse(engine.entry(mine.id()).edited());
        deliverControl(remote, incoming, ChatEnvelope.DELETE, new ChatEnvelope.Action(mine.id(), null, null));
        engine.sync();
        assertNotNull("A delete claimed by the peer for a message I sent is ignored", engine.entry(mine.id()));
        // A reaction on my own sent message is still allowed, from either side.
        deliverControl(remote, incoming, ChatEnvelope.REACTION, new ChatEnvelope.Action(mine.id(), ChatEnvelope.REACTIONS.iterator().next(), null));
        engine.sync();
        assertEquals(ChatEnvelope.REACTIONS.iterator().next(), engine.entry(mine.id()).reactionTheirs());
    }

    @Test public void anIncomingEditUpdatesWhatIsStoredAndDisplayedForAMessageThePeerSentToMe() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry theirs = entry(false, "Original from the peer", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -60_000);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        deliverControl(remote, incoming, ChatEnvelope.EDIT, new ChatEnvelope.Action(theirs.id(), null, "Updated by the peer"));
        engine.sync();
        assertEquals("Updated by the peer", engine.content(engine.entry(theirs.id()), false, false).envelope().text());
        assertTrue(engine.entry(theirs.id()).edited());
        deliverControl(remote, incoming, ChatEnvelope.DELETE, new ChatEnvelope.Action(theirs.id(), null, null));
        engine.sync();
        assertNull("A delete from the peer for their own message removes it here too", engine.entry(theirs.id()));
    }

    @Test public void anIncomingReplyCarriesItsQuotedMessageIdAndSnippetToTheRecipient() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        ChatEnvelope.Reference reference = new ChatEnvelope.Reference(UUID.randomUUID(), "What they are replying to");
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + 3_600_000;
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId, now, deadline,
            ChatEnvelope.Expiry.HOUR_1, "Replying to your earlier message", null, null, reference, null);
        SignalClient.Packet packet = remote.encrypt(userId, RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
        incoming.add(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, ChatEnvelope.Expiry.HOUR_1, now, deadline, packet.type(), packet.ciphertext(), null));
        engine.sync();
        ChatEngine.Entry stored = engine.entry(id);
        assertNotNull("The message was received", stored);
        assertEquals(reference, engine.content(stored, false, false).envelope().reference());
    }

    // ==== end message replies, reactions, edits and delete-for-everyone ====

    private int dpPx(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private PhotoViewer openPhoto(ActivityScenario<MainActivity> scenario, ChatEngine.Entry entry, boolean confirmViewOnce) throws Exception {
        scenario.onActivity(activity -> invoke(activity, "viewContent", new Class<?>[]{ChatEngine.Entry.class}, entry));
        if (confirmViewOnce) scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") != null);
        PhotoViewer[] viewer = new PhotoViewer[1];
        scenario.onActivity(activity -> viewer[0] = (PhotoViewer) readField(activity, "photoViewer"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        return viewer[0];
    }

    private void assertNoPopup(MainActivity activity, String message) {
        SecureSheet sheet = dialog(activity);
        assertTrue(message, sheet == null || !sheet.isShowing());
    }

    private View photoClose(PhotoViewer viewer) {
        return descendants(viewer.getWindow().getDecorView()).stream()
                .filter(view -> "Close photo".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                .findFirst().orElseThrow(() -> new AssertionError("The photo needs a close button"));
    }

    private void viewerSnapshot(ActivityScenario<MainActivity> scenario, PhotoViewer viewer, String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            View view = viewer.getWindow().getDecorView();
            assertTrue((viewer.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertTrue("The photo must be laid out", view.getWidth() > 0 && view.getHeight() > 0);
            Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(image));
            File folder = new File(context.getExternalFilesDir(null), "ui-qa"); assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            catch (IOException failure) { throw new AssertionError(failure); }
            finally { image.recycle(); }
        });
    }

    @androidx.annotation.RequiresApi(29)
    private List<Uri> galleryCopies() {
        List<Uri> found = new ArrayList<>();
        Uri collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (android.database.Cursor cursor = context.getContentResolver().query(collection, new String[]{android.provider.MediaStore.MediaColumns._ID},
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{"Pictures/Vanishr%", "Vanishr_%"}, null)) {
            while (cursor != null && cursor.moveToNext()) found.add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)));
        }
        return found;
    }

    @androidx.annotation.RequiresApi(29)
    private void deleteGalleryCopies() { for (Uri copy : galleryCopies()) context.getContentResolver().delete(copy, null, null); }

    private byte[] readGalleryCopy(Uri copy) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(copy)) { return input.readAllBytes(); }
    }

    @androidx.annotation.RequiresApi(29)
    private void awaitGalleryCopies(int count) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
        while (galleryCopies().size() < count && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertEquals(count, galleryCopies().size());
    }

    @Test public void photosFillTheScreenWithOnlyACloseButtonAndOrdinaryAccountsCannotSaveThem() throws Exception {
        signedInFixture(); conversationsFixture();
        assertFalse(engine.onboarding().admin());
        ChatEngine.Entry regular = entry(false, null, photo, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -30_000);
        ChatEngine.Entry viewOnce = entry(false, null, photo, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -20_000);
        if (Build.VERSION.SDK_INT >= 29) deleteGalleryCopies();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            PhotoViewer viewer = openPhoto(scenario, regular, false);
            scenario.onActivity(activity -> {
                assertNoPopup(activity, "A photo is no longer shown in a bottom sheet");
                assertTrue(viewer.isShowing());
                View decor = viewer.getWindow().getDecorView();
                ImageView image = viewer.image();
                int screen = activity.getWindow().getDecorView().getWidth();
                assertEquals("The photo view spans the whole screen width", screen, image.getWidth());
                assertEquals("The viewer window spans the whole screen width", screen, decor.getWidth());
                assertEquals(0, image.getPaddingLeft()); assertEquals(0, image.getPaddingRight());
                assertEquals(0, image.getPaddingTop()); assertEquals(0, image.getPaddingBottom());
                int[] at = new int[2]; image.getLocationOnScreen(at);
                assertEquals("No border on the left", 0, at[0]);
                float[] matrix = new float[9]; image.getImageMatrix().getValues(matrix);
                assertEquals("The picture itself is scaled to the full width", screen, image.getDrawable().getIntrinsicWidth() * matrix[Matrix.MSCALE_X], 1.5f);
                assertTrue("The viewer must keep the window secure", (viewer.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertTrue("There is no Done button", descendants(decor).stream().noneMatch(view -> view instanceof TextView label && "Done".contentEquals(label.getText())));
                List<View> controls = descendants(decor).stream().filter(view -> view instanceof Button || view instanceof ImageButton).toList();
                assertEquals("The close cross is the only control", 1, controls.size());
                View close = controls.get(0);
                assertEquals("Close photo", close.getContentDescription().toString());
                int[] closeAt = new int[2]; close.getLocationOnScreen(closeAt);
                assertTrue("The cross is at the right edge", closeAt[0] + close.getWidth() > screen - dpPx(24) && closeAt[0] + close.getWidth() <= screen);
                assertTrue("The cross is at the top", closeAt[1] >= 0 && closeAt[1] < dpPx(100));
                assertTrue("The cross is a comfortable touch target", close.getWidth() >= dpPx(44) && close.getHeight() >= dpPx(44));
                assertFalse("Ordinary accounts have no save gesture", image.isLongClickable());
                assertFalse(image.performLongClick());
                assertNoPopup(activity, "No save popup for an ordinary account");
                assertFalse(viewer.canSave()); assertNull(readField(viewer, "savable"));
            });
            viewerSnapshot(scenario, viewer, "60-photo-viewer");
            scenario.onActivity(activity -> assertTrue(photoClose(viewer).performClick()));
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
            scenario.onActivity(activity -> {
                assertFalse("The cross closes the photo", viewer.isShowing());
                assertNull("The picture is released", viewer.image().getDrawable());
                assertTrue(((Bitmap) readField(viewer, "bitmap")).isRecycled());
                assertNoPopup(activity, "No popup is open");
                assertNotNull("The chat is still there", readField(activity, "messageScroll"));
            });
            PhotoViewer once = openPhoto(scenario, viewOnce, true);
            assertNull("A view-once photo is consumed when it is opened", vault.get("body/" + viewOnce.id()));
            scenario.onActivity(activity -> {
                assertFalse("An ordinary account keeps no copy of a view-once photo", once.canSave());
                assertFalse(once.image().performLongClick());
                assertNoPopup(activity, "No popup is open");
                assertTrue(photoClose(once).performClick());
            });
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
        }
        if (Build.VERSION.SDK_INT >= 29) assertTrue("Nothing may be written to the gallery", galleryCopies().isEmpty());
    }

    @Test public void onlyTheAdminCanSaveAPhotoByLongPressingItEvenWhenItWasViewOnce() throws Exception {
        Assume.assumeTrue("Saving to the gallery needs Android 10 or later", Build.VERSION.SDK_INT >= 29);
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        conversationsFixture();
        assertTrue(engine.onboarding().admin());
        assertTrue("The pinned device and identity match this phone", engine.onboarding().verifiedAdmin());
        ChatEngine.Entry regular = entry(false, null, photo, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -30_000);
        ChatEngine.Entry viewOnce = entry(false, null, photo, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -20_000);
        ChatEngine.Entry sent = engine.entries(peerId).stream().filter(entry -> entry.outgoing() && entry.image()).findFirst().orElseThrow();
        deleteGalleryCopies();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            PhotoViewer viewer = openPhoto(scenario, regular, false);
            byte[][] held = new byte[1][];
            scenario.onActivity(activity -> {
                assertTrue(viewer.canSave());
                held[0] = (byte[]) readField(viewer, "savable"); assertNotNull(held[0]);
                assertTrue("A long press anywhere on the photo is handled", viewer.image().isLongClickable());
                assertTrue(viewer.image().performLongClick());
                SecureSheet confirm = dialog(activity);
                assertNotNull("The long press opens the save popup", confirm);
                View popup = confirm.getWindow().getDecorView();
                assertNotNull(text(popup, "Save photo")); assertNotNull(text(popup, "Cancel")); assertNotNull(text(popup, "Save"));
                assertTrue(viewer.isShowing());
                confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Cancel must not save anything", galleryCopies().isEmpty());
            scenario.onActivity(activity -> {
                assertTrue("The photo stays open behind the popup", viewer.isShowing());
                assertTrue(viewer.image().performLongClick());
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitGalleryCopies(1);
            Uri saved = galleryCopies().get(0);
            assertArrayEquals("The saved file is the original photo, not a re-encoded copy", photo, readGalleryCopy(saved));
            try (android.database.Cursor cursor = context.getContentResolver().query(saved, new String[]{android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    android.provider.MediaStore.MediaColumns.IS_PENDING, android.provider.MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("image/jpeg", cursor.getString(0)); assertEquals("The file is finished, not pending", 0, cursor.getInt(1));
                assertTrue(cursor.getString(2).startsWith("Pictures/Vanishr"));
            }
            scenario.onActivity(activity -> assertTrue(photoClose(viewer).performClick()));
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
            for (byte value : held[0]) assertEquals("The kept bytes are wiped when the photo closes", 0, value);
            assertNull(readField(viewer, "savable"));

            PhotoViewer once = openPhoto(scenario, viewOnce, true);
            assertNull("A view-once photo is consumed when it is opened", vault.get("body/" + viewOnce.id()));
            scenario.onActivity(activity -> {
                assertTrue("The admin can save a view-once photo", once.canSave());
                assertTrue(once.image().performLongClick());
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitGalleryCopies(2);
            for (Uri copy : galleryCopies()) assertArrayEquals(photo, readGalleryCopy(copy));
            scenario.onActivity(activity -> assertTrue(photoClose(once).performClick()));
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);

            PhotoViewer outgoing = openPhoto(scenario, sent, false);
            scenario.onActivity(activity -> {
                assertTrue("A photo the admin sent can be saved as well", outgoing.canSave());
                assertTrue(outgoing.image().performLongClick());
                assertNotNull(dialog(activity)); dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            byte[][] backgrounded = new byte[1][];
            scenario.onActivity(activity -> backgrounded[0] = (byte[]) readField(outgoing, "savable"));
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> {
                assertFalse("Leaving the app closes the photo", outgoing.isShowing());
                assertNull(readField(activity, "photoViewer"));
            });
            for (byte value : backgrounded[0]) assertEquals("Leaving the app wipes the kept bytes", 0, value);
            assertEquals("Only the two confirmed saves exist", 2, galleryCopies().size());
        } finally { deleteGalleryCopies(); }
    }

    @Test public void aGalleryCopyKeepsTheFormatAndAnUnsupportedFormatIsRefused() throws Exception {
        Assume.assumeTrue("Saving to the gallery needs Android 10 or later", Build.VERSION.SDK_INT >= 29);
        deleteGalleryCopies();
        try {
            Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(40, 120, 95));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)); bitmap.recycle();
            Uri jpegCopy = SafeImages.saveToGallery(context, photo);
            Uri pngCopy = SafeImages.saveToGallery(context, png.toByteArray());
            assertArrayEquals(photo, readGalleryCopy(jpegCopy)); assertArrayEquals(png.toByteArray(), readGalleryCopy(pngCopy));
            try (android.database.Cursor cursor = context.getContentResolver().query(pngCopy, new String[]{android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("image/png", cursor.getString(0)); assertTrue(cursor.getString(1).endsWith(".png"));
            }
            assertEquals(2, galleryCopies().size());
            assertThrows("Bytes that are not a supported image are refused", IOException.class,
                    () -> SafeImages.saveToGallery(context, "GIF89a-not-a-supported-photo".getBytes(StandardCharsets.US_ASCII)));
            assertThrows(IOException.class, () -> SafeImages.saveToGallery(context, new byte[0]));
            assertEquals("A refused photo leaves nothing behind", 2, galleryCopies().size());
        } finally { deleteGalleryCopies(); }
    }

    @Test public void anOpenPhotoClosesWhenItsMessageExpires() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Entry shortLived = entry(false, null, photo, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -3_594_000);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            PhotoViewer viewer = openPhoto(scenario, shortLived, false);
            scenario.onActivity(activity -> assertTrue("The photo opened before it expired", viewer.isShowing()));
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
            scenario.onActivity(activity -> {
                assertFalse("The photo closes at its deadline", viewer.isShowing());
                assertTrue(((Bitmap) readField(viewer, "bitmap")).isRecycled());
            });
        }
    }

    @Test public void anAccountThatOnlyCarriesTheAdminsUserIdCannotSavePhotos() throws Exception {
        signedInFixture(); conversationsFixture();
        SignalClient own = new SignalClient(userId, vault);
        SignalClient someoneElse = new SignalClient(userId, new DeviceSecurityTest.MemoryVault());
        ChatEngine.Entry regular = entry(false, null, photo, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -30_000);
        ChatEngine.Entry viewOnce = entry(false, null, photo, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -20_000);
        // The relay chooses the user id at sign-in, so an id that matches the pin proves nothing about this phone's identity.
        AdminOnboarding.Pin[] forged = {adminPin(userId, deviceId, someoneElse), adminPin(userId, UUID.randomUUID(), own)};
        if (Build.VERSION.SDK_INT >= 29) deleteGalleryCopies();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            for (AdminOnboarding.Pin pin : forged) {
                setField(engine.onboarding(), "pin", pin);
                assertTrue("The signed-in user id matches the pinned admin", engine.onboarding().admin());
                assertFalse("The device or identity key does not", engine.onboarding().verifiedAdmin());
                PhotoViewer viewer = openPhoto(scenario, regular, false);
                scenario.onActivity(activity -> {
                    assertFalse("No save option without the pinned identity", viewer.canSave());
                    assertNull(readField(viewer, "savable"));
                    assertFalse(viewer.image().isLongClickable()); assertFalse(viewer.image().performLongClick());
                    assertNoPopup(activity, "No save popup");
                    assertTrue(photoClose(viewer).performClick());
                });
                awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
            }
            setField(engine.onboarding(), "pin", adminPin(userId, deviceId, own));
            assertTrue(engine.onboarding().verifiedAdmin());
            PhotoViewer genuine = openPhoto(scenario, viewOnce, true);
            scenario.onActivity(activity -> {
                assertTrue("The same account with the pinned identity can save", genuine.canSave());
                assertTrue(photoClose(genuine).performClick());
            });
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
        }
        if (Build.VERSION.SDK_INT >= 29) assertTrue("Nothing may be written to the gallery", galleryCopies().isEmpty());
    }

    @Test public void aRealPressAndHoldAnywhereOnTheAdminsPhotoOpensTheSavePopupAndTheCrossClosesIt() throws Exception {
        SignalClient own = new SignalClient(userId, vault);
        signedInFixture(adminPin(userId, deviceId, own));
        conversationsFixture();
        ChatEngine.Entry regular = entry(false, null, photo, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -30_000);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            PhotoViewer viewer = openPhoto(scenario, regular, false);
            int[] at = new int[2]; int[] size = new int[2];
            scenario.onActivity(activity -> { viewer.image().getLocationOnScreen(at); size[0] = viewer.image().getWidth(); size[1] = viewer.image().getHeight(); });
            for (float[] spot : new float[][]{{0.5f, 0.5f}, {0.15f, 0.3f}, {0.85f, 0.85f}}) {
                int x = at[0] + Math.round(size[0] * spot[0]), y = at[1] + Math.round(size[1] * spot[1]);
                // A swipe that never leaves its start point is a press and hold of about a second, longer than the 400 ms long-press timeout.
                assertTrue(device.swipe(x, y, x, y, 200));
                awaitMainCondition(scenario, activity -> dialog(activity) != null && dialog(activity).isShowing());
                scenario.onActivity(activity -> {
                    assertNotNull("Holding the photo opens the save popup", text(dialog(activity).getWindow().getDecorView(), "Save photo"));
                    assertTrue("The photo stays open behind the popup", viewer.isShowing());
                    dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                });
                awaitMainCondition(scenario, activity -> !dialog(activity).isShowing());
            }
            int[] cross = new int[2]; View[] close = new View[1];
            scenario.onActivity(activity -> { close[0] = photoClose(viewer); close[0].getLocationOnScreen(cross); });
            assertTrue(device.click(cross[0] + close[0].getWidth() / 2, cross[1] + close[0].getHeight() / 2));
            awaitMainCondition(scenario, activity -> readField(activity, "photoViewer") == null);
            scenario.onActivity(activity -> assertFalse("Tapping the cross closes the photo", viewer.isShowing()));

            setField(engine.onboarding(), "pin", AdminOnboarding.OFFICIAL);
            PhotoViewer ordinary = openPhoto(scenario, regular, false);
            scenario.onActivity(activity -> assertFalse(ordinary.canSave()));
            int x = at[0] + size[0] / 2, y = at[1] + size[1] / 2;
            assertTrue(device.swipe(x, y, x, y, 200));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNoPopup(activity, "Holding the photo of an ordinary account does nothing");
                assertTrue(ordinary.isShowing());
                assertTrue(photoClose(ordinary).performClick());
            });
        }
    }

    @Test public void theCloseCrossFollowsTheReadingDirectionAndTheSideInsets() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PhotoViewer[] holder = new PhotoViewer[1];
            scenario.onActivity(activity -> {
                try { holder[0] = new PhotoViewer(activity, SafeImages.display(photo), null, ignored -> { }); }
                catch (Exception failure) { throw new AssertionError(failure); }
                holder[0].show();
            });
            PhotoViewer viewer = holder[0];
            awaitMainCondition(scenario, activity -> viewer.isShowing() && viewer.getWindow().getDecorView().getWidth() > 0);
            for (boolean rtl : new boolean[]{false, true}) {
                scenario.onActivity(activity -> {
                    View decor = viewer.getWindow().getDecorView();
                    decor.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                    ViewGroup content = decor.findViewById(android.R.id.content);
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), new androidx.core.view.WindowInsetsCompat.Builder()
                            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(dpPx(60), dpPx(24), dpPx(20), 0)).build());
                    decor.measure(View.MeasureSpec.makeMeasureSpec(decor.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(decor.getHeight(), View.MeasureSpec.EXACTLY));
                    decor.layout(decor.getLeft(), decor.getTop(), decor.getRight(), decor.getBottom());
                    View close = photoClose(viewer);
                    int width = ((View) close.getParent()).getWidth();
                    if (rtl) assertEquals("In a right-to-left language the cross sits at the start edge, clear of the left inset", dpPx(60) + dpPx(8), close.getLeft(), 1);
                    else assertEquals("The cross sits at the right edge, clear of the right inset", width - dpPx(20) - dpPx(8), close.getRight(), 1);
                    assertEquals("The cross is clear of the status bar", dpPx(24) + dpPx(8), close.getTop(), 1);
                });
            }
            scenario.onActivity(activity -> viewer.dismiss());
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

    private void bulkEntry(UUID peerUser, UUID peerDev, boolean outgoing, String text, long offset, String state) throws Exception {
        UUID id = UUID.randomUUID(); long created = System.currentTimeMillis() + offset; long deadline = created + ChatEnvelope.Expiry.HOUR_1.milliseconds;
        createFixtureKey(AndroidVault.contentAlias(deadline, id));
        ChatEnvelope envelope = new ChatEnvelope(1, id, outgoing ? userId : peerUser, outgoing ? deviceId : peerDev,
                outgoing ? peerUser : userId, outgoing ? peerDev : deviceId, created, deadline, ChatEnvelope.Expiry.HOUR_1, text, null);
        ChatEngine.Entry entry = new ChatEngine.Entry(id, peerUser, deadline, ChatEnvelope.Expiry.HOUR_1, outgoing, false, state);
        vault.transaction(() -> {
            write("entry/" + id, entry);
            byte[] content = RelayApi.JSON.toJson(new ChatEngine.Content(envelope, null)).getBytes(StandardCharsets.UTF_8);
            try { vault.put("body/" + id, vault.seal(AndroidVault.contentAlias(deadline, id), content)); }
            finally { Arrays.fill(content, (byte) 0); }
            return null;
        });
    }

    private List<ChatEngine.Peer> manyVerifiedContacts(int count, int entriesEach) throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        List<ChatEngine.Peer> peers = new ArrayList<>();
        vault.transaction(() -> {
            SignalClient own = new SignalClient(userId, vault);
            for (int index = 0; index < count; index++) {
                UUID id = new UUID(7, index + 1);
                ChatEngine.Peer contact = new ChatEngine.Peer(id, UUID.randomUUID(), Base64.getEncoder().encodeToString(remote.publicIdentity()),
                        String.format(Locale.ROOT, "Contact %03d", index));
                own.verifyPeer(id, remote.publicIdentity());
                write("contact/" + id, contact);
                peers.add(contact);
            }
            return null;
        });
        for (ChatEngine.Peer contact : peers)
            for (int index = 0; index < entriesEach; index++)
                bulkEntry(contact.userId(), contact.deviceId(), index % 3 == 0, "Synthetic message " + index, -(index * 1000L), index % 3 == 0 ? "DELIVERED" : "DELIVERED");
        return peers;
    }

    private static final class StallMeter implements Runnable {
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private volatile long last = android.os.SystemClock.elapsedRealtime(); volatile long worst; private volatile boolean running = true;
        void start() { last = android.os.SystemClock.elapsedRealtime(); handler.post(this); }
        @Override public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            worst = Math.max(worst, now - last - 10); last = now;
            if (running) handler.postDelayed(this, 10);
        }
        void stop() { running = false; handler.removeCallbacks(this); }
    }

    private static void perf(String label, long millis) {
        android.util.Log.w("PERF", label + " = " + millis + " ms");
        android.os.Bundle metrics = new android.os.Bundle(); metrics.putLong(label.replace(' ', '_'), millis);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
    }

    private View contactRow(MainActivity activity, String name) {
        TextView title = (TextView) text(root(activity), name);
        return (View) title.getParent().getParent();
    }

    @Test public void startupWithManyContactsShowsTheListAndOpensAChatPromptly() throws Exception {
        signedInFixture(); conversationsFixture();
        manyVerifiedContacts(40, 3);
        var meter = new StallMeter();
        long launchStart = android.os.SystemClock.elapsedRealtime();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> meter.start());
            awaitMainCondition(scenario, activity -> readField(activity, "contactRows") != null && ((LinearLayout) readField(activity, "contactRows")).getChildCount() > 10);
            long listed = android.os.SystemClock.elapsedRealtime() - launchStart;
            perf("launch to chat list visible with 43 contacts and 120+ messages (ms)", listed);
            perf("worst main thread stall while launching (ms)", meter.worst);
            // Before the fix the list had not appeared after 15 s on the same emulator.
            assertTrue("The chat list must appear without per-contact message scans; took " + listed + "ms", listed < 6_000);
            meter.worst = 0;
            long[] tap = new long[1];
            scenario.onActivity(activity -> {
                View row = contactRow(activity, "Contact 020");
                long started = android.os.SystemClock.elapsedRealtime();
                row.performClick();
                tap[0] = android.os.SystemClock.elapsedRealtime() - started;
                assertNotNull(readField(activity, "conversationName"));
            });
            perf("verified contact tap handler on the main thread (ms)", tap[0]);
            perf("worst main thread stall after the tap (ms)", meter.worst);
            assertTrue("Tapping a chat must open it at once; took " + tap[0] + "ms", tap[0] < 1_000);
            awaitMessages(scenario);
            scenario.onActivity(activity -> assertEquals(3, ((Map<?, ?>) readField(activity, "messageRows")).size()));
            scenario.onActivity(activity -> { invoke(activity, "hideContent"); });
            meter.stop();
        }
    }

    // ==== 0.6.5: changed identities, three expiry choices, swipes and a tidier composer ====

    private void assertOfficialAdminHeader(MainActivity activity) {
        TextView name = (TextView) readField(activity, "conversationName");
        assertEquals("The shield after the name says what the row under the chat used to",
            "Vanishr, official admin", String.valueOf(name.getContentDescription()));
        assertTrue("The row that carried the words is gone", descendants(root(activity)).stream()
            .noneMatch(view -> view instanceof TextView label && "Official admin".contentEquals(label.getText())));
    }

    private boolean hasText(View root, String value) {
        return descendants(root).stream().anyMatch(view -> view instanceof TextView label && label.getText().toString().equals(value));
    }

    /** The admin's own phone, with one contact saved under the identity it first had and a relay that can introduce a new one for it. */
    private final class AdminWithAReinstalledContact {
        final SignalClient before = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        final SignalClient after = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        final UUID newcomerId = UUID.randomUUID();
        final SignalClient newcomer = new SignalClient(newcomerId, new DeviceSecurityTest.MemoryVault());
        final AdminOnboarding.Introduction saved = introduction(peerId, peerDevice, before, "reinstaller");
        final AdminOnboarding.Introduction changed = introduction(peerId, UUID.randomUUID(), after, "reinstaller");
        final AdminOnboarding.Introduction fresh = introduction(newcomerId, UUID.randomUUID(), newcomer, "brand_new");
        final ChatEngine.Contact official;
        final java.util.concurrent.atomic.AtomicReference<AdminOnboarding.Page> directory;
        final List<ChatEngine.Incoming> incoming = new ArrayList<>();

        AdminWithAReinstalledContact() throws Exception {
            SignalClient own = new SignalClient(userId, vault);
            signedInFixture(adminPin(userId, deviceId, own));
            official = new ChatEngine.Contact(userId, deviceId, Base64.getEncoder().encodeToString(own.publicIdentity()));
            directory = new java.util.concurrent.atomic.AtomicReference<>(new AdminOnboarding.Page(userId, official, List.of(saved), null));
            adminTransport(directory, incoming, new ArrayList<>(), null);
            engine.onboarding().refresh();
        }

        /** The relay now introduces the new identity, and a brand-new account on the same page, as after a reinstall. */
        void reinstall() throws Exception {
            directory.set(new AdminOnboarding.Page(userId, official, List.of(changed, fresh), null));
            engine.onboarding().first();
        }

        ChatEngine.Incoming fromTheNewDevice() {
            long now = System.currentTimeMillis();
            return new ChatEngine.Incoming(UUID.randomUUID(), peerId, changed.deviceId(), userId, deviceId, ChatEnvelope.Expiry.HOUR_1,
                now, now + 3_600_000, 3, new byte[64], null);
        }
    }

    @Test public void aContactWhoseIdentityChangedIsKeptForReviewAndNeitherStopsTheOtherContactsNorShowsADirectoryWideError() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        assertEquals(scene.saved.peer(), engine.peer(peerId));
        // This used to throw, roll the whole page back (the other account too) and leave a red line on the list.
        scene.reinstall();
        assertNull("No directory-wide error for one contact", engine.onboarding().problem());
        assertTrue("The other account on the same page was still saved", engine.peers().contains(scene.fresh.peer()));
        assertEquals("The saved identity is untouched", scene.saved.peer(), engine.peer(peerId));
        assertEquals(scene.changed.peer(), engine.onboarding().changed(peerId));
        assertEquals(Set.of(peerId), engine.onboarding().changedContacts());
        assertNull(engine.onboarding().changed(scene.newcomerId));
        // Nothing about the new identity is trusted: the Signal store still pins the old one.
        assertThrows(SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, scene.after.publicIdentity()));
        engine.groupSignal().verifyPeer(peerId, scene.before.publicIdentity());
        // The same answer again changes nothing and raises nothing.
        long generation = engine.onboarding().generation();
        engine.onboarding().first();
        assertEquals(generation, engine.onboarding().generation());
        assertEquals(Set.of(peerId), engine.onboarding().changedContacts());
    }

    @Test public void acceptingAChangedIdentityNeedsTheOneThatWasShownAndIsStillIntroducedAndReplacesTheSavedOneOnlyThen() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        scene.reinstall();
        String shown = scene.changed.peer().identityKey();
        // Not what was shown, nothing shown, or nothing waiting for review: refused, and nothing changes.
        assertThrows(AdminOnboarding.Failure.class, () -> engine.onboarding().acceptChanged(peerId, scene.saved.peer().identityKey()));
        assertThrows(AdminOnboarding.Failure.class, () -> engine.onboarding().acceptChanged(peerId, null));
        assertThrows(SecurityException.class, () -> engine.onboarding().acceptChanged(scene.newcomerId, scene.fresh.peer().identityKey()));
        assertEquals(scene.saved.peer(), engine.peer(peerId));
        // The relay introduces yet another identity between the review and the tap: nothing is replaced, and the newer one is the one to review.
        SignalClient third = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        var newest = introduction(peerId, UUID.randomUUID(), third, "reinstaller");
        scene.directory.set(new AdminOnboarding.Page(userId, scene.official, List.of(newest), null));
        assertThrows(AdminOnboarding.Failure.class, () -> engine.onboarding().acceptChanged(peerId, shown));
        assertEquals(scene.saved.peer(), engine.peer(peerId));
        assertEquals(newest.peer(), engine.onboarding().changed(peerId));
        // The one that was shown and is still introduced is accepted.
        engine.onboarding().acceptChanged(peerId, newest.peer().identityKey());
        assertEquals(newest.peer(), engine.peer(peerId));
        assertNull(engine.onboarding().changed(peerId)); assertTrue(engine.onboarding().changedContacts().isEmpty());
        engine.groupSignal().verifyPeer(peerId, third.publicIdentity());
        assertThrows(SecurityException.class, () -> engine.groupSignal().verifyPeer(peerId, scene.before.publicIdentity()));
        assertFalse("Accepting is not verifying: the contact is no longer one this person verified", engine.independentlyVerified(peerId));
        assertTrue(engine.onboarding().automatic(peerId));
    }

    @Test public void aMessageFromANewDeviceOfASavedContactKeepsItsNewIdentityForReviewOnceAndStaysAtTheRelay() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        // An older contact is not on the first page, so the page does not find the change; a look-up for that contact does.
        scene.directory.set(new AdminOnboarding.Page(userId, scene.official, List.of(scene.changed), null));
        var lookups = countLookups(peerId);
        deletedMessages.clear();
        for (int pass = 0; pass < 3; pass++) { scene.incoming.add(scene.fromTheNewDevice()); engine.sync(); }
        assertEquals("One look-up, then the recorded change is enough", 1, lookups.get());
        assertEquals(scene.changed.peer(), engine.onboarding().changed(peerId));
        assertTrue(engine.unverifiedIncoming);
        assertTrue("The messages are neither read nor deleted at the relay", deletedMessages.isEmpty() && engine.entries(peerId).isEmpty());
        assertEquals(scene.saved.peer(), engine.peer(peerId));
        assertNull(engine.onboarding().problem());
    }

    @Test public void aDeviceTheRelayDoesNotIntroduceForTheContactIsNotKeptForReview() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        scene.directory.set(new AdminOnboarding.Page(userId, scene.official, List.of(scene.saved), null));
        var lookups = countLookups(peerId);
        for (int pass = 0; pass < 3; pass++) { scene.incoming.add(scene.fromTheNewDevice()); engine.sync(); }
        assertNull("A sender the relay does not stand behind is not offered for review", engine.onboarding().changed(peerId));
        assertTrue(engine.unverifiedIncoming);
        assertEquals(scene.saved.peer(), engine.peer(peerId));
        assertEquals("Such a sender is asked about once a minute, not on every sync", 1, lookups.get());
    }

    @Test public void aDeviceOtherThanTheOneTheRelayIntroducesIsNotKeptForReview() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        // The relay stands behind some other new device of this contact, not the one that wrote.
        var elsewhere = introduction(peerId, UUID.randomUUID(), new SignalClient(peerId, new DeviceSecurityTest.MemoryVault()), "reinstaller");
        scene.directory.set(new AdminOnboarding.Page(userId, scene.official, List.of(elsewhere), null));
        scene.incoming.add(scene.fromTheNewDevice());
        engine.sync();
        assertNull("The writing device is not the introduced one, so nothing is offered", engine.onboarding().changed(peerId));
        assertTrue(engine.unverifiedIncoming);
        assertEquals(scene.saved.peer(), engine.peer(peerId));
    }

    /** Counts the look-ups of one contact that the engine asks the relay for. */
    private java.util.concurrent.atomic.AtomicInteger countLookups(UUID contact) {
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        var builder = ((okhttp3.OkHttpClient) readField(engine.groupApi(), "client")).newBuilder();
        builder.interceptors().add(0, chain -> {
            if (chain.request().url().encodedPath().equals("/account/admin-contacts/" + contact)) lookups.incrementAndGet();
            return chain.proceed(chain.request());
        });
        setField(engine.groupApi(), "client", builder.build());
        return lookups;
    }

    @Test public void removingAContactThatIsWaitingForReviewClearsTheReviewAndTheContactStaysRemoved() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        scene.reinstall();
        assertNotNull(engine.onboarding().changed(peerId));
        engine.forget(scene.saved.peer());
        assertNull(engine.onboarding().changed(peerId)); assertTrue(engine.onboarding().changedContacts().isEmpty());
        scene.directory.set(new AdminOnboarding.Page(userId, scene.official, List.of(scene.changed, scene.fresh), null));
        engine.onboarding().first();
        assertNull("A removed contact is not offered for review again", engine.onboarding().changed(peerId));
        assertTrue(engine.peers().stream().noneMatch(peer -> peer.userId().equals(peerId)));
        assertTrue("The other account is still introduced", engine.peers().contains(scene.fresh.peer()));
    }

    @Test public void aReviewIsDroppedWhenTheRelayIntroducesTheSavedIdentityAgain() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        scene.reinstall();
        assertEquals(scene.changed.peer(), engine.onboarding().changed(peerId));
        scene.directory.set(new AdminOnboarding.Page(userId, scene.official, List.of(scene.saved, scene.fresh), null));
        long generation = engine.onboarding().generation();
        engine.onboarding().first();
        assertNull("Nothing is left to review once the relay stands behind the saved identity again", engine.onboarding().changed(peerId));
        assertTrue(engine.onboarding().changedContacts().isEmpty());
        assertTrue("The list is redrawn without the mark", engine.onboarding().generation() > generation);
        assertEquals(scene.saved.peer(), engine.peer(peerId));
    }

    @Test public void aPhoneThatIsNotTheAdminHasNoIdentityReviewsAndCannotAcceptOne() throws Exception {
        signedInFixture();
        assertNull(engine.onboarding().changed(peerId)); assertTrue(engine.onboarding().changedContacts().isEmpty());
        assertThrows(SecurityException.class, () -> engine.onboarding().acceptChanged(peerId, "A".repeat(44)));
        vault.transaction(() -> { write("admin-changed/" + peerId, new ChatEngine.Peer(peerId, peerDevice, "A".repeat(44), "x")); return null; });
        assertNull("A record written by hand is not honoured on such a phone", engine.onboarding().changed(peerId));
        assertTrue(engine.onboarding().changedContacts().isEmpty());
        assertThrows("Nor can it be accepted there", SecurityException.class, () -> engine.onboarding().acceptChanged(peerId, "A".repeat(44)));
    }

    private UUID deliverText(SignalClient remote, List<ChatEngine.Incoming> incoming, String text, ChatEnvelope.Expiry wire, long lifetime) throws Exception {
        UUID id = UUID.randomUUID(); long now = System.currentTimeMillis(), deadline = now + lifetime;
        ChatEnvelope envelope = new ChatEnvelope(1, id, peerId, peerDevice, userId, deviceId, now, deadline, wire, text, null);
        SignalClient.Packet packet = remote.encrypt(userId, RelayApi.JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8), Instant.now());
        incoming.add(new ChatEngine.Incoming(id, peerId, peerDevice, userId, deviceId, wire, now, deadline, packet.type(), packet.ciphertext(), null));
        return id;
    }

    @Test public void aTwelveHourMessageIsSentAsA24HourOneWithATwelveHourDeadlineAndKeepsItsChoiceOnThisPhone() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        UUID id = UUID.randomUUID(); long created = System.currentTimeMillis(), twelve = ChatEnvelope.Expiry.HOURS_12.milliseconds;
        engine.send(peer, "Twelve hours", null, ChatEnvelope.Expiry.HOURS_12, null, id, created, () -> { });
        ChatEngine.Send sent = outgoing.get(0);
        assertEquals("The relay and every earlier app only know four values", ChatEnvelope.Expiry.HOURS_24, sent.expiry());
        assertEquals(created + twelve, sent.expiresAt());
        ChatEnvelope wire = decryptControl(remote, sent);
        assertEquals(ChatEnvelope.Expiry.HOURS_24, wire.expiry());
        assertEquals(created + twelve, wire.expiresAt());
        assertEquals("Twelve hours", wire.text());
        assertEquals("This phone remembers what was chosen", ChatEnvelope.Expiry.HOURS_12, engine.entry(id).expiry());
        assertEquals(created + twelve, engine.entry(id).expiresAt());
        // Every other choice goes as itself.
        for (ChatEnvelope.Expiry other : List.of(ChatEnvelope.Expiry.VIEW_ONCE, ChatEnvelope.Expiry.HOURS_24, ChatEnvelope.Expiry.HOURS_6, ChatEnvelope.Expiry.HOUR_1)) {
            int before = outgoing.size();
            engine.send(peer, "As itself", null, other, null, UUID.randomUUID(), System.currentTimeMillis(), () -> { });
            assertEquals(other, outgoing.get(before).expiry());
        }
    }

    @Test public void aMessageSentAsTwelveHoursIsShownAsTwelveHoursAndEveryOlderKindStillArrivesAsItself() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        List<ChatEngine.Incoming> incoming = new ArrayList<>(); List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(incoming, outgoing);
        long hour = 3_600_000L;
        Map<UUID, ChatEnvelope.Expiry> expected = new java.util.LinkedHashMap<>();
        expected.put(deliverText(remote, incoming, "twelve", ChatEnvelope.Expiry.HOURS_24, 12 * hour), ChatEnvelope.Expiry.HOURS_12);
        expected.put(deliverText(remote, incoming, "a day", ChatEnvelope.Expiry.HOURS_24, 24 * hour), ChatEnvelope.Expiry.HOURS_24);
        expected.put(deliverText(remote, incoming, "six", ChatEnvelope.Expiry.HOURS_6, 6 * hour), ChatEnvelope.Expiry.HOURS_6);
        expected.put(deliverText(remote, incoming, "one", ChatEnvelope.Expiry.HOUR_1, hour), ChatEnvelope.Expiry.HOUR_1);
        engine.sync();
        for (var item : expected.entrySet()) assertEquals(item.getValue(), engine.entry(item.getKey()).expiry());
    }

    @Test public void aGroupMessageSentAsTwelveHoursAlsoTravelsAsA24HourOneWithTheEarlierDeadline() throws Exception {
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
                try { return syntheticResponse(request,200,List.of(new GroupChat.Claimed(peerId,recipient.generatePreKey(Instant.now())))); }
                catch (Exception failure) { throw new IOException("Synthetic prekey fixture failed"); }
            }
            okio.Buffer buffer=new okio.Buffer(); request.body().writeTo(buffer); String body=buffer.readUtf8();
            if (path.endsWith("/controls")) { controls.addAll(RelayApi.JSON.fromJson(body,GroupChat.Controls.class).packets()); return syntheticResponse(request,200,Map.of()); }
            assertTrue(path.endsWith("/messages"));
            GroupChat.Send message=RelayApi.JSON.fromJson(body,GroupChat.Send.class); messages.add(message);
            return syntheticResponse(request,200,new GroupChat.Status(message.id(),message.expiresAt(),1,0,0,"QUEUED"));
        }));
        UUID messageId=UUID.randomUUID(); long created=System.currentTimeMillis(), twelve=ChatEnvelope.Expiry.HOURS_12.milliseconds;
        createFixtureKey(AndroidVault.contentAlias(created+twelve,messageId,userId));
        engine.groups().send(groupId,"Group, twelve hours",null,ChatEnvelope.Expiry.HOURS_12,messageId,created,() -> { });
        assertEquals(1,messages.size());
        assertEquals(ChatEnvelope.Expiry.HOURS_24,messages.get(0).expiry());
        assertEquals(created+twelve,messages.get(0).expiresAt());
        SignalGroup receiving=new SignalGroup(new DeviceSecurityTest.MemoryVault(),groupId,epoch,peerId);
        for (GroupChat.ControlSend packet : controls) {
            byte[] plaintext=recipient.decrypt(userId,new SignalClient.Packet(packet.type(),packet.ciphertext()));
            try {
                GroupChat.ControlBody body=RelayApi.JSON.fromJson(new String(plaintext,StandardCharsets.UTF_8),GroupChat.ControlBody.class);
                if (body.kind().equals("KEY")) receiving.accept(userId,body.distribution());
            } finally { Arrays.fill(plaintext,(byte)0); }
        }
        byte[] plaintext=receiving.decrypt(userId,messages.get(0).ciphertext());
        try {
            GroupEnvelope envelope=RelayApi.JSON.fromJson(new String(plaintext,StandardCharsets.UTF_8),GroupEnvelope.class);
            envelope.verify(groupId,epoch,2,messageId,userId,deviceId,ChatEnvelope.Expiry.HOURS_24,created+twelve,null,Instant.now());
            assertEquals(ChatEnvelope.Expiry.HOURS_24,envelope.content().expiry());
            assertEquals(ChatEnvelope.Expiry.HOURS_12,ChatEnvelope.Expiry.received(messages.get(0).expiry(),envelope.content().sentAt(),envelope.content().expiresAt()));
        } finally { Arrays.fill(plaintext,(byte)0); }
        assertEquals(ChatEnvelope.Expiry.HOURS_12,engine.entries(groupId).get(0).expiry());
    }

    private long touchStart;

    private void touch(int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_DOWN) touchStart = now;
        android.view.MotionEvent event = android.view.MotionEvent.obtain(touchStart, now, action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
        event.recycle();
    }

    /** Presses at ({@code fromX}, {@code fromY}), drags to ({@code toX}, {@code toY}) in small steps and leaves the finger down. */
    private void dragAndHold(int fromX, int fromY, int toX, int toY) {
        touch(android.view.MotionEvent.ACTION_DOWN, fromX, fromY);
        for (int step = 1; step <= 12; step++) {
            touch(android.view.MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / 12f, fromY + (toY - fromY) * step / 12f);
            android.os.SystemClock.sleep(16);
        }
    }

    private void dragAndHold(int fromX, int toX, int y) { dragAndHold(fromX, y, toX, y); }

    private void locateBubble(ActivityScenario<MainActivity> scenario, String message, int[] at, int[] size) {
        scenario.onActivity(activity -> {
            View bubble = (View) text(root(activity), message).getParent();
            bubble.getLocationOnScreen(at); size[0] = bubble.getWidth(); size[1] = bubble.getHeight();
        });
    }

    private void awaitDialog(ActivityScenario<MainActivity> scenario) throws Exception {
        awaitMainCondition(scenario, activity -> dialog(activity) != null && dialog(activity).isShowing());
    }

    private void awaitNoDialog(ActivityScenario<MainActivity> scenario) throws Exception {
        awaitMainCondition(scenario, activity -> dialog(activity) == null || !dialog(activity).isShowing());
    }

    @Test public void aContactWaitingForReviewIsMarkedOnTheListOpensItsReviewInsteadOfTheChatAndAcceptingMakesTheChatOpenable() throws Exception {
        var scene = new AdminWithAReinstalledContact();
        scene.reinstall();
        String number = ChatEngine.safetyNumber(peerId, scene.changed.peer().identityKey());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            snapshot(scenario, "93-list-identity-changed");
            scenario.onActivity(activity -> {
                assertTrue("The list says which contact needs a look", hasText(root(activity), "Identity changed. Tap to review"));
                assertFalse("There is no directory-wide error line", hasText(root(activity), "Introduced identity changed; the saved identity was not replaced"));
                contactRow(activity, "reinstaller").performClick();
            });
            awaitDialog(scenario);
            snapshot(scenario, "94-identity-review");
            scenario.onActivity(activity -> {
                assertNull("The chat did not open", readField(activity, "selectedPeer"));
                View sheet = dialog(activity).getWindow().getDecorView();
                assertTrue(hasText(sheet, "Identity changed"));
                assertTrue("The new safety number is shown", hasText(sheet, number));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            assertNotNull("Not now changes nothing", engine.onboarding().changed(peerId));
            assertEquals(scene.saved.peer(), engine.peer(peerId));
            scenario.onActivity(activity -> contactRow(activity, "reinstaller").performClick());
            awaitDialog(scenario);
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            awaitMainCondition(scenario, activity -> engine.onboarding().changed(peerId) == null && !hasText(root(activity), "Identity changed. Tap to review"));
            assertEquals(scene.changed.peer(), engine.peer(peerId));
            scenario.onActivity(activity -> contactRow(activity, "reinstaller").performClick());
            awaitMainCondition(scenario, activity -> peerId.equals(readField(activity, "selectedPeer")));
            scenario.onActivity(activity -> ((View) ((TextView) readField(activity, "conversationName")).getParent()).performClick());
            awaitDialog(scenario);
            snapshot(scenario, "95-profile-account-enrolled");
            scenario.onActivity(activity -> {
                View sheet = dialog(activity).getWindow().getDecorView();
                assertTrue(hasText(sheet, "Contact profile"));
                assertTrue("What the label under the chat used to say is in the profile", hasText(sheet, "Account enrolled, not verified by you"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
        }
    }

    private View holderOf(MainActivity activity, String message) {
        return (View) ((View) text(root(activity), message).getParent()).getParent();
    }

    private void locateRow(ActivityScenario<MainActivity> scenario, String message, int[] at, int[] size) {
        scenario.onActivity(activity -> {
            View row = (View) holderOf(activity, message).getParent();
            row.getLocationOnScreen(at); size[0] = row.getWidth(); size[1] = row.getHeight();
        });
    }

    private void clearReply(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            setField(activity, "pendingReplyTo", null); setField(activity, "pendingReplySnippet", null); invoke(activity, "updateReplyStrip");
        });
    }

    @Test public void swipingAnyMessageToTheRightStartsAReplyWhoeverWroteItAndNothingElseDoes() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            int[] at = new int[2], size = new int[2];
            locateBubble(scenario, "Coffee at 4?", at, size);
            int y = at[1] + size[1] / 2, x = at[0] + dpPx(24);
            // A short pull, a pull the wrong way and a mostly vertical drag do nothing.
            assertTrue(device.swipe(x, y, x + dpPx(24), y, 12));
            assertTrue(device.swipe(x + dpPx(120), y, x, y, 12));
            assertTrue(device.swipe(x, y, x + dpPx(50), y - dpPx(150), 12));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertNull(readField(activity, "pendingReplyTo")));
            // Held, a mostly vertical drag does not move the message either, so the chat's own scrolling is left alone.
            dragAndHold(x, y, x + dpPx(30), y - dpPx(120));
            scenario.onActivity(activity -> assertEquals("A vertical drag leaves the message where it is", 0f, holderOf(activity, "Coffee at 4?").getTranslationX(), 0.5f));
            touch(android.view.MotionEvent.ACTION_UP, x + dpPx(30), y - dpPx(120));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertNull(readField(activity, "pendingReplyTo")));
            // Held short of the mark, the message follows the finger, with the reply cue growing in the space it leaves; letting go does nothing.
            locateBubble(scenario, "Coffee at 4?", at, size);
            y = at[1] + size[1] / 2; x = at[0] + dpPx(24);
            dragAndHold(x, x + dpPx(40), y);
            scenario.onActivity(activity -> {
                float moved = holderOf(activity, "Coffee at 4?").getTranslationX();
                assertTrue("The message follows the finger: " + moved, moved > 0 && moved < dpPx(48));
            });
            snapshot(scenario, "91-swipe-reply-held");
            touch(android.view.MotionEvent.ACTION_UP, x + dpPx(40), y);
            awaitMainCondition(scenario, activity -> holderOf(activity, "Coffee at 4?").getTranslationX() == 0f);
            scenario.onActivity(activity -> assertNull("Letting go short of the mark does nothing", readField(activity, "pendingReplyTo")));
            // A real swipe starts the reply, and the message slides back.
            locateBubble(scenario, "Coffee at 4?", at, size);
            assertTrue(device.swipe(at[0] + dpPx(24), at[1] + size[1] / 2, at[0] + dpPx(24) + dpPx(150), at[1] + size[1] / 2, 20));
            awaitMainCondition(scenario, activity -> readField(activity, "pendingReplyTo") != null);
            scenario.onActivity(activity -> {
                assertEquals("Coffee at 4?", readField(activity, "pendingReplySnippet"));
                View strip = (View) readField(activity, "replyStrip");
                assertEquals(View.VISIBLE, strip.getVisibility());
                assertTrue(hasText(strip, "Coffee at 4?"));
                assertTrue("The strip names whose words they are", hasText(strip, "Aarav Mehta"));
            });
            awaitMainCondition(scenario, activity -> holderOf(activity, "Coffee at 4?").getTranslationX() == 0f);
            clearReply(scenario);

            // One of my own messages sits at the far side of the screen, so it swipes from anywhere on its row: from the bubble, and from
            // the empty space beside it.
            locateBubble(scenario, "Sounds good. Same place?", at, size);
            assertTrue(device.swipe(at[0] + dpPx(24), at[1] + size[1] / 2, at[0] + dpPx(24) + dpPx(150), at[1] + size[1] / 2, 20));
            awaitMainCondition(scenario, activity -> "Sounds good. Same place?".equals(readField(activity, "pendingReplySnippet")));
            scenario.onActivity(activity -> {
                assertTrue(((ChatEngine.Entry) readField(activity, "pendingReplyTo")).outgoing());
                assertTrue("The strip says it is mine", hasText((View) readField(activity, "replyStrip"), "You"));
            });
            awaitMainCondition(scenario, activity -> holderOf(activity, "Sounds good. Same place?").getTranslationX() == 0f);
            clearReply(scenario);
            int[] row = new int[2], rowSize = new int[2];
            locateRow(scenario, "Sounds good. Same place?", row, rowSize);
            int beside = row[0] + dpPx(20), middle = row[1] + rowSize[1] / 2;
            assertTrue("The space beside the bubble is empty", beside + dpPx(30) < at[0]);
            dragAndHold(beside, beside + dpPx(40), middle);
            scenario.onActivity(activity -> assertTrue("The message follows a finger that is beside it", holderOf(activity, "Sounds good. Same place?").getTranslationX() > dpPx(30)));
            snapshot(scenario, "91b-swipe-reply-own-held");
            touch(android.view.MotionEvent.ACTION_UP, beside + dpPx(40), middle);
            awaitMainCondition(scenario, activity -> holderOf(activity, "Sounds good. Same place?").getTranslationX() == 0f);
            scenario.onActivity(activity -> assertNull(readField(activity, "pendingReplyTo")));
            assertTrue(device.swipe(beside, middle, beside + dpPx(150), middle, 20));
            awaitMainCondition(scenario, activity -> "Sounds good. Same place?".equals(readField(activity, "pendingReplySnippet")));
            clearReply(scenario);
            // The same from the empty space beside a message that was received.
            locateRow(scenario, "Yes, see you there.", row, rowSize);
            locateBubble(scenario, "Yes, see you there.", at, size);
            int rightOf = at[0] + size[0] + dpPx(30);
            assertTrue(device.swipe(rightOf, row[1] + rowSize[1] / 2, rightOf + dpPx(150), row[1] + rowSize[1] / 2, 20));
            awaitMainCondition(scenario, activity -> "Yes, see you there.".equals(readField(activity, "pendingReplySnippet")));
            clearReply(scenario);

            // A photo (which is itself something to tap) replies too, and does not open. Its click is counted here, so that what is checked
            // does not depend on how long a photo takes to decrypt and open.
            java.util.concurrent.atomic.AtomicInteger taps = new java.util.concurrent.atomic.AtomicInteger();
            scenario.onActivity(activity -> {
                View photoBody = text(root(activity), "Photo");
                assertTrue("The photo is something to tap", photoBody.hasOnClickListeners());
                photoBody.setOnClickListener(view -> taps.incrementAndGet());
            });
            locateBubble(scenario, "Photo", at, size);
            assertTrue(device.swipe(at[0] + size[0] / 2, at[1] + size[1] / 2, at[0] + size[0] / 2 + dpPx(150), at[1] + size[1] / 2, 20));
            awaitMainCondition(scenario, activity -> "Photo".equals(readField(activity, "pendingReplySnippet")));
            assertEquals("Swiping a photo does not tap it", 0, taps.get());
            clearReply(scenario);
            // A sideways drag the other way across the photo does not tap it either, even a slow one. (It has to pass the touch slop before
            // the long-press time is up, or it is what it looks like, a press that is held.)
            locateBubble(scenario, "Photo", at, size);
            assertTrue(device.swipe(at[0] + size[0] * 3 / 4, at[1] + size[1] / 2, at[0] + size[0] / 4, at[1] + size[1] / 2, 40));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("A drag the wrong way across a photo does not tap it", 0, taps.get());
            scenario.onActivity(activity -> {
                assertNull("Nor does it end as a long press", overlay(activity));
                assertNull("Nor as a reply", readField(activity, "pendingReplyTo"));
            });
            // A plain tap on the photo still taps it, and the long press opens the actions, on the photo as well as on a text message.
            assertTrue(device.click(at[0] + size[0] / 2, at[1] + size[1] / 2));
            awaitMainCondition(scenario, activity -> taps.get() == 1);
            scenario.onActivity(activity -> assertNull("The tap was the counted one, not the photo's own", readField(activity, "photoViewer")));
            locateBubble(scenario, "Yes, see you there.", at, size);
            assertTrue(device.swipe(at[0] + dpPx(24), at[1] + size[1] / 2, at[0] + dpPx(24), at[1] + size[1] / 2, 200));
            awaitOverlay(scenario);
            scenario.onActivity(activity -> {
                View decor = overlay(activity).getWindow().getDecorView();
                assertTrue(hasText(decor, "Reply")); assertTrue(hasText(decor, "Delete"));
                overlay(activity).dismiss();
            });
            awaitNoOverlay(scenario);
        }
    }

    private MessageActions overlay(MainActivity activity) { return (MessageActions) readField(activity, "messageOverlay"); }

    private void awaitOverlay(ActivityScenario<MainActivity> scenario) throws Exception {
        awaitMainCondition(scenario, activity -> {
            MessageActions open = overlay(activity);
            return open != null && open.isShowing() && (boolean) readField(open, "placed");
        });
        // The pieces pop in over a fraction of a second; positions are measured once they have settled.
        android.os.SystemClock.sleep(700);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void awaitNoOverlay(ActivityScenario<MainActivity> scenario) throws Exception {
        awaitMainCondition(scenario, activity -> overlay(activity) == null || !overlay(activity).isShowing());
    }

    // ---- A chat in the list, pulled sideways: it shows its actions and stays open until one is chosen (0.6.8, rebuilt) ----

    private SwipeActions actionsOf(MainActivity activity, String name) { return (SwipeActions) contactRow(activity, name).getParent(); }
    private SwipeActions.Group rowGroup(MainActivity activity) { return (SwipeActions.Group) readField(activity, "rowSwipes"); }
    private float shiftOf(MainActivity activity, String name) { return contactRow(activity, name).getTranslationX(); }
    private boolean noDialog(MainActivity activity) { return dialog(activity) == null || !dialog(activity).isShowing(); }

    private TextView actionLabel(MainActivity activity, String name, String label) {
        return (TextView) descendants(actionsOf(activity, name)).stream()
            .filter(view -> view instanceof TextView text && label.contentEquals(text.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("No action " + label + " on " + name));
    }

    private boolean showing(MainActivity activity, String name, String label) { return actionLabel(activity, name, label).isShown(); }

    private Rect holderRect(ActivityScenario<MainActivity> scenario, String name) {
        Rect[] result = new Rect[1];
        scenario.onActivity(activity -> {
            View holder = actionsOf(activity, name); int[] at = new int[2]; holder.getLocationOnScreen(at);
            result[0] = new Rect(at[0], at[1], at[0] + holder.getWidth(), at[1] + holder.getHeight());
        });
        return result[0];
    }

    /** A quick pull of a chat's row, as a finger flicks it: to the right is positive. It starts clear of the screen's edge gestures. */
    private void pullRow(ActivityScenario<MainActivity> scenario, androidx.test.uiautomator.UiDevice device, String name, int dp) {
        Rect at = holderRect(scenario, name);
        int from = dp > 0 ? at.left + dpPx(60) : at.right - dpPx(60);
        assertTrue(device.swipe(from, at.centerY(), from + dpPx(dp), at.centerY(), 20));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Drags from {@code fromX} by {@code dp} and lets go once the finger has been still for a moment, so that only the distance counts. */
    private void dragRowAndStop(Rect at, int fromX, int dp) {
        int toX = fromX + dpPx(dp), y = at.centerY();
        dragAndHold(fromX, toX, y);
        android.os.SystemClock.sleep(250);
        touch(android.view.MotionEvent.ACTION_UP, toX, y);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void awaitShift(ActivityScenario<MainActivity> scenario, String name, int dp) throws Exception {
        float[] seen = new float[1];
        try { awaitMainCondition(scenario, activity -> { seen[0] = shiftOf(activity, name); return Math.abs(seen[0] - dpPx(dp)) <= 2f; }); }
        catch (AssertionError failure) { throw new AssertionError("The row of " + name + " is " + seen[0] + " px from rest, not " + dpPx(dp) + " (" + dp + " dp)", failure); }
    }

    private void tapRowAction(ActivityScenario<MainActivity> scenario, androidx.test.uiautomator.UiDevice device, String name, String label) {
        int[] at = new int[2];
        scenario.onActivity(activity -> {
            TextView text = actionLabel(activity, name, label);
            assertTrue(label + " is showing on " + name, text.isShown());
            text.getLocationOnScreen(at); at[0] += text.getWidth() / 2; at[1] += text.getHeight() / 2;
        });
        assertTrue(device.click(at[0], at[1]));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private int badgeColor(MainActivity activity, UUID id) {
        @SuppressWarnings("unchecked") Map<UUID, TextView> badges = (Map<UUID, TextView>) readField(activity, "unreadLabels");
        return ((android.graphics.drawable.GradientDrawable) badges.get(id).getBackground()).getColor().getDefaultColor();
    }

    private long pictures(MainActivity activity, String name) { return descendants(contactRow(activity, name)).stream().filter(ImageView.class::isInstance).count(); }

    @Test public void pullingAChatToTheRightShowsMuteAndClearChatAndLeavesThemThere() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals("At rest the row is where it always was", 0f, shiftOf(activity, "Aarav Mehta"), 0f);
                View row = contactRow(activity, "Aarav Mehta"); SwipeActions holder = actionsOf(activity, "Aarav Mehta");
                assertEquals("The actions behind a row do not make it taller", row.getHeight(), holder.getHeight());
                assertTrue("It is an ordinary chat row: " + row.getHeight(), row.getHeight() >= dpPx(78) && row.getHeight() < dpPx(120));
                for (View panel : List.of(holder.getChildAt(0), holder.getChildAt(1))) assertEquals("and the actions are as tall as it is", row.getHeight(), panel.getHeight());
                for (String label : List.of("Mute", "Clear chat", "Remove contact", "Block user")) assertFalse(label + " is not showing at rest", showing(activity, "Aarav Mehta", label));
            });
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            scenario.onActivity(activity -> {
                assertTrue(showing(activity, "Aarav Mehta", "Mute")); assertTrue(showing(activity, "Aarav Mehta", "Clear chat"));
                assertFalse("Not the other side's", showing(activity, "Aarav Mehta", "Remove contact") || showing(activity, "Aarav Mehta", "Block user"));
                assertTrue("Nothing is asked: " + shownText(activity), noDialog(activity));
                assertNull("The chat did not open", readField(activity, "selectedPeer"));
                assertEquals("The other chats did not move", 0f, shiftOf(activity, "Maya Chen"), 0f);
                assertSame("It is this row that is open", actionsOf(activity, "Aarav Mehta"), readField(rowGroup(activity), "open"));
            });
            snapshot(scenario, "112-swipe-right-actions");
            // Nothing puts it back by itself.
            android.os.SystemClock.sleep(1500);
            scenario.onActivity(activity -> assertEquals(dpPx(152), shiftOf(activity, "Aarav Mehta"), 2f));
        }
    }

    @Test public void pullingAChatToTheLeftShowsRemoveContactAndBlockUserAndLeavesThemThere() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            scenario.onActivity(activity -> {
                assertTrue(showing(activity, "Aarav Mehta", "Remove contact")); assertTrue(showing(activity, "Aarav Mehta", "Block user"));
                assertFalse("Not the other side's", showing(activity, "Aarav Mehta", "Mute") || showing(activity, "Aarav Mehta", "Clear chat"));
                assertTrue("Nothing is asked: " + shownText(activity), noDialog(activity));
                assertNull("The chat did not open", readField(activity, "selectedPeer"));
                assertEquals("The other chats did not move", 0f, shiftOf(activity, "Maya Chen"), 0f);
            });
            snapshot(scenario, "113-swipe-left-actions");
            android.os.SystemClock.sleep(1500);
            scenario.onActivity(activity -> assertEquals(-dpPx(152), shiftOf(activity, "Aarav Mehta"), 2f));
            // Pulled the other way it passes through rest and shows the other two.
            Rect at = holderRect(scenario, "Aarav Mehta");
            dragRowAndStop(at, at.left + dpPx(40), 280);
            awaitShift(scenario, "Aarav Mehta", 152);
            scenario.onActivity(activity -> {
                assertTrue(showing(activity, "Aarav Mehta", "Mute")); assertFalse(showing(activity, "Aarav Mehta", "Block user"));
            });
        }
    }

    @Test public void aShortOrSlowOrUpwardPullLeavesTheRowAtRestAndOnePastHalfwayStays() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect at = holderRect(scenario, "Aarav Mehta");
            // Let go before halfway of what the buttons need (76 of 152 dp): back it goes, to either side.
            dragRowAndStop(at, at.left + dpPx(60), 60);
            awaitShift(scenario, "Aarav Mehta", 0);
            dragRowAndStop(at, at.right - dpPx(60), -60);
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> {
                assertTrue(noDialog(activity)); assertNull(readField(activity, "selectedPeer"));
                assertFalse(showing(activity, "Aarav Mehta", "Mute")); assertFalse(showing(activity, "Aarav Mehta", "Block user"));
            });
            // A mostly vertical drag does not move the row, so the list can scroll.
            dragAndHold(at.left + dpPx(80), at.centerY(), at.left + dpPx(110), at.centerY() - dpPx(120));
            scenario.onActivity(activity -> assertEquals("A vertical drag leaves the row where it is", 0f, shiftOf(activity, "Aarav Mehta"), 0.5f));
            touch(android.view.MotionEvent.ACTION_UP, at.left + dpPx(110), at.centerY() - dpPx(120));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> { assertTrue(noDialog(activity)); assertNull(readField(activity, "selectedPeer")); });
            // Past halfway, let go slowly: it stays open.
            dragRowAndStop(at, at.left + dpPx(60), 100);
            awaitShift(scenario, "Aarav Mehta", 152);
            // From open, a short pull back leaves it open and one past halfway puts it back.
            dragRowAndStop(at, at.left + dpPx(300), -40);
            awaitShift(scenario, "Aarav Mehta", 152);
            dragRowAndStop(at, at.left + dpPx(300), -110);
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> { assertTrue(noDialog(activity)); assertNull(readField(activity, "selectedPeer")); });
        }
    }

    @Test public void theRowCoversTheActionsBehindItWhileItIsBeingPulled() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect at = holderRect(scenario, "Aarav Mehta");
            dragAndHold(at.left + dpPx(60), at.left + dpPx(60) + dpPx(50), at.centerY());
            scenario.onActivity(activity -> {
                SwipeActions holder = actionsOf(activity, "Aarav Mehta");
                float moved = shiftOf(activity, "Aarav Mehta");
                assertTrue("Held, the row follows the finger: " + moved, moved > dpPx(30) && moved < dpPx(152));
                assertTrue("and a finger is on it", rowGroup(activity).active());
                Bitmap image = Bitmap.createBitmap(holder.getWidth(), holder.getHeight(), Bitmap.Config.ARGB_8888);
                holder.draw(new Canvas(image));
                int inRow = image.getPixel(Math.round(moved) + dpPx(6), dpPx(4)), beside = image.getPixel(dpPx(10), dpPx(4));
                image.recycle();
                assertEquals("The row's own colour, so nothing behind it shows through", Color.red(Ui.CANVAS), Color.red(inRow), 8);
                assertEquals(Color.green(Ui.CANVAS), Color.green(inRow), 8); assertEquals(Color.blue(Ui.CANVAS), Color.blue(inRow), 8);
                assertEquals("The button beside it is the Clear chat button", Color.green(Ui.MUTED), Color.green(beside), 8);
                assertEquals(Color.red(Ui.MUTED), Color.red(beside), 8);
            });
            snapshot(scenario, "114-swipe-held");
            touch(android.view.MotionEvent.ACTION_UP, at.left + dpPx(60) + dpPx(50), at.centerY());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertFalse("Nobody is touching the list any more", rowGroup(activity).active()));
        }
    }

    @Test public void aTapOnAnOpenRowPutsItBackAndOpensNothingWhileATapOnARowAtRestOpensTheChat() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect at = holderRect(scenario, "Aarav Mehta");
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            assertTrue(device.click(at.right - dpPx(60), at.centerY()));
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> { assertNull("The tap put the row back and opened nothing", readField(activity, "selectedPeer")); assertTrue(noDialog(activity)); });
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            assertTrue(device.click(at.left + dpPx(60), at.centerY()));
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> { assertNull(readField(activity, "selectedPeer")); assertTrue(noDialog(activity)); });
            // A row at rest still opens its chat for a tap.
            assertTrue(device.click(at.centerX(), at.centerY()));
            awaitMainCondition(scenario, activity -> peerId.equals(readField(activity, "selectedPeer")));
        }
    }

    @Test public void touchingAnotherRowPutsTheOpenOneBackWithoutOpeningThatOneAndOnlyOneIsOpen() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            Rect maya = holderRect(scenario, "Maya Chen");
            assertTrue(device.click(maya.centerX(), maya.centerY()));
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> { assertNull("The touch that put it back did not open the other chat", readField(activity, "selectedPeer")); assertTrue(noDialog(activity)); });
            // A pull on another row does the same and moves nothing; the next pull opens it, and only it.
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            pullRow(scenario, device, "Maya Chen", -220);
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> assertEquals("The pull that put the open row back moved nothing else", 0f, shiftOf(activity, "Maya Chen"), 0.5f));
            pullRow(scenario, device, "Maya Chen", -220);
            awaitShift(scenario, "Maya Chen", -152);
            scenario.onActivity(activity -> {
                assertEquals(0f, shiftOf(activity, "Aarav Mehta"), 0.5f);
                assertSame(actionsOf(activity, "Maya Chen"), readField(rowGroup(activity), "open"));
            });
            snapshot(scenario, "115-swipe-second-row");
        }
    }

    @Test public void touchingTheEmptyPartOfTheListAndBackPutAnOpenRowBackAndASecondBackLeaves() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            Rect last = holderRect(scenario, "Noah Williams");
            assertTrue(device.click(last.centerX(), last.bottom + dpPx(150)));
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> assertNull(readField(activity, "selectedPeer")));
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> assertFalse("The first Back only put the row back", activity.isFinishing()));
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            awaitMainCondition(scenario, MainActivity::isFinishing);
        }
    }

    @Test public void scrollingTheListPutsAnOpenRowBack() throws Exception {
        signedInFixture(); conversationsFixture();
        manyVerifiedContacts(30, 1);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            awaitMainCondition(scenario, activity -> readField(activity, "contactRows") != null && ((LinearLayout) readField(activity, "contactRows")).getChildCount() > 10);
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            // The drag starts on the open row itself, so that no other row is touched and only the list's own scrolling can put it back.
            Rect at = holderRect(scenario, "Aarav Mehta");
            int x = at.right - dpPx(60);
            assertTrue(device.swipe(x, at.centerY(), x, at.centerY() + dpPx(300), 20));
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> { assertNull(readField(activity, "selectedPeer")); assertTrue(noDialog(activity)); });
        }
    }

    @Test public void mutingAChatKeepsItsCountGreyShowsABellAndCanBeUndone() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            long[] plainCount = new long[1];
            scenario.onActivity(activity -> plainCount[0] = pictures(activity, "Maya Chen"));
            long plain = plainCount[0];
            scenario.onActivity(activity -> {
                assertEquals("A chat that is not muted has the green count", Ui.PRIMARY, badgeColor(activity, peerId));
                assertEquals(plain, pictures(activity, "Aarav Mehta"));
                assertNull(androidx.core.view.ViewCompat.getStateDescription(contactRow(activity, "Aarav Mehta")));
            });
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            tapRowAction(scenario, device, "Aarav Mehta", "Mute");
            awaitMainCondition(scenario, activity -> engine.mutedChats().contains(peerId) && !(boolean) readField(activity, "busy"));
            scenario.onActivity(activity -> {
                assertEquals(Set.of(peerId), engine.mutedChats());
                assertEquals("The count is grey", Ui.MUTED, badgeColor(activity, peerId));
                assertEquals("and a bell is beside it", plain + 1, pictures(activity, "Aarav Mehta"));
                assertEquals("Muted", String.valueOf(androidx.core.view.ViewCompat.getStateDescription(contactRow(activity, "Aarav Mehta"))));
                assertEquals("The row is back at rest", 0f, shiftOf(activity, "Aarav Mehta"), 0.5f);
                assertEquals("No other chat is muted", plain, pictures(activity, "Maya Chen"));
                assertTrue("Muting does not touch the messages", engine.entries(peerId).size() == 7);
            });
            snapshot(scenario, "116-muted-chat");
            // Opened again, the button offers to undo it, and does.
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            scenario.onActivity(activity -> {
                assertTrue(showing(activity, "Aarav Mehta", "Unmute"));
                assertTrue("There is no second Mute button", descendants(actionsOf(activity, "Aarav Mehta")).stream().noneMatch(view -> view instanceof TextView label && "Mute".contentEquals(label.getText())));
            });
            snapshot(scenario, "117-unmute-offered");
            tapRowAction(scenario, device, "Aarav Mehta", "Unmute");
            awaitMainCondition(scenario, activity -> engine.mutedChats().isEmpty() && !(boolean) readField(activity, "busy"));
            scenario.onActivity(activity -> {
                assertEquals(Ui.PRIMARY, badgeColor(activity, peerId));
                assertEquals(plain, pictures(activity, "Aarav Mehta"));
                assertNull(androidx.core.view.ViewCompat.getStateDescription(contactRow(activity, "Aarav Mehta")));
            });
        }
    }

    @Test public void clearChatFromTheSwipeAsksFirstAndKeepsTheContact() throws Exception {
        signedInFixture(); conversationsFixture();
        int count = engine.entries(peerId).size();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            tapRowAction(scenario, device, "Aarav Mehta", "Clear chat");
            awaitDialog(scenario);
            scenario.onActivity(activity -> assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Clear chat?")));
            awaitShift(scenario, "Aarav Mehta", 0);
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            awaitNoDialog(scenario);
            assertEquals("Cancelling keeps every message", count, engine.entries(peerId).size());
            pullRow(scenario, device, "Aarav Mehta", 220);
            awaitShift(scenario, "Aarav Mehta", 152);
            tapRowAction(scenario, device, "Aarav Mehta", "Clear chat");
            awaitDialog(scenario);
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            awaitMainCondition(scenario, activity -> engine.entries(peerId).isEmpty() && !(boolean) readField(activity, "busy"));
            scenario.onActivity(activity -> {
                assertTrue("The contact stays", engine.peers().stream().anyMatch(value -> value.userId().equals(peerId)));
                assertTrue(hasText(root(activity), "Aarav Mehta"));
                assertNull(readField(activity, "selectedPeer"));
                @SuppressWarnings("unchecked") Map<UUID, TextView> badges = (Map<UUID, TextView>) readField(activity, "unreadLabels");
                assertEquals("There is nothing left to count", View.GONE, badges.get(peerId).getVisibility());
            });
        }
    }

    @Test public void removeContactFromTheSwipeAsksFirstAndTakesTheMuteWithIt() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.mute(peerId, true);
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            tapRowAction(scenario, device, "Aarav Mehta", "Remove contact");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                View sheet = dialog(activity).getWindow().getDecorView();
                assertTrue(hasText(sheet, "Remove contact?"));
                assertTrue(descendants(sheet).stream().anyMatch(view -> view instanceof TextView label && label.getText().toString().contains("Aarav Mehta")));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            awaitShift(scenario, "Aarav Mehta", 0);
            assertTrue("Cancelling keeps the contact and the mute", engine.peers().stream().anyMatch(value -> value.userId().equals(peerId)) && engine.mutedChats().contains(peerId));
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            tapRowAction(scenario, device, "Aarav Mehta", "Remove contact");
            awaitDialog(scenario);
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            awaitMainCondition(scenario, activity -> engine.peers().stream().noneMatch(value -> value.userId().equals(peerId)) && !(boolean) readField(activity, "busy"));
            scenario.onActivity(activity -> assertFalse(hasText(root(activity), "Aarav Mehta")));
            assertTrue("The mute went with the contact", engine.mutedChats().isEmpty());
            assertTrue(vault.names("muted/").isEmpty());
        }
    }

    @Test public void blockUserFromTheSwipeAsksFirstAndTakesTheMuteAndTheContactWithIt() throws Exception {
        signedInFixture(); conversationsFixture();
        engine.mute(peerId, true);
        plainTransport(new ArrayList<>(), new ArrayList<>());
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            tapRowAction(scenario, device, "Aarav Mehta", "Block user");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Block Aarav Mehta?"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            assertFalse("Cancelling blocks nobody", engine.safety().isBlocked(peerId));
            assertTrue(engine.mutedChats().contains(peerId));
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            tapRowAction(scenario, device, "Aarav Mehta", "Block user");
            awaitDialog(scenario);
            scenario.onActivity(activity -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            awaitMainCondition(scenario, activity -> engine.safety().isBlocked(peerId) && !(boolean) readField(activity, "busy"));
            scenario.onActivity(activity -> assertFalse(hasText(root(activity), "Aarav Mehta")));
            assertTrue("The mute went with the contact", engine.mutedChats().isEmpty());
        }
    }

    @Test public void anOpenRowComesBackOpenWhenTheListIsDrawnAgainAndIsForgottenWhenItsChatIsNotListed() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            pullRow(scenario, device, "Aarav Mehta", -220);
            awaitShift(scenario, "Aarav Mehta", -152);
            // A sync draws the list again; the row has to come back as it was, without a gesture.
            scenario.onActivity(activity -> {
                View before = contactRow(activity, "Aarav Mehta");
                invoke(activity, "renderContacts");
                View after = contactRow(activity, "Aarav Mehta");
                assertNotSame("The rows are new views", before, after);
                assertEquals(-dpPx(152), after.getTranslationX(), 2f);
                assertTrue(showing(activity, "Aarav Mehta", "Block user"));
                assertEquals("Only that one", 0f, shiftOf(activity, "Maya Chen"), 0f);
                assertSame(actionsOf(activity, "Aarav Mehta"), readField(rowGroup(activity), "open"));
            });
            // So does the filter for chats with something unread, which draws it again as well.
            scenario.onActivity(activity -> ((View) readField(activity, "unreadFilter")).performClick());
            scenario.onActivity(activity -> assertEquals(-dpPx(152), shiftOf(activity, "Aarav Mehta"), 2f));
            scenario.onActivity(activity -> ((View) readField(activity, "unreadFilter")).performClick());
            // A search that leaves the chat out forgets it: it is back at rest when it is listed again.
            scenario.onActivity(activity -> ((EditText) readField(activity, "contactSearch")).setText("Maya"));
            scenario.onActivity(activity -> ((EditText) readField(activity, "contactSearch")).setText(""));
            scenario.onActivity(activity -> assertEquals(0f, shiftOf(activity, "Aarav Mehta"), 0.5f));
            snapshot(scenario, "118-list-after-search");
        }
    }

    @Test public void theFourActionsAreOfferedToAScreenReaderAsActionsOnTheRowAndAsButtons() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                for (String label : List.of("Mute", "Clear chat", "Remove contact", "Block user")) {
                    View button = descendants(actionsOf(activity, "Aarav Mehta")).stream().filter(view -> label.equals(String.valueOf(view.getContentDescription()))).findFirst()
                        .orElseThrow(() -> new AssertionError("No button described as " + label));
                    assertTrue(label + " is a button", button.isClickable() && button.isFocusable());
                }
                View row = contactRow(activity, "Aarav Mehta");
                Map<String, Integer> actions = new HashMap<>();
                for (var action : row.createAccessibilityNodeInfo().getActionList()) if (action.getLabel() != null) actions.put(action.getLabel().toString(), action.getId());
                assertTrue("The row offers them: " + actions.keySet(), actions.keySet().containsAll(List.of("Mute chat", "Clear chat", "Remove contact", "Block user")));
                assertTrue(row.performAccessibilityAction(actions.get("Clear chat"), null));
            });
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Clear chat?"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            scenario.onActivity(activity -> {
                View row = contactRow(activity, "Aarav Mehta");
                Map<String, Integer> actions = new HashMap<>();
                for (var action : row.createAccessibilityNodeInfo().getActionList()) if (action.getLabel() != null) actions.put(action.getLabel().toString(), action.getId());
                assertTrue(row.performAccessibilityAction(actions.get("Remove contact"), null));
            });
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Remove contact?"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            assertTrue(engine.peers().stream().anyMatch(value -> value.userId().equals(peerId)));
        }
    }

    @Test public void aMuteBelongsToAContactStaysOnThisPhoneAndGoesWithTheContact() throws Exception {
        signedInFixture(); conversationsFixture();
        assertTrue(engine.mutedChats().isEmpty());
        assertThrows("Only a contact can be muted", SecurityException.class, () -> engine.mute(UUID.randomUUID(), true));
        engine.mute(peerId, true);
        engine.mute(peerId, true);
        assertEquals("Muting twice is the same as once", Set.of(peerId), engine.mutedChats());
        assertEquals("Kept in the encrypted store beside the contact", List.of("muted/" + peerId), vault.names("muted/"));
        vault.transaction(() -> { vault.put("muted/not-a-chat", new byte[]{1}); return null; });
        assertEquals("A stray name is not a chat", Set.of(peerId), engine.mutedChats());
        engine.mute(peerId, false);
        assertTrue(engine.mutedChats().isEmpty());
        engine.mute(peerId, true);
        engine.forget(peer);
        assertTrue("Removing the contact removes the mute", engine.mutedChats().isEmpty());
        assertEquals(List.of("muted/not-a-chat"), vault.names("muted/"));
    }

    private void syncPass(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            var worker = (java.util.concurrent.ExecutorService) readField(activity, "work");
            try { worker.submit(() -> invoke(activity, "syncOnce")).get(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test public void aSyncDoesNotDrawTheListAgainUnderAFingerAndTheListCatchesUpWhenItIsLifted() throws Exception {
        signedInFixture(); conversationsFixture(); notificationTransport(reference -> null);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::inject);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect at = holderRect(scenario, "Aarav Mehta");
            dragAndHold(at.left + dpPx(60), at.left + dpPx(60) + dpPx(40), at.centerY());
            View[] before = new View[1];
            scenario.onActivity(activity -> { before[0] = contactRow(activity, "Aarav Mehta"); assertTrue(rowGroup(activity).active()); });
            vault.transaction(() -> { write("contact/" + UUID.randomUUID(), new ChatEngine.Peer(UUID.randomUUID(), UUID.randomUUID(), peer.identityKey(), "Zed Newcomer")); return null; });
            syncPass(scenario);
            scenario.onActivity(activity -> {
                assertSame("The row under the finger was not replaced", before[0], contactRow(activity, "Aarav Mehta"));
                assertFalse("and the list has not caught up yet", hasText(root(activity), "Zed Newcomer"));
            });
            touch(android.view.MotionEvent.ACTION_UP, at.left + dpPx(60) + dpPx(40), at.centerY());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            syncPass(scenario);
            scenario.onActivity(activity -> assertTrue("With the finger lifted the next pass draws it", hasText(root(activity), "Zed Newcomer")));
        }
    }

    @Test public void tappingTheNameOrThePictureInTheChatHeaderOpensTheContactProfile() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                TextView name = (TextView) readField(activity, "conversationName");
                assertEquals("A contact this person verified is marked beside the name", "Aarav Mehta, verified", String.valueOf(name.getContentDescription()));
                ((View) name.getParent()).performClick();
            });
            awaitDialog(scenario);
            snapshot(scenario, "97-profile-verified");
            scenario.onActivity(activity -> {
                View sheet = dialog(activity).getWindow().getDecorView();
                assertTrue(hasText(sheet, "Contact profile"));
                assertTrue(hasText(sheet, "Verified by you"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            // The picture does the same.
            scenario.onActivity(activity -> {
                TextView name = (TextView) readField(activity, "conversationName");
                ((ViewGroup) ((View) name.getParent()).getParent()).getChildAt(1).performClick();
            });
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Contact profile"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            // The menu route is still there.
            scenario.onActivity(activity -> assertTrue(descendants(root(activity)).stream().anyMatch(view -> "Conversation options".equals(view.getContentDescription()))));
        }
    }

    @Test public void theComposerKeepsOnlyTheExpiryChoiceInsideTheMessageFieldAndOffersThreeChoices() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            snapshot(scenario, "90-composer-expiry-chip");
            scenario.onActivity(activity -> {
                View composer = (View) readField(activity, "composer");
                TextView chip = (TextView) readField(activity, "expiryLabel");
                assertSame("The expiry choice sits in the same frame as the message field", composer.getParent(), chip.getParent());
                assertEquals("12h", chip.getText().toString());
                assertEquals("Message expiry: 12 hours", String.valueOf(chip.getContentDescription()));
                assertEquals(ChatEnvelope.Expiry.HOURS_12, readField(activity, "expiry"));
                for (String gone : List.of("Account enrolled", "Verified", "Official admin", "Private group"))
                    assertTrue(gone + " is no longer a row under the chat", descendants(root(activity)).stream().noneMatch(view -> view instanceof TextView label && gone.contentEquals(label.getText())));
                View area = (View) ((View) composer.getParent()).getParent().getParent();
                assertTrue("The area under the chat is the field and its padding only: " + area.getHeight(), area.getHeight() <= dpPx(85));
                chip.performClick();
            });
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                SecureSheet choices = dialog(activity);
                assertEquals(3, choices.getListView().getCount());
                assertTrue(hasText(choices.getWindow().getDecorView(), "View once"));
                assertTrue(hasText(choices.getWindow().getDecorView(), "12 hours"));
                assertTrue(hasText(choices.getWindow().getDecorView(), "24 hours"));
                assertFalse(hasText(choices.getWindow().getDecorView(), "1 hour"));
                assertFalse(hasText(choices.getWindow().getDecorView(), "6 hours"));
                choices.getListView().performItemClick(choices.getListView().getChildAt(0), 0, 0);
            });
            awaitNoDialog(scenario);
            scenario.onActivity(activity -> {
                TextView chip = (TextView) readField(activity, "expiryLabel");
                assertEquals(ChatEnvelope.Expiry.VIEW_ONCE, readField(activity, "expiry"));
                assertEquals("Once", chip.getText().toString());
                assertEquals("Message expiry: View once", String.valueOf(chip.getContentDescription()));
                invoke(activity, "expiryDialog");
            });
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                SecureSheet choices = dialog(activity);
                choices.getListView().performItemClick(choices.getListView().getChildAt(2), 2, 2);
            });
            awaitNoDialog(scenario);
            scenario.onActivity(activity -> {
                assertEquals(ChatEnvelope.Expiry.HOURS_24, readField(activity, "expiry"));
                assertEquals("24h", ((TextView) readField(activity, "expiryLabel")).getText().toString());
            });
        }
    }

    @Test public void aTwelveHourMessageIsLabelledTwelveHoursWithItsRealSendingTime() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Entry twelve = entry(true, "Twelve hour message", null, ChatEnvelope.Expiry.HOURS_12, "DELIVERED", -45_000);
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(
            java.time.Instant.ofEpochMilli(twelve.expiresAt() - ChatEnvelope.Expiry.HOURS_12.milliseconds).atZone(java.time.ZoneId.systemDefault()));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> assertTrue("The label shows twelve hours and the time it was really sent",
                hasText(root(activity), time + "  /  12 hours")));
        }
    }

    @Test public void aMessageHeldPartWayShowsTheReplyArrowBehindItAndNothingIsDrawnThereAtRest() throws Exception {
        signedInFixture(); conversationsFixture();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> assertEquals("Nothing is drawn behind a message at rest", 0, markedPixelsBehind(activity, "Coffee at 4?")));
            int[] at = new int[2], size = new int[2];
            locateBubble(scenario, "Coffee at 4?", at, size);
            int y = at[1] + size[1] / 2, x = at[0] + dpPx(24);
            dragAndHold(x, x + dpPx(40), y);
            int[] held = new int[1];
            scenario.onActivity(activity -> held[0] = markedPixelsBehind(activity, "Coffee at 4?"));
            assertTrue("The reply arrow shows behind a message held part of the way: " + held[0], held[0] > 40);
            snapshot(scenario, "98-reply-arrow-held");
            touch(android.view.MotionEvent.ACTION_UP, x + dpPx(40), y);
            awaitMainCondition(scenario, activity -> markedPixelsBehind(activity, "Coffee at 4?") == 0);
        }
    }

    /** How many pixels are drawn in the space the message has moved away from, where the reply arrow shows. */
    private int markedPixelsBehind(MainActivity activity, String message) {
        View bubble = holderOf(activity, message);
        ViewGroup line = (ViewGroup) bubble.getParent();
        Bitmap image = Bitmap.createBitmap(line.getWidth(), line.getHeight(), Bitmap.Config.ARGB_8888);
        line.draw(new Canvas(image));
        int shown = 0, edge = Math.max(0, Math.round(bubble.getLeft() + bubble.getTranslationX()));
        for (int px = 0; px < edge; px++) for (int py = 0; py < image.getHeight(); py++) if (Color.alpha(image.getPixel(px, py)) != 0) shown++;
        image.recycle();
        return shown;
    }

    // ==== 0.6.8: message actions, quoted replies, reactions on the message ====

    private Rect screenRect(View view) {
        int[] at = new int[2]; view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    private static boolean isReactionButton(View view) {
        CharSequence description = view.getContentDescription();
        return description != null && (description.toString().startsWith("React ") || description.toString().startsWith("Remove reaction "));
    }

    /** The overlay's pieces on the screen: the reactions (null where there are none), the copy of the message, and the card of actions. */
    private Rect[] overlayPieces(MainActivity activity, String message) {
        View decor = overlay(activity).getWindow().getDecorView();
        View column = (View) text(decor, message).getParent();
        View stage = (View) ((View) column.getParent()).getParent();
        View bar = descendants(decor).stream().filter(ScreenFlowTest::isReactionButton).findFirst().map(button -> (View) button.getParent()).orElse(null);
        View card = descendants(decor).stream().filter(view -> view instanceof ScrollView).findFirst().orElseThrow();
        return new Rect[]{bar == null ? null : screenRect(bar), screenRect(stage), screenRect(card)};
    }

    private List<String> menuLabels(MainActivity activity) {
        View card = descendants(overlay(activity).getWindow().getDecorView()).stream().filter(view -> view instanceof ScrollView).findFirst().orElseThrow();
        List<String> labels = new ArrayList<>();
        for (View view : descendants(card)) if (view instanceof TextView label && label.getText().length() > 0) labels.add(label.getText().toString());
        return labels;
    }

    private void longPress(ActivityScenario<MainActivity> scenario, androidx.test.uiautomator.UiDevice device, String message) throws Exception {
        int[] at = new int[2], size = new int[2];
        locateBubble(scenario, message, at, size);
        int x = at[0] + size[0] / 2, y = at[1] + size[1] / 2;
        assertTrue(device.swipe(x, y, x, y, 200));
        awaitOverlay(scenario);
    }

    // What a screen reader does: the long press as an action, with no finger on the screen.
    private void openActions(ActivityScenario<MainActivity> scenario, String message) throws Exception {
        scenario.onActivity(activity -> assertTrue(((View) holderOf(activity, message).getParent()).performLongClick()));
        awaitOverlay(scenario);
    }

    private void tapAction(ActivityScenario<MainActivity> scenario, String label) throws Exception {
        scenario.onActivity(activity -> ((View) text(overlay(activity).getWindow().getDecorView(), label).getParent()).performClick());
        awaitNoOverlay(scenario);
    }

    private void closeActions(ActivityScenario<MainActivity> scenario) throws Exception {
        scenario.onActivity(activity -> overlay(activity).dismiss());
        awaitNoOverlay(scenario);
    }

    private void overlaySnapshot(ActivityScenario<MainActivity> scenario, String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            MessageActions open = overlay(activity);
            assertTrue("The actions are hidden from screenshots like every window that shows a message",
                (open.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            View under = activity.getWindow().getDecorView(), over = open.getWindow().getDecorView();
            Bitmap image = Bitmap.createBitmap(under.getWidth(), under.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            under.draw(canvas);
            over.draw(canvas);
            File folder = new File(context.getExternalFilesDir(null), "ui-qa"); assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            catch (IOException failure) { throw new AssertionError(failure); }
            finally { image.recycle(); }
            for (View child : descendants(over)) {
                if (!(child instanceof TextView label) || !child.isShown() || label.getLayout() == null) continue;
                int available = label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight();
                for (int line = 0; line < label.getLineCount(); line++)
                    assertTrue("Text overflows in " + name, label.getLayout().getLineMax(line) <= available + 4 || label.getLayout().getEllipsisCount(line) > 0);
            }
        });
    }

    @Test public void longPressingAMessageOpensReactionsAboveItAndTheMenuBelowItAndOffersOnlyWhatSuitsTheMessage() throws Exception {
        signedInFixture(); conversationsFixture();
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        var metrics = context.getResources().getDisplayMetrics();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            Object[][] cases = {
                {"Coffee at 4?", false, List.of("Reply", "Forward", "Copy", "Info", "Delete", "More\u2026"), "100-actions-received-text"},
                {"Sounds good. Same place?", true, List.of("Reply", "Forward", "Copy", "Edit", "Info", "Delete"), "101-actions-own-text"},
                {"Photo", true, List.of("Reply", "Forward", "Info", "Delete"), "102-actions-photo"},
                {"View once", false, List.of("Reply", "Info", "Delete", "More\u2026"), "103-actions-view-once"},
                {"A longer message should wrap naturally on a narrow display without covering the timestamp or the send controls.", true,
                    List.of("Reply", "Forward", "Copy", "Edit", "Info", "Delete"), "104-actions-at-the-bottom"},
            };
            for (Object[] each : cases) {
                String message = (String) each[0];
                boolean mine = (boolean) each[1];
                @SuppressWarnings("unchecked") List<String> offered = (List<String>) each[2];
                Rect[] original = new Rect[1];
                scenario.onActivity(activity -> original[0] = screenRect(holderOf(activity, message)));
                longPress(scenario, device, message);
                scenario.onActivity(activity -> {
                    assertEquals("What is offered for " + message, offered, menuLabels(activity));
                    Rect[] pieces = overlayPieces(activity, message);
                    Rect bar = pieces[0], stage = pieces[1], card = pieces[2];
                    assertNotNull("The reactions float above the message", bar);
                    assertEquals("The copy is as wide as the message", original[0].width(), stage.width());
                    assertEquals("and as tall", original[0].height(), stage.height());
                    assertTrue("The reactions sit above the message: " + bar + " " + stage, bar.bottom <= stage.top + 1);
                    assertTrue("The menu sits below the message: " + card + " " + stage, card.top >= stage.bottom - 1);
                    assertTrue("The reactions are a piece of their own, apart from the menu", bar.bottom < card.top);
                    // Each piece is lined up with the side the message is on.
                    if (mine) { assertEquals(stage.right, bar.right, 1); assertEquals(stage.right, card.right, 1); }
                    else { assertEquals(stage.left, bar.left, 1); assertEquals(stage.left, card.left, 1); }
                    for (Rect piece : pieces) {
                        assertTrue("On screen: " + piece, piece.left >= 0 && piece.top >= 0 && piece.right <= metrics.widthPixels && piece.bottom <= metrics.heightPixels);
                    }
                    assertEquals("Six reactions are offered", ChatEnvelope.REACTION_CHOICES.size(), descendants(overlay(activity).getWindow().getDecorView()).stream().filter(ScreenFlowTest::isReactionButton).count());
                    assertEquals("The message itself is hidden where it was while its copy is shown", 0f, holderOf(activity, message).getAlpha(), 0f);
                });
                overlaySnapshot(scenario, (String) each[3]);
                closeActions(scenario);
                scenario.onActivity(activity -> {
                    assertEquals("The message is back", 1f, holderOf(activity, message).getAlpha(), 0f);
                    assertEquals("and has not moved", original[0], screenRect(holderOf(activity, message)));
                });
            }
        }
    }

    @Test public void theActionsOfAMessageDoWhatTheySayAndDeleteForEveryoneIsOnlyForMyOwnLiveMessages() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry theirs = entry(false, "Coffee at 4?", null, ChatEnvelope.Expiry.HOUR_1, "READ", -240_000);
        ChatEngine.Entry mine = entry(true, "Sounds good. Same place?", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -210_000);
        ChatEngine.Entry second = entry(false, "Another one to remove", null, ChatEnvelope.Expiry.HOUR_1, "READ", -180_000);
        plainTransport(new ArrayList<>(), new ArrayList<>());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            // Reply starts a reply and closes the menu.
            openActions(scenario, "Coffee at 4?");
            tapAction(scenario, "Reply");
            scenario.onActivity(activity -> {
                assertEquals(theirs.id(), ((ChatEngine.Entry) readField(activity, "pendingReplyTo")).id());
                assertEquals(1f, holderOf(activity, "Coffee at 4?").getAlpha(), 0f);
            });
            // Copy puts the text on the clipboard, marked sensitive where Android knows how.
            openActions(scenario, "Sounds good. Same place?");
            tapAction(scenario, "Copy");
            // Android lets only the app in front read the clipboard, so wait for the window to be back in front.
            awaitMainCondition(scenario, activity -> activity.hasWindowFocus());
            scenario.onActivity(activity -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = clipboard.getPrimaryClip();
                assertNotNull(clip);
                assertEquals("Sounds good. Same place?", clip.getItemAt(0).getText().toString());
                if (Build.VERSION.SDK_INT >= 33) assertTrue(clip.getDescription().getExtras().getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE));
            });
            // Info says when it was sent, how it stands, and when it goes.
            openActions(scenario, "Sounds good. Same place?");
            tapAction(scenario, "Info");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                View sheet = dialog(activity).getWindow().getDecorView();
                for (String line : List.of("Message info", "Sent", "Status", "Delivered", "Disappears")) assertTrue(line, hasText(sheet, line));
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitNoDialog(scenario);
            // Edit is only for my own text; it opens the editor.
            openActions(scenario, "Sounds good. Same place?");
            tapAction(scenario, "Edit");
            awaitDialog(scenario);
            scenario.onActivity(activity -> { assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Edit message")); dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); });
            awaitNoDialog(scenario);
            // More holds the report, for a message that came from somebody else.
            openActions(scenario, "Coffee at 4?");
            tapAction(scenario, "More\u2026");
            awaitDialog(scenario);
            scenario.onActivity(activity -> { assertTrue(hasText(dialog(activity).getWindow().getDecorView(), "Report")); dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); });
            awaitNoDialog(scenario);
            // Delete: for everyone only on my own live message, and always for me.
            openActions(scenario, "Sounds good. Same place?");
            tapAction(scenario, "Delete");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                View sheet = dialog(activity).getWindow().getDecorView();
                assertTrue(hasText(sheet, "Delete message?")); assertTrue(hasText(sheet, "Delete for everyone")); assertTrue(hasText(sheet, "Delete for me"));
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            awaitNoDialog(scenario);
            assertTrue("Cancelling deletes nothing", engine.hasEntry(mine.id()));
            openActions(scenario, "Another one to remove");
            tapAction(scenario, "Delete");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                View sheet = dialog(activity).getWindow().getDecorView();
                assertFalse("Somebody else's message cannot be deleted for everyone", hasText(sheet, "Delete for everyone"));
                text(sheet, "Delete for me").performClick();
            });
            awaitMainCondition(scenario, activity -> !engine.hasEntry(second.id()) && !hasText(root(activity), "Another one to remove"));
            assertTrue("Only that message went", engine.hasEntry(theirs.id()) && engine.hasEntry(mine.id()));
        }
    }

    @Test public void aReactionPickedFromTheOverlayHangsOffTheMessageAsAPillAndTheSameOneTakesItBack() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry theirs = entry(false, "Coffee at 4?", null, ChatEnvelope.Expiry.HOUR_1, "READ", -240_000);
        ChatEngine.Entry mine = entry(true, "Sounds good. Same place?", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -210_000);
        ChatEngine.Entry both = entry(true, "We both reacted", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -180_000);
        ChatEngine.Entry same = entry(false, "The same one twice", null, ChatEnvelope.Expiry.HOUR_1, "READ", -150_000);
        String thumbs = ChatEnvelope.REACTION_CHOICES.get(0), heart = ChatEnvelope.REACTION_CHOICES.get(1);
        vault.transaction(() -> {
            write("entry/" + both.id(), both.withReaction(true, thumbs).withReaction(false, heart));
            write("entry/" + same.id(), same.withReaction(true, thumbs).withReaction(false, thumbs));
            return null;
        });
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(new ArrayList<>(), outgoing);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> {
                assertEquals("Nothing hangs off a message without reactions", 0, chips(activity, "Coffee at 4?"));
                // Two reactions: both emoji and how many; the same emoji twice: one emoji and the count.
                View both2 = chip(activity, "We both reacted"), same2 = chip(activity, "The same one twice");
                assertTrue(hasText(both2, thumbs + heart)); assertTrue(hasText(both2, "2"));
                assertTrue(hasText(same2, thumbs)); assertFalse(hasText(same2, thumbs + thumbs)); assertTrue(hasText(same2, "2"));
            });
            // Pick a reaction on a received message.
            openActions(scenario, "Coffee at 4?");
            scenario.onActivity(activity -> {
                assertTrue("Nothing is picked yet", descendants(overlay(activity).getWindow().getDecorView()).stream()
                    .noneMatch(view -> view.getContentDescription() != null && view.getContentDescription().toString().startsWith("Remove reaction ")));
                descendants(overlay(activity).getWindow().getDecorView()).stream().filter(view -> thumbs.equals(text(view))).findFirst().orElseThrow().performClick();
            });
            awaitNoOverlay(scenario);
            awaitMainCondition(scenario, activity -> thumbs.equals(engine.entry(theirs.id()).reactionMine()) && chips(activity, "Coffee at 4?") == 1);
            assertEquals("One control message went out", 1, outgoing.size());
            scenario.onActivity(activity -> {
                Rect bubble = screenRect((View) text(root(activity), "Coffee at 4?").getParent()), pill = screenRect(chip(activity, "Coffee at 4?"));
                assertTrue("The pill is half on the bubble and half below it: " + pill + " " + bubble, pill.top < bubble.bottom && pill.bottom > bubble.bottom);
                assertEquals("on the right-hand side of a received message, where the corner faces the middle: " + pill + " " + bubble, bubble.right - dpPx(10), pill.right, 2);
                assertTrue(hasText(chip(activity, "Coffee at 4?"), thumbs));
                assertFalse("One reaction has no count", hasText(chip(activity, "Coffee at 4?"), "1"));
            });
            snapshot(scenario, "105-reaction-pill-received");
            // The one that was given is shown as picked in the overlay, and tapping it takes it back.
            openActions(scenario, "Coffee at 4?");
            scenario.onActivity(activity -> {
                View picked = descendants(overlay(activity).getWindow().getDecorView()).stream().filter(view -> view.getContentDescription() != null
                    && view.getContentDescription().toString().equals("Remove reaction " + thumbs)).findFirst().orElseThrow();
                picked.performClick();
            });
            awaitNoOverlay(scenario);
            awaitMainCondition(scenario, activity -> engine.entry(theirs.id()).reactionMine() == null && chips(activity, "Coffee at 4?") == 0);
            assertEquals("and the removal went out too", 2, outgoing.size());
            // On a message of mine the pill sits on the other side.
            openActions(scenario, "Sounds good. Same place?");
            scenario.onActivity(activity -> descendants(overlay(activity).getWindow().getDecorView()).stream().filter(view -> heart.equals(text(view))).findFirst().orElseThrow().performClick());
            awaitNoOverlay(scenario);
            awaitMainCondition(scenario, activity -> heart.equals(engine.entry(mine.id()).reactionMine()) && chips(activity, "Sounds good. Same place?") == 1);
            scenario.onActivity(activity -> {
                Rect bubble = screenRect((View) text(root(activity), "Sounds good. Same place?").getParent()), pill = screenRect(chip(activity, "Sounds good. Same place?"));
                assertTrue("The pill is half on the bubble and half below it", pill.top < bubble.bottom && pill.bottom > bubble.bottom);
                assertEquals("on the left-hand side of my own message, where the corner faces the middle: " + pill + " " + bubble, bubble.left + dpPx(10), pill.left, 2);
            });
            snapshot(scenario, "106-reaction-pill-own");
            // Tapping the pill opens the same actions.
            scenario.onActivity(activity -> chip(activity, "Sounds good. Same place?").performClick());
            awaitOverlay(scenario);
            closeActions(scenario);
        }
    }

    private static String text(View view) { return view instanceof TextView label ? label.getText().toString() : null; }

    private int chips(MainActivity activity, String message) {
        LinearLayout holder = (LinearLayout) holderOf(activity, message);
        return holder.getChildCount() - 1;
    }

    private View chip(MainActivity activity, String message) {
        LinearLayout holder = (LinearLayout) holderOf(activity, message);
        assertEquals("A pill hangs off " + message, 2, holder.getChildCount());
        return holder.getChildAt(1);
    }

    @Test public void forwardingSendsANewMessageToEachConversationPickedAndNeverAViewOnceOne() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        entry(false, "Coffee at 4?", null, ChatEnvelope.Expiry.HOUR_1, "READ", -240_000);
        entry(false, "This message is visible once.", null, ChatEnvelope.Expiry.VIEW_ONCE, "DELIVERED", -90_000);
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(new ArrayList<>(), outgoing);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            openActions(scenario, "View once");
            scenario.onActivity(activity -> assertFalse("A view-once message is never forwarded", menuLabels(activity).contains("Forward")));
            closeActions(scenario);
            openActions(scenario, "Coffee at 4?");
            tapAction(scenario, "Forward");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                ListView list = sheet.getListView();
                assertTrue(hasText(sheet.getWindow().getDecorView(), "Forward to")); assertTrue(hasText(sheet.getWindow().getDecorView(), "Test peer"));
                assertFalse("Nothing is picked, so there is nothing to forward", sheet.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                assertTrue(sheet.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                assertFalse("Un-picking it again leaves nothing to forward", sheet.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitMainCondition(scenario, activity -> outgoing.size() == 1 && engine.entries(peerId).stream().filter(entry -> entry.outgoing()).count() == 1);
            ChatEnvelope sent = decryptControl(remote, outgoing.get(0));
            assertEquals("Coffee at 4?", sent.text());
            assertNull("A forwarded message is an ordinary message, not a reply", sent.reference());
            assertNull(sent.kind());
            assertEquals("It travels with the lifetime chosen for new messages", ChatEnvelope.Expiry.HOURS_24, sent.expiry());
            awaitMainCondition(scenario, activity -> descendants(root(activity)).stream().filter(view -> "Coffee at 4?".equals(text(view))).count() == 2);
        }
    }

    @Test public void forwardingAPhotoSendsItsBytesAnewLeavesTheOriginalAndNeverReferencesIt() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        ChatEngine.Entry original = entry(false, null, photo, ChatEnvelope.Expiry.HOUR_1, "READ", -60_000);
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(new ArrayList<>(), outgoing);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            openActions(scenario, "Photo");
            scenario.onActivity(activity -> assertTrue("A photo can be forwarded, and cannot be copied", menuLabels(activity).contains("Forward") && !menuLabels(activity).contains("Copy")));
            tapAction(scenario, "Forward");
            awaitDialog(scenario);
            scenario.onActivity(activity -> {
                SecureSheet sheet = dialog(activity);
                ListView list = sheet.getListView();
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitMainCondition(scenario, activity -> outgoing.size() == 1 && uploadedMedia.size() == 1);
            ChatEnvelope sent = decryptControl(remote, outgoing.get(0));
            assertNull(sent.text()); assertNull(sent.reference()); assertNull(sent.kind());
            assertNotNull(sent.image());
            assertArrayEquals("The photo that arrives is the photo that was forwarded", photo,
                ImageCipher.decrypt(sent.image().id(), uploadedMedia.get(0), sent.image().key(), sent.image().nonce()));
            assertNotEquals("It is a new photo, under its own id", original.id(), outgoing.get(0).id());
            assertTrue("The original stays where it was", engine.hasEntry(original.id()));
            assertEquals(2, engine.entries(peerId).size());
        }
    }

    @Test public void aGroupMessageOffersNoReactionsAndNoReplyAndASwipeOnItStartsNoReply() throws Exception {
        signedInFixture(); conversationsFixture();
        UUID groupId = UUID.randomUUID(), epoch = UUID.randomUUID(), messageId = UUID.randomUUID();
        GroupChat.Snapshot snapshot = new GroupChat.Snapshot(groupId, userId, 1, epoch, false, List.of(
            new GroupChat.Member(userId, deviceId, Base64.getEncoder().encodeToString(engine.groupSignal().publicIdentity()), "alex", null, "ACTIVE", 0),
            new GroupChat.Member(peerId, peerDevice, peer.identityKey(), "friend", null, "ACTIVE", 0)));
        long created = System.currentTimeMillis() - 60_000, deadline = created + ChatEnvelope.Expiry.HOUR_1.milliseconds;
        createFixtureKey(AndroidVault.contentAlias(deadline, messageId, userId));
        ChatEnvelope envelope = new ChatEnvelope(1, messageId, peerId, peerDevice, userId, deviceId, created, deadline, ChatEnvelope.Expiry.HOUR_1, "Hello group", null);
        ChatEngine.Entry entry = new ChatEngine.Entry(messageId, groupId, deadline, ChatEnvelope.Expiry.HOUR_1, false, false, "DELIVERED", userId, epoch, 1, peerId);
        vault.transaction(() -> {
            write("group/" + groupId, new GroupChat.Conversation(snapshot, "Private launch", null));
            write("entry/" + messageId, entry);
            byte[] content = RelayApi.JSON.toJson(new ChatEngine.Content(envelope, null)).getBytes(StandardCharsets.UTF_8);
            try { vault.put("body/" + messageId, vault.seal(AndroidVault.contentAlias(deadline, messageId, userId), content)); }
            finally { Arrays.fill(content, (byte) 0); }
            return null;
        });
        var device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", groupId); inject(activity); });
            awaitMessages(scenario);
            awaitMainCondition(scenario, activity -> hasText(root(activity), "Hello group"));
            // A swipe across a group message starts no reply (a group has none), and does not move the message.
            int[] at = new int[2], size = new int[2];
            locateBubble(scenario, "Hello group", at, size);
            assertTrue(device.swipe(at[0] + dpPx(24), at[1] + size[1] / 2, at[0] + dpPx(24) + dpPx(150), at[1] + size[1] / 2, 20));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNull(readField(activity, "pendingReplyTo"));
                assertEquals(0f, holderOf(activity, "Hello group").getTranslationX(), 0.5f);
            });
            // The press still opens the actions, without the reactions and without Reply.
            longPress(scenario, device, "Hello group");
            scenario.onActivity(activity -> {
                assertEquals(List.of("Forward", "Copy", "Info", "Delete", "More\u2026"), menuLabels(activity));
                assertTrue("A group message has no reactions", descendants(overlay(activity).getWindow().getDecorView()).stream().noneMatch(ScreenFlowTest::isReactionButton));
                Rect[] pieces = overlayPieces(activity, "Hello group");
                assertNull(pieces[0]);
                assertTrue("The card is below the message and on its side: " + pieces[2] + " " + pieces[1], pieces[2].top >= pieces[1].bottom - 1 && pieces[2].left == pieces[1].left);
            });
            overlaySnapshot(scenario, "110-actions-group-message");
            closeActions(scenario);
        }
    }

    private ChatEngine.Entry entryQuoting(boolean outgoing, String text, ChatEnvelope.Reference reference, long offset) throws Exception {
        ChatEnvelope.Expiry expiry = ChatEnvelope.Expiry.HOUR_1;
        UUID id = UUID.randomUUID(); long created = System.currentTimeMillis() + offset; long deadline = created + expiry.milliseconds;
        createFixtureKey(AndroidVault.contentAlias(deadline, id));
        ChatEnvelope envelope = new ChatEnvelope(1, id, outgoing ? userId : peerId, outgoing ? deviceId : peerDevice,
                outgoing ? peerId : userId, outgoing ? peerDevice : deviceId, created, deadline, expiry, text, null, null, reference, null);
        ChatEngine.Entry entry = new ChatEngine.Entry(id, peerId, deadline, expiry, outgoing, false, outgoing ? "DELIVERED" : "READ");
        vault.transaction(() -> {
            write("entry/" + id, entry);
            byte[] content = RelayApi.JSON.toJson(new ChatEngine.Content(envelope, null)).getBytes(StandardCharsets.UTF_8);
            try { vault.put("body/" + id, vault.seal(AndroidVault.contentAlias(deadline, id), content)); }
            finally { Arrays.fill(content, (byte) 0); }
            return null;
        });
        return entry;
    }

    private QuoteBlock quoteOf(MainActivity activity, String message) {
        View column = (View) text(root(activity), message).getParent();
        return descendants(column).stream().filter(QuoteBlock.class::isInstance).map(QuoteBlock.class::cast).findFirst().orElseThrow(() -> new AssertionError("No quote on " + message));
    }

    private String quoteName(QuoteBlock quote) {
        TextView name = descendants(quote).stream().filter(TextView.class::isInstance).map(TextView.class::cast).findFirst().orElseThrow();
        return name.getVisibility() == View.GONE ? null : name.getText().toString();
    }

    @Test public void aQuotedReplyNamesWhoseWordsTheyAreFillsTheBubbleAndStillWorksForRepliesFromEarlierApps() throws Exception {
        signedInFixture(); conversationsFixture();
        ChatEngine.Entry wasMine = entry(true, "Quoted by an older app, mine", null, ChatEnvelope.Expiry.HOUR_1, "DELIVERED", -100_000);
        String quote = "Coffee at 4?";
        entryQuoting(true, "r1 I answered them", new ChatEnvelope.Reference(UUID.randomUUID(), quote, false), -50_000);
        entryQuoting(true, "r2 I answered myself", new ChatEnvelope.Reference(UUID.randomUUID(), "Sounds good. Same place?", true), -49_000);
        entryQuoting(false, "r3 They answered me", new ChatEnvelope.Reference(UUID.randomUUID(), "Sounds good. Same place?", false), -48_000);
        entryQuoting(false, "r4 They answered themselves", new ChatEnvelope.Reference(UUID.randomUUID(), quote, true), -47_000);
        entryQuoting(false, "r5 An older app, my message", new ChatEnvelope.Reference(wasMine.id(), "Quoted by an older app, mine", null), -46_000);
        entryQuoting(false, "r6 An older app, gone", new ChatEnvelope.Reference(UUID.randomUUID(), "Something that has expired since", null), -45_000);
        entryQuoting(true, "ok", new ChatEnvelope.Reference(UUID.randomUUID(), "A quoted message that is a good deal longer than the reply that quotes it", false), -44_000);
        entryQuoting(false, "A reply that is much longer than what it quotes, so that the quote has to stretch to the width of the bubble", new ChatEnvelope.Reference(UUID.randomUUID(), "Short", false), -43_000);
        entryQuoting(false, "Photo reply", new ChatEnvelope.Reference(UUID.randomUUID(), QuoteBlock.PHOTO, true), -42_000);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            awaitMainCondition(scenario, activity -> hasText(root(activity), "r6 An older app, gone"));
            scenario.onActivity(activity -> {
                assertEquals("Aarav Mehta", quoteName(quoteOf(activity, "r1 I answered them")));
                assertEquals("You", quoteName(quoteOf(activity, "r2 I answered myself")));
                assertEquals("You", quoteName(quoteOf(activity, "r3 They answered me")));
                assertEquals("Aarav Mehta", quoteName(quoteOf(activity, "r4 They answered themselves")));
                assertEquals("An older app's reply to my message is found out from the message itself", "You", quoteName(quoteOf(activity, "r5 An older app, my message")));
                assertNull("and says nothing when it cannot be", quoteName(quoteOf(activity, "r6 An older app, gone")));
                assertTrue(hasText(quoteOf(activity, "r1 I answered them"), quote));
                // The quote takes the width of the bubble, to its edges, whichever of the two is wider.
                for (String reply : List.of("ok", "A reply that is much longer than what it quotes, so that the quote has to stretch to the width of the bubble", "r1 I answered them")) {
                    View column = (View) text(root(activity), reply).getParent();
                    QuoteBlock block = quoteOf(activity, reply);
                    assertEquals("The quote starts near the edge of " + reply, dpPx(4), block.getLeft(), 1);
                    assertEquals("and ends near the other edge", column.getWidth() - dpPx(4), block.getRight(), 1);
                    assertTrue("The text of the bubble is not pushed out of it", text(root(activity), reply).getRight() <= column.getWidth() - dpPx(14) + 1);
                }
                assertEquals("A quote cut to three lines at the most", 3, ((TextView) descendants(quoteOf(activity, "ok")).stream().filter(TextView.class::isInstance)
                    .reduce((first, last) -> last).orElseThrow()).getMaxLines());
            });
            snapshot(scenario, "107-quoted-replies");
            // The strip above the composer looks the same, and Photo gets its icon.
            openActions(scenario, "Photo reply");
            tapAction(scenario, "Reply");
            scenario.onActivity(activity -> {
                QuoteBlock strip = (QuoteBlock) readField(activity, "replyQuote");
                assertEquals(View.VISIBLE, ((View) readField(activity, "replyStrip")).getVisibility());
                assertEquals("Aarav Mehta", quoteName(strip)); assertTrue(hasText(strip, "Photo reply"));
            });
            snapshot(scenario, "108-reply-strip");
        }
    }

    @Test public void aLongMessageIsQuotedByItsBeginningAndStillSends() throws Exception {
        SignalClient remote = new SignalClient(peerId, new DeviceSecurityTest.MemoryVault());
        signedInFixture(); establishedConversation(remote);
        String words = "Considerable ".repeat(60) + "end";
        ChatEngine.Entry long1 = entry(false, words, null, ChatEnvelope.Expiry.HOUR_1, "READ", -60_000);
        List<ChatEngine.Send> outgoing = new ArrayList<>();
        plainTransport(new ArrayList<>(), outgoing);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            scenario.onActivity(activity -> assertTrue(((View) holderOf(activity, words).getParent()).performLongClick()));
            awaitOverlay(scenario);
            tapAction(scenario, "Reply");
            scenario.onActivity(activity -> {
                String snippet = (String) readField(activity, "pendingReplySnippet");
                assertTrue(snippet.endsWith("\u2026"));
                assertTrue(words.startsWith(snippet.substring(0, snippet.length() - 1)));
                assertTrue(snippet.getBytes(StandardCharsets.UTF_8).length <= ChatEnvelope.MAX_SNIPPET_BYTES);
                EditText composer = (EditText) readField(activity, "composer");
                composer.setText("An answer");
                ((View) descendants(root(activity)).stream().filter(view -> "Send".contentEquals(String.valueOf(view.getContentDescription()))).findFirst().orElseThrow()).performClick();
            });
            awaitMainCondition(scenario, activity -> outgoing.size() == 1);
            ChatEnvelope sent = decryptControl(remote, outgoing.get(0));
            assertEquals("An answer", sent.text());
            assertEquals(long1.id(), sent.reference().messageId());
            assertEquals("The message quoted was written by the other side", Boolean.FALSE, sent.reference().ownMessage());
            assertTrue(sent.reference().snippet().getBytes(StandardCharsets.UTF_8).length <= ChatEnvelope.MAX_SNIPPET_BYTES);
        }
    }

    @Test public void aMessageTallerThanTheScreenIsCutShortInTheActionsSoBothTheReactionsAndTheMenuStayOnScreen() throws Exception {
        signedInFixture(); conversationsFixture();
        String tall = "A very long message. " + "Another sentence that keeps the message going. ".repeat(110) + "The end.";
        entry(false, tall, null, ChatEnvelope.Expiry.HOUR_1, "READ", -30_000);
        var metrics = context.getResources().getDisplayMetrics();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { setField(activity, "selectedPeer", peerId); inject(activity); });
            awaitMessages(scenario);
            Rect[] original = new Rect[1];
            scenario.onActivity(activity -> original[0] = screenRect(holderOf(activity, tall)));
            assertTrue("The message is taller than the screen: " + original[0], original[0].height() > metrics.heightPixels);
            openActions(scenario, tall);
            scenario.onActivity(activity -> {
                View decor = overlay(activity).getWindow().getDecorView();
                Rect[] pieces = overlayPieces(activity, tall);
                Rect bar = pieces[0], stage = pieces[1], card = pieces[2];
                String where = " decor " + decor.getWidth() + "x" + decor.getHeight() + " screen " + metrics.widthPixels + "x" + metrics.heightPixels
                    + " stageParam " + stageOf(activity, tall).getLayoutParams().height + " original " + original[0];
                assertTrue("Cut short: " + stage + where, stage.height() < original[0].height() && stage.height() >= dpPx(96));
                assertTrue(bar.top >= 0 && bar.bottom <= stage.top + 1);
                assertTrue("The menu is on screen: " + card, card.top >= stage.bottom - 1 && card.bottom <= metrics.heightPixels);
                assertEquals("The beginning of the message is what stays", tall.substring(0, 20), text(descendants(stageOf(activity, tall)).stream().filter(TextView.class::isInstance)
                    .filter(view -> text(view).startsWith("A very long")).findFirst().orElseThrow()).substring(0, 20));
            });
            overlaySnapshot(scenario, "109-actions-tall-message");
            closeActions(scenario);
        }
    }

    private View stageOf(MainActivity activity, String message) {
        View decor = overlay(activity).getWindow().getDecorView();
        View column = (View) text(decor, message).getParent();
        return (View) ((View) column.getParent()).getParent();
    }

}