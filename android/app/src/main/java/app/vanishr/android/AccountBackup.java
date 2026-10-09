package app.vanishr.android;

import android.content.Context;
import app.vanishr.crypto.BackupCipher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;

import static app.vanishr.android.RelayApi.JSON;

/**
 * Opt-in backup of manually verified contacts and private nicknames. It is encrypted on this phone with a recovery key
 * that the relay never sees. Private keys, Signal sessions, messages, photos and credentials are never included.
 * The presence of the {@value #KEY} vault record means backup is on.
 */
final class AccountBackup {
    static final String KEY = "backup-key";
    static final int MAX_CONTACTS = 200;
    static final long INTERVAL = 24L * 3_600_000;
    static final long RETRY_DELAY = 30L * 60_000;
    private static final int SNAPSHOT_VERSION = 1;
    private static final long CONTACT_PACING = 350;
    private static final long RATE_LIMIT_WAIT = 10_000;

    /** A failure whose message is safe and useful to show to the user. */
    static final class Rejected extends Exception {
        Rejected(String message) { super(message); }
    }

    record Contact(UUID userId, UUID deviceId, String identityKey, String handle, String profileName, String nickname) {
        @Override public String toString() { return "Contact[redacted]"; }
    }
    record Snapshot(int version, long createdAt, List<Contact> contacts) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }
    record Status(boolean exists, long size, long updatedAt, long expiresAt) { }
    record Result(int restored, int present, int unrestored) { }
    private enum Outcome { RESTORED, PRESENT, UNRESTORED }

    /** A sealed backup and the credentials needed to upload it after the vault closes; it holds no recovery key. */
    static final class Prepared implements AutoCloseable {
        private final UUID userId;
        private final String origin;
        private final java.util.function.BiFunction<String, String, RelayApi> transports;
        private volatile RelayApi api;
        private String token;
        private byte[] blob;
        private boolean closed;

        Prepared(UUID userId, String origin, String token, byte[] blob) { this(userId, origin, token, blob, RelayApi::new); }

        Prepared(UUID userId, String origin, String token, byte[] blob, java.util.function.BiFunction<String, String, RelayApi> transports) {
            this.userId = userId; this.origin = origin; this.token = token; this.blob = blob; this.transports = transports;
        }

        UUID userId() { return userId; }

        Status upload() throws Exception {
            RelayApi transport;
            byte[] sealed;
            synchronized (this) {
                if (closed || token == null || blob == null) throw new IOException("Backup was cancelled");
                transport = transports.apply(origin, token);
                api = transport;
                sealed = blob;
            }
            try { return transport.backupUpload(sealed); }
            finally { transport.close(); }
        }

        void cancel() {
            RelayApi transport = api;
            if (transport != null) transport.cancelRequests();
        }

        @Override public synchronized void close() {
            closed = true;
            token = null;
            if (blob != null) Arrays.fill(blob, (byte) 0);
            blob = null;
        }
    }

    private final ChatEngine engine;
    private final AndroidVault vault;
    // Replaceable so tests can route the background upload to a synthetic relay.
    java.util.function.BiFunction<String, String, RelayApi> transports = RelayApi::new;

    AccountBackup(ChatEngine engine, AndroidVault vault) { this.engine = engine; this.vault = vault; }

    boolean enabled() { return engine.account() != null && !vault.names(KEY).isEmpty(); }

    private ChatEngine.Account requireActive() {
        ChatEngine.Account account = engine.account();
        if (account == null || !account.enrolled() || !engine.authenticated()) throw new SecurityException("Sign in before using backup");
        return account;
    }

    private byte[] key() {
        byte[] key = vault.get(KEY);
        if (key == null) throw new IllegalStateException("Backup is off");
        if (key.length != BackupCipher.KEY_BYTES) { Arrays.fill(key, (byte) 0); throw new SecurityException("Invalid backup key"); }
        return key;
    }

    private void write(String key, Object value) {
        byte[] encoded = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        try { vault.put(key, encoded); }
        finally { Arrays.fill(encoded, (byte) 0); }
    }

    private void keep(byte[] recoveryKey) throws Exception {
        byte[] stored = recoveryKey.clone();
        try { vault.transaction(() -> { vault.put(KEY, stored); return null; }); }
        finally { Arrays.fill(stored, (byte) 0); }
    }

    /** Stores a newly generated recovery key. It is saved before it is shown, so it can always be shown again. */
    void enable(byte[] recoveryKey) throws Exception {
        ChatEngine.Account account = requireActive();
        if (recoveryKey == null || recoveryKey.length != BackupCipher.KEY_BYTES) throw new IllegalArgumentException("Invalid recovery key");
        keep(recoveryKey);
        BackupState.clear(vault.applicationContext(), account.userId());
    }

    String recoveryKey() {
        byte[] key = key();
        try { return BackupCipher.formatRecoveryKey(key); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    Status serverStatus() throws Exception {
        requireActive();
        Status status = engine.groupApi().call("GET", "/account/backup/status", null, Status.class);
        return status == null ? new Status(false, 0, 0, 0) : status;
    }

    /** The server copy is removed first, so a failed request leaves backup on and the recovery key intact. */
    void disable() throws Exception {
        ChatEngine.Account account = requireActive();
        engine.groupApi().call("DELETE", "/account/backup", null, Void.class);
        vault.transaction(() -> { vault.remove(KEY); return null; });
        BackupState.clear(vault.applicationContext(), account.userId());
    }

    boolean due(long now) {
        ChatEngine.Account account = engine.account();
        if (account == null || !account.enrolled() || !enabled()) return false;
        Context context = vault.applicationContext();
        // A timestamp in the future (the clock was set back) must not suppress backups.
        long success = BackupState.lastSuccess(context, account.userId()), attempt = BackupState.lastAttempt(context, account.userId());
        if (success > now) success = 0;
        if (attempt > now) attempt = 0;
        return now - success >= INTERVAL && now - attempt >= RETRY_DELAY;
    }

    Snapshot snapshot() throws Rejected {
        List<Contact> contacts = new ArrayList<>();
        for (String name : vault.names("contact/")) {
            byte[] stored = vault.get(name);
            ChatEngine.Peer peer = stored == null ? null : JSON.fromJson(new String(stored, StandardCharsets.UTF_8), ChatEngine.Peer.class);
            if (stored != null) Arrays.fill(stored, (byte) 0);
            // Only contacts the app itself treats as independently verified are backed up; automatic admin introductions are
            // re-created by onboarding, and contacts without a username cannot be validated on restore.
            if (peer == null || peer.handle() == null || !engine.independentlyVerified(peer.userId())) continue;
            String nickname = engine.privateName(peer);
            contacts.add(new Contact(peer.userId(), peer.deviceId(), peer.identityKey(), peer.handle(), peer.profileName(), nickname.isEmpty() ? null : nickname));
        }
        if (contacts.size() > MAX_CONTACTS) throw new Rejected("Backups support up to " + MAX_CONTACTS + " verified contacts. Remove a contact and try again.");
        contacts.sort(Comparator.comparing(Contact::userId));
        return new Snapshot(SNAPSHOT_VERSION, System.currentTimeMillis(), List.copyOf(contacts));
    }

    static byte[] encode(Snapshot snapshot) { return JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8); }

    static boolean validNickname(String value) {
        return value != null && !value.isEmpty() && value.equals(value.strip()) && value.length() <= 40 && value.codePoints().noneMatch(Character::isISOControl);
    }

    /** Rejects anything the app itself would not have written; a backup is untrusted input until fully validated. */
    static Snapshot decode(byte[] json, UUID owner) throws Rejected {
        Rejected unreadable = new Rejected("This backup is damaged or was made by a newer version of Vanishr.");
        Snapshot snapshot;
        try { snapshot = JSON.fromJson(new String(json, StandardCharsets.UTF_8), Snapshot.class); }
        catch (RuntimeException failure) { throw unreadable; }
        if (snapshot == null || snapshot.version() != SNAPSHOT_VERSION || snapshot.contacts() == null || snapshot.contacts().size() > MAX_CONTACTS)
            throw unreadable;
        Set<UUID> seen = new HashSet<>();
        for (Contact contact : snapshot.contacts()) {
            if (contact == null || contact.userId() == null || contact.deviceId() == null || owner.equals(contact.userId()) || !seen.add(contact.userId())
                    || contact.identityKey() == null || !contact.identityKey().matches("[A-Za-z0-9+/]{44}")
                    || contact.nickname() != null && !validNickname(contact.nickname())) throw unreadable;
            try { ChatEngine.validateProfile(new ChatEngine.Profile(contact.userId(), contact.handle(), contact.profileName()), contact.userId()); }
            catch (SecurityException | IllegalArgumentException invalid) { throw unreadable; }
        }
        return snapshot;
    }

    /** Seals the snapshot now, while the vault is open, so a background service needs only the ciphertext and a token. */
    Prepared prepare() throws Exception {
        ChatEngine.Account account = requireActive();
        engine.safety().requireNotDeleting();
        Context context = vault.applicationContext();
        BackupState.attempted(context, account.userId(), System.currentTimeMillis());
        byte[] key = null, json = null;
        try {
            engine.renewAccessIfExpiringWithin(120_000);
            ChatEngine.Account current = engine.account();
            if (current == null || current.accessToken() == null || current.accessToken().isEmpty()) throw new SecurityException("Sign in before using backup");
            key = key();
            json = encode(snapshot());
            return new Prepared(account.userId(), current.origin(), current.accessToken(), BackupCipher.seal(key, account.userId(), json), transports);
        } catch (Exception failure) {
            BackupState.failed(context, account.userId());
            throw failure;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (json != null) Arrays.fill(json, (byte) 0);
        }
    }

    /**
     * Restores contacts only when the relay's current identity for the account is exactly the identity the user verified
     * before the backup. Anything else is left for fresh in-person verification; nothing is trusted silently.
     */
    Result restore(String recoveryKeyText) throws Exception {
        ChatEngine.Account account = requireActive();
        engine.safety().requireNotDeleting();
        byte[] key;
        try { key = BackupCipher.parseRecoveryKey(recoveryKeyText); }
        catch (IllegalArgumentException invalid) { throw new Rejected("Enter the recovery key exactly as it was shown (52 letters and digits)."); }
        try {
            byte[] blob;
            try { blob = engine.groupApi().backupDownload(); }
            catch (RelayApi.ApiFailure failure) {
                if (failure.status == 404) throw new Rejected("No backup was found for this account.");
                throw failure;
            }
            byte[] json;
            try { json = BackupCipher.open(key, account.userId(), blob); }
            catch (GeneralSecurityException failure) { throw new Rejected("That recovery key does not match this backup, or the backup is damaged."); }
            finally { Arrays.fill(blob, (byte) 0); }
            Snapshot snapshot;
            try { snapshot = decode(json, account.userId()); }
            finally { Arrays.fill(json, (byte) 0); }
            Result result = restoreContacts(snapshot.contacts());
            keep(key);
            BackupState.clear(vault.applicationContext(), account.userId());
            return result;
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private Result restoreContacts(List<Contact> contacts) throws Exception {
        engine.safety().refresh();
        int restored = 0, present = 0, unrestored = 0;
        for (Contact saved : contacts) {
            long started = System.currentTimeMillis();
            switch (restoreContact(saved)) {
                case RESTORED -> restored++;
                case PRESENT -> present++;
                case UNRESTORED -> unrestored++;
            }
            long wait = CONTACT_PACING - (System.currentTimeMillis() - started);
            if (wait > 0) Thread.sleep(wait);
        }
        return new Result(restored, present, unrestored);
    }

    private Outcome restoreContact(Contact saved) throws Exception {
        requireActive();
        UUID id = saved.userId();
        if (!vault.names("contact/" + id).isEmpty()) return Outcome.PRESENT;
        if (engine.safety().isBlocked(id)) return Outcome.UNRESTORED;
        ChatEngine.Contact current = lookup(id);
        if (current == null || !id.equals(current.userId()) || !saved.deviceId().equals(current.deviceId())
                || !saved.identityKey().equals(current.identityKey())) return Outcome.UNRESTORED;
        String name = saved.nickname() != null ? saved.nickname() : saved.profileName() != null ? saved.profileName() : saved.handle();
        ChatEngine.Peer peer = new ChatEngine.Peer(id, current.deviceId(), current.identityKey(), name, saved.handle(), saved.profileName());
        try {
            vault.transaction(() -> {
                engine.onboarding().manuallyVerified(peer);
                engine.groupSignal().verifyPeer(id, Base64.getDecoder().decode(peer.identityKey()));
                write("contact/" + id, peer);
                if (saved.nickname() != null) write("contact-name/" + id, saved.nickname());
                return null;
            });
        } catch (AndroidVault.PhoneLockedException | AndroidVault.PhoneLockRequiredException locked) { throw locked; }
        catch (SecurityException mismatch) { return Outcome.UNRESTORED; }
        return Outcome.RESTORED;
    }

    private ChatEngine.Contact lookup(UUID id) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return engine.groupApi().call("GET", "/users/id/" + id, null, ChatEngine.Contact.class); }
            catch (RelayApi.ApiFailure failure) {
                if (failure.status == 404) return null;
                if (failure.status != 429 || attempt == 6) throw failure;
                Thread.sleep(RATE_LIMIT_WAIT);
            }
        }
    }
}
