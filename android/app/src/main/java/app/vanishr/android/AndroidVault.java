package app.vanishr.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.*;
import android.util.AtomicFile;
import app.vanishr.crypto.SecureVault;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;

public final class AndroidVault implements SecureVault, AutoCloseable {
    static final String MASTER = "vanishr.vault.v1";
    static final String CONTENT = "vanishr.content.";
    static final String SAVED_ACCOUNT = "saved-account/";
    static final String ACCOUNT_DELETION = "account-deletion";
    record AccountDeletion(UUID userId, String origin, List<String> legacyAliases, boolean confirmed,
                           String token, long expiresAt, String deletionProof, long proofExpiresAt) {
        AccountDeletion(UUID userId, String origin, List<String> legacyAliases, boolean confirmed, String token, long expiresAt) {
            this(userId, origin, legacyAliases, confirmed, token, expiresAt, null, 0);
        }
        @Override public String toString() { return "AccountDeletion[redacted]"; }
    }
    static String phoneAlias(String alias) { return alias + ".phone"; }
    static String currentAlias(String alias) { return alias + ".phone.v3"; }
    static final byte RECORD_VERSION = 3;
    static final class PhoneLockedException extends SecurityException { }
    static final class PhoneLockRequiredException extends SecurityException { }
    static final class MigrationUnlockRequiredException extends SecurityException { }
    private static final int MAX_VAULT = 32 * 1024 * 1024;
    private final Context context;
    private final AtomicFile file;
    private Map<String, byte[]> values = new HashMap<>();
    private boolean open;
    private boolean changed;
    private int transactionDepth;

    public AndroidVault(Context context) {
        this.context = context.getApplicationContext();
        this.file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vault.bin"));
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private SecretKey key(String alias, boolean create) throws Exception {
        requirePhoneUnlocked();
        KeyStore store = keyStore();
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw new SecurityException("Secure key is unavailable");
        boolean strongBox = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        try { return generate(alias, strongBox); }
        catch (StrongBoxUnavailableException failure) { return generate(alias, false); }
    }

    static KeyGenParameterSpec keyPolicy(String alias, boolean strongBox, int androidVersion) {
        return new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).setUnlockedDeviceRequired(androidVersion >= 35)
                .setIsStrongBoxBacked(strongBox).build();
    }

    private SecretKey generate(String alias, boolean strongBox) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(keyPolicy(alias, strongBox, Build.VERSION.SDK_INT));
        return generator.generateKey();
    }

    public byte[] seal(String alias, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(currentAlias(alias), true));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(13 + encrypted.length).put(RECORD_VERSION).put(cipher.getIV()).put(encrypted).array();
    }

    public byte[] unseal(String alias, byte[] encrypted) throws Exception {
        if (encrypted == null || encrypted.length < 30) throw new SecurityException("Invalid protected record");
        String keyAlias = switch (encrypted[0]) {
            case 1 -> alias;
            case 2 -> phoneAlias(alias);
            case RECORD_VERSION -> currentAlias(alias);
            default -> throw new SecurityException("Invalid protected record");
        };
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(keyAlias, false), new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encrypted, 13, encrypted.length - 13);
        } catch (UserNotAuthenticatedException failure) {
            if (encrypted[0] != RECORD_VERSION) throw new MigrationUnlockRequiredException();
            throw failure;
        }
    }

    public synchronized void unlock() throws Exception {
        requirePhoneUnlocked();
        expireContentKeys(System.currentTimeMillis());
        if (open) return;
        if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()) {
            byte[] encrypted;
            try (InputStream input = file.openRead()) { encrypted = boundedRead(input, MAX_VAULT + 29); }
            byte[] plaintext = unseal(MASTER, encrypted);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
                int count = input.readInt();
                if (count < 0 || count > 4000) throw new SecurityException("Invalid vault size");
                for (int index = 0; index < count; index++) {
                    String name = input.readUTF();
                    int length = input.readInt();
                    if (name.length() > 256 || length < 0 || length > 4 * 1024 * 1024) throw new SecurityException("Invalid protected record");
                    byte[] value = new byte[length];
                    input.readFully(value);
                    if (values.put(name, value) != null) throw new SecurityException("Duplicate protected record");
                }
                if (input.read() != -1) throw new SecurityException("Invalid vault trailing data");
            } catch (Exception failure) { close(); throw failure; }
            finally { Arrays.fill(plaintext, (byte) 0); }
            open = true;
            if (encrypted[0] != RECORD_VERSION) {
                try {
                    persist();
                    keyStore().deleteEntry(MASTER);
                    keyStore().deleteEntry(phoneAlias(MASTER));
                } catch (Exception failure) { close(); throw failure; }
            }
        } else {
            byte[] probe = new byte[]{0};
            byte[] recovered = unseal(MASTER, seal(MASTER, probe));
            try {
                if (!Arrays.equals(probe, recovered)) throw new SecurityException("Secure storage verification failed");
            } finally { Arrays.fill(recovered, (byte) 0); }
            open = true;
        }
    }

    static byte[] boundedRead(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximum) throw new IOException("Content size limit exceeded");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void requirePhoneUnlocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null) throw new PhoneLockedException();
        if (!keyguard.isDeviceSecure()) throw new PhoneLockRequiredException();
        if (keyguard.isDeviceLocked()) throw new PhoneLockedException();
    }

    private void requireOpen() {
        if (!open) throw new SecurityException("Vault is closed");
        requirePhoneUnlocked();
    }

    @Override public synchronized byte[] get(String name) {
        requireOpen();
        byte[] value = values.get(name);
        return value == null ? null : value.clone();
    }
    @Override public synchronized void put(String name, byte[] value) {
        requireOpen();
        if (transactionDepth == 0 || name.length() > 256 || value.length > 4 * 1024 * 1024) throw new IllegalStateException("Protected writes require a bounded transaction");
        if (!values.containsKey(name) && values.size() >= 4000) throw new IllegalStateException("Secure storage record capacity reached");
        if (!Arrays.equals(values.get(name), value)) { values.put(name, value.clone()); changed = true; }
    }
    @Override public synchronized void remove(String name) {
        requireOpen();
        if (transactionDepth == 0) throw new IllegalStateException("Protected writes require a transaction");
        if (values.remove(name) != null) changed = true;
    }
    @Override public synchronized List<String> names(String prefix) {
        requireOpen();
        List<String> result = new ArrayList<>();
        for (String name : values.keySet()) if (name.startsWith(prefix)) result.add(name);
        return result;
    }

    @Override public synchronized <Result> Result transaction(Operation<Result> operation) throws Exception {
        return transaction(operation, false);
    }

    private <Result> Result transaction(Operation<Result> operation, boolean verifyCommit) throws Exception {
        requireOpen();
        if (transactionDepth > 0) {
            if (verifyCommit) throw new IllegalStateException("Account deletion requires a durable outer transaction");
            return operation.run();
        }
        Map<String, byte[]> before = new HashMap<>(values);
        changed = false;
        transactionDepth++;
        try {
            Result result = operation.run();
            if (changed) persist(verifyCommit);
            return result;
        } catch (Exception failure) {
            for (byte[] value : values.values()) if (!before.containsValue(value)) Arrays.fill(value, (byte) 0);
            values = before;
            throw failure;
        } finally {
            transactionDepth--;
            for (byte[] value : before.values()) if (!values.containsValue(value)) Arrays.fill(value, (byte) 0);
        }
    }

    private void persist() throws Exception { persist(false); }

    private void persist(boolean verifyCommit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(values.size());
            for (Map.Entry<String, byte[]> entry : values.entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
                if (bytes.size() > MAX_VAULT) throw new IllegalStateException("Secure storage capacity reached");
            }
        }
        byte[] plaintext = bytes.toByteArray();
        byte[] encrypted;
        try { encrypted = seal(MASTER, plaintext); }
        finally { Arrays.fill(plaintext, (byte) 0); }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(encrypted);
            file.finishWrite(output);
        } catch (Exception failure) { file.failWrite(output); throw failure; }
        if (verifyCommit) {
            try (InputStream input = file.openRead()) {
                if (!Arrays.equals(encrypted, boundedRead(input, MAX_VAULT + 29)))
                    throw new IOException("Encrypted account update did not commit");
            }
        }
    }

    static String contentAlias(long expiresAt, UUID id) { return CONTENT + expiresAt + "." + id; }
    public static void deleteContentKey(long expiresAt, UUID id) throws Exception { deleteContentKey(expiresAt, id, null); }

    static String contentAlias(long expiresAt, UUID id, UUID accountId) {
        return accountId == null ? contentAlias(expiresAt, id) : CONTENT + expiresAt + "." + accountId + "." + id;
    }

    static void deleteContentKey(long expiresAt, UUID id, UUID accountId) throws Exception {
        KeyStore store = keyStore();
        String alias = contentAlias(expiresAt, id, accountId);
        store.deleteEntry(alias);
        store.deleteEntry(phoneAlias(alias));
        store.deleteEntry(currentAlias(alias));
    }

    static void deleteLegacyContentKey(String alias) throws Exception {
        if (!alias.startsWith(CONTENT) || alias.endsWith(".phone") || alias.endsWith(".phone.v3")) throw new SecurityException("Invalid legacy content alias");
        KeyStore store = keyStore();
        store.deleteEntry(alias);
        store.deleteEntry(phoneAlias(alias));
    }

    synchronized void migrateProtectedRecords(Map<String, String> aliases) throws Exception {
        Set<String> replaced = new HashSet<>();
        transaction(() -> {
            for (var record : aliases.entrySet()) {
                byte[] encrypted = get(record.getKey());
                if (encrypted == null) continue;
                if (encrypted.length < 30 || encrypted[0] < 1 || encrypted[0] > RECORD_VERSION) throw new SecurityException("Invalid protected record");
                if (encrypted[0] == RECORD_VERSION) continue;
                String alias = record.getValue();
                if (!alias.startsWith(CONTENT) || alias.endsWith(".phone") || alias.endsWith(".phone.v3")) throw new SecurityException("Invalid legacy content alias");
                byte[] plaintext = unseal(alias, encrypted);
                try { put(record.getKey(), seal(alias, plaintext)); }
                finally { Arrays.fill(plaintext, (byte) 0); }
                replaced.add(alias);
            }
            return null;
        });
        for (String alias : replaced) deleteLegacyContentKey(alias);
    }

    public static void expireContentKeys(long now) throws Exception {
        KeyStore store = keyStore();
        Enumeration<String> aliases = store.aliases();
        List<String> expired = new ArrayList<>();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (!alias.startsWith(CONTENT)) continue;
            String deadline = alias.substring(CONTENT.length()).split("\\.", 2)[0];
            try { if (Long.parseLong(deadline) <= now) expired.add(alias); }
            catch (NumberFormatException failure) { throw new SecurityException("Invalid content key metadata"); }
        }
        for (String alias : expired) store.deleteEntry(alias);
    }

    static String savedAccountPrefix(UUID userId) { return SAVED_ACCOUNT + userId + "/"; }

    SecureVault accountVault(ChatEngine.Account owner) {
        UUID userId = Objects.requireNonNull(owner.userId());
        UUID deviceId = owner.deviceId();
        String origin = Objects.requireNonNull(owner.origin());
        return new SecureVault() {
            private void check(String name) {
                if (values.containsKey(ACCOUNT_DELETION) || name.startsWith(SAVED_ACCOUNT))
                    throw new SecurityException("The encryption account is no longer active");
                ChatEngine.Account active = record("account", ChatEngine.Account.class);
                if (active == null || !userId.equals(active.userId())
                        || !Objects.equals(deviceId, active.deviceId()) || !origin.equals(active.origin()))
                    throw new SecurityException("The encryption account is no longer active");
            }
            @Override public byte[] get(String name) {
                synchronized (AndroidVault.this) { check(name); return AndroidVault.this.get(name); }
            }
            @Override public void put(String name, byte[] value) {
                synchronized (AndroidVault.this) { check(name); AndroidVault.this.put(name, value); }
            }
            @Override public void remove(String name) {
                synchronized (AndroidVault.this) { check(name); AndroidVault.this.remove(name); }
            }
            @Override public List<String> names(String prefix) {
                synchronized (AndroidVault.this) {
                    check(prefix);
                    return AndroidVault.this.names(prefix).stream().filter(name -> !name.startsWith(SAVED_ACCOUNT)).toList();
                }
            }
            @Override public <Result> Result transaction(Operation<Result> operation) throws Exception {
                return AndroidVault.this.transaction(() -> { check(""); return operation.run(); });
            }
        };
    }

    synchronized void saveAccount(UUID userId) throws Exception {
        String prefix = savedAccountPrefix(Objects.requireNonNull(userId));
        transaction(() -> {
            if (get(ACCOUNT_DELETION) != null) throw new SecurityException("Account deletion must finish before signing out");
            if (get("account") == null || !names(prefix).isEmpty()) throw new SecurityException("Account storage is inconsistent");
            for (String name : names("")) {
                if (name.startsWith(SAVED_ACCOUNT)) continue;
                byte[] value = get(name);
                try { remove(name); put(prefix + name, value); }
                finally { Arrays.fill(value, (byte) 0); }
            }
            return null;
        });
    }

    synchronized boolean restoreAccount(UUID userId) throws Exception {
        String prefix = savedAccountPrefix(Objects.requireNonNull(userId));
        return transaction(() -> {
            if (get(ACCOUNT_DELETION) != null || get(prefix + ACCOUNT_DELETION) != null)
                throw new SecurityException("Account deletion must finish before signing in");
            if (get(prefix + "account") == null) return false;
            if (names("").stream().anyMatch(name -> !name.startsWith(SAVED_ACCOUNT)))
                throw new SecurityException("Sign out before restoring another account");
            for (String name : names(prefix)) {
                byte[] value = get(name);
                try { remove(name); put(name.substring(prefix.length()), value); }
                finally { Arrays.fill(value, (byte) 0); }
            }
            return true;
        });
    }

    private <Value> Value record(String name, Class<Value> type) {
        byte[] value = get(name);
        if (value == null) return null;
        try { return RelayApi.JSON.fromJson(new String(value, StandardCharsets.UTF_8), type); }
        finally { Arrays.fill(value, (byte) 0); }
    }

    private void deletionRecord(AccountDeletion deletion) {
        byte[] value = RelayApi.JSON.toJson(deletion).getBytes(StandardCharsets.UTF_8);
        try { put(ACCOUNT_DELETION, value); }
        finally { Arrays.fill(value, (byte) 0); }
    }

    synchronized AccountDeletion pendingAccountDeletion() {
        AccountDeletion deletion = record(ACCOUNT_DELETION, AccountDeletion.class);
        if (deletion == null) return null;
        if (deletion.userId() == null || deletion.origin() == null || deletion.legacyAliases() == null
                || deletion.legacyAliases().size() > 4000
                || deletion.legacyAliases().stream().anyMatch(alias -> alias == null || !legacyContentAlias(alias))
                || new HashSet<>(deletion.legacyAliases()).size() != deletion.legacyAliases().size()
                || deletion.confirmed() && (deletion.token() != null || deletion.expiresAt() != 0
                    || deletion.deletionProof() != null || deletion.proofExpiresAt() != 0)
                || !deletion.confirmed() && (deletion.token() == null ? deletion.expiresAt() != 0
                    : !deletion.token().matches("[A-Za-z0-9_-]{43}") || deletion.expiresAt() <= 0)
                || !deletion.confirmed() && (deletion.deletionProof() == null ? deletion.proofExpiresAt() != 0
                    : !deletion.deletionProof().matches("[A-Za-z0-9_-]{43}") || deletion.proofExpiresAt() <= 0
                        || deletion.deletionProof().equals(deletion.token())))
            throw new SecurityException("Invalid pending account deletion");
        requireAccountOwner(deletion.userId(), deletion.origin());
        return deletion;
    }

    private void requireAccountOwner(UUID owner, String origin) {
        ChatEngine.Account active = record("account", ChatEngine.Account.class);
        if (active == null || !owner.equals(active.userId()) || !Objects.equals(origin, active.origin())
                || !names(savedAccountPrefix(owner)).isEmpty())
            throw new SecurityException("Account removal does not match the active account");
    }

    private static boolean legacyContentAlias(String alias) {
        if (!alias.startsWith(CONTENT)) return false;
        String[] parts = alias.substring(CONTENT.length()).split("\\.", -1);
        if (parts.length != 2) return false;
        try { return Long.parseLong(parts[0]) > 0 && UUID.fromString(parts[1]).toString().equals(parts[1]); }
        catch (IllegalArgumentException failure) { return false; }
    }

    private Set<String> retainedContentAliases(UUID deleting) {
        Set<String> retained = new HashSet<>();
        Set<String> partitions = new HashSet<>();
        for (String name : names(SAVED_ACCOUNT)) {
            int separator = name.indexOf('/', SAVED_ACCOUNT.length());
            if (separator < 0) throw new SecurityException("Invalid saved account partition");
            partitions.add(name.substring(0, separator + 1));
        }
        for (String prefix : partitions) {
            ChatEngine.Account saved = record(prefix + "account", ChatEngine.Account.class);
            if (saved == null || saved.userId() == null || saved.userId().equals(deleting)
                    || !prefix.equals(savedAccountPrefix(saved.userId())))
                throw new SecurityException("Invalid saved account partition");
            for (String entryName : names(prefix + "entry/")) {
                ChatEngine.Entry entry = record(entryName, ChatEngine.Entry.class);
                if (entry == null || entry.id() == null || !entryName.equals(prefix + "entry/" + entry.id())
                        || entry.keyOwner() != null && !saved.userId().equals(entry.keyOwner()))
                    throw new SecurityException("Invalid saved content ownership");
                retained.add(contentAlias(entry.expiresAt(), entry.id(), entry.keyOwner()));
            }
        }
        return retained;
    }

    synchronized void deleteOwnedContentKey(ChatEngine.Entry entry, UUID owner) throws Exception {
        if (owner == null || entry.keyOwner() != null && !owner.equals(entry.keyOwner()))
            throw new SecurityException("Content belongs to another account");
        if (entry.keyOwner() == null && retainedContentAliases(owner).contains(contentAlias(entry.expiresAt(), entry.id()))) return;
        deleteContentKey(entry.expiresAt(), entry.id(), entry.keyOwner());
    }

    synchronized void beginAccountDeletion(UUID owner, String origin, String token, long expiresAt) throws Exception {
        beginAccountDeletion(owner, origin, token, expiresAt, null, 0);
    }

    synchronized void beginAccountDeletion(UUID owner, String origin, String token, long expiresAt,
                                          String deletionProof, long proofExpiresAt) throws Exception {
        requireAccountOwner(Objects.requireNonNull(owner), origin);
        if (get(ACCOUNT_DELETION) != null) throw new SecurityException("Account deletion is already pending");
        long now = System.currentTimeMillis();
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || expiresAt <= now || expiresAt > now + 300_000)
            throw new SecurityException("Invalid deletion authorization");
        if (deletionProof == null ? proofExpiresAt != 0
                : !deletionProof.matches("[A-Za-z0-9_-]{43}") || deletionProof.equals(token)
                    || proofExpiresAt <= expiresAt || proofExpiresAt > now + AccountSafety.DELETION_PROOF_LIFETIME)
            throw new SecurityException("Invalid deletion recovery proof");
        Set<String> retained = retainedContentAliases(owner);
        Set<String> legacy = new TreeSet<>();
        for (String name : names("entry/")) {
            ChatEngine.Entry entry = record(name, ChatEngine.Entry.class);
            if (entry == null || entry.id() == null || entry.expiresAt() <= 0
                    || !name.equals("entry/" + entry.id()) || entry.keyOwner() != null && !owner.equals(entry.keyOwner()))
                throw new SecurityException("Invalid account content ownership");
            String alias = contentAlias(entry.expiresAt(), entry.id(), entry.keyOwner());
            if (entry.keyOwner() == null && !retained.contains(alias)) legacy.add(alias);
        }
        transaction(() -> {
            deletionRecord(new AccountDeletion(owner, origin, List.copyOf(legacy), false, token, expiresAt, deletionProof, proofExpiresAt));
            return null;
        }, true);
    }

    synchronized void confirmAccountDeletion(UUID owner) throws Exception {
        AccountDeletion deletion = Objects.requireNonNull(pendingAccountDeletion(), "Account deletion is not pending");
        if (!owner.equals(deletion.userId())) throw new SecurityException("Account deletion owner changed");
        if (deletion.confirmed()) return;
        transaction(() -> {
            deletionRecord(new AccountDeletion(owner, deletion.origin(), deletion.legacyAliases(), true, null, 0));
            return null;
        }, true);
    }

    synchronized void cancelAccountDeletion(UUID owner) throws Exception {
        AccountDeletion deletion = Objects.requireNonNull(pendingAccountDeletion(), "Account deletion is not pending");
        if (!owner.equals(deletion.userId()) || deletion.confirmed()) throw new SecurityException("Confirmed deletion cannot be cancelled");
        transaction(() -> { remove(ACCOUNT_DELETION); return null; }, true);
    }

    synchronized void expireDeletionAuthorization() throws Exception {
        AccountDeletion deletion = pendingAccountDeletion();
        if (deletion == null || deletion.confirmed()) return;
        long now = System.currentTimeMillis();
        boolean proofExpired = deletion.deletionProof() != null && deletion.proofExpiresAt() <= now;
        boolean tokenExpired = deletion.token() != null && (deletion.expiresAt() <= now || proofExpired);
        if (tokenExpired || proofExpired)
            transaction(() -> {
                deletionRecord(new AccountDeletion(deletion.userId(), deletion.origin(), deletion.legacyAliases(), false,
                        tokenExpired ? null : deletion.token(), tokenExpired ? 0 : deletion.expiresAt(),
                        proofExpired ? null : deletion.deletionProof(), proofExpired ? 0 : deletion.proofExpiresAt()));
                return null;
            }, true);
    }

    synchronized void boundDeletionProof(UUID owner, long expiresAt) throws Exception {
        AccountDeletion deletion = Objects.requireNonNull(pendingAccountDeletion(), "Account deletion is not pending");
        long now = System.currentTimeMillis();
        if (!owner.equals(deletion.userId()) || deletion.confirmed() || deletion.deletionProof() == null
                || deletion.proofExpiresAt() <= now || expiresAt <= now || expiresAt > now + AccountSafety.DELETION_PROOF_LIFETIME)
            throw new SecurityException("Invalid deletion recovery deadline");
        long bounded = Math.min(deletion.proofExpiresAt(), expiresAt);
        if (bounded == deletion.proofExpiresAt()) return;
        transaction(() -> {
            deletionRecord(new AccountDeletion(owner, deletion.origin(), deletion.legacyAliases(), false,
                    deletion.token(), deletion.expiresAt(), deletion.deletionProof(), bounded));
            return null;
        }, true);
    }

    synchronized void eraseAccount(UUID owner) throws Exception {
        requireOpen();
        if (transactionDepth != 0) throw new IllegalStateException("Account removal cannot run inside a transaction");
        AccountDeletion deletion = pendingAccountDeletion();
        if (deletion == null || !deletion.confirmed() || !Objects.requireNonNull(owner).equals(deletion.userId()))
            throw new SecurityException("Server-confirmed account deletion is required");
        Set<String> retained = retainedContentAliases(owner);
        KeyStore store = keyStore();
        Set<String> aliases = new TreeSet<>(deletion.legacyAliases());
        for (String stored : Collections.list(store.aliases())) {
            if (!stored.startsWith(CONTENT)) continue;
            String alias = stored.endsWith(".phone.v3") ? stored.substring(0, stored.length() - 9)
                    : stored.endsWith(".phone") ? stored.substring(0, stored.length() - 6) : stored;
            String[] parts = alias.substring(CONTENT.length()).split("\\.", -1);
            if (parts.length == 3 && owner.toString().equals(parts[1])) {
                try {
                    if (Long.parseLong(parts[0]) <= 0 || !UUID.fromString(parts[2]).toString().equals(parts[2]))
                        throw new SecurityException("Invalid owned content key");
                } catch (IllegalArgumentException failure) { throw new SecurityException("Invalid owned content key"); }
                aliases.add(alias);
            }
        }
        // Keep the encrypted tombstone until all owned keys and the replacement vault commit.
        for (String alias : aliases) {
            if (retained.contains(alias)) continue;
            store.deleteEntry(alias);
            store.deleteEntry(phoneAlias(alias));
            store.deleteEntry(currentAlias(alias));
            if (store.containsAlias(alias) || store.containsAlias(phoneAlias(alias)) || store.containsAlias(currentAlias(alias)))
                throw new SecurityException("Owned content key removal did not complete");
        }
        transaction(() -> {
            for (String name : names("")) if (!name.startsWith(SAVED_ACCOUNT)) remove(name);
            return null;
        }, true);
    }

    void stopAccountPush() throws IOException {
        if (!context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putBoolean("push-active", false).commit())
            throw new IOException("Notification delivery could not be disabled");
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager != null) manager.cancel("vanishr-new", 1);
    }

    public synchronized void eraseAccount() throws Exception {
        requireOpen();
        if (transactionDepth != 0) throw new IllegalStateException("Account removal cannot run inside a transaction");
        try {
            KeyStore store = keyStore();
            List<String> aliases = Collections.list(store.aliases());
            for (String alias : aliases) if (alias.equals(MASTER) || alias.equals(phoneAlias(MASTER)) || alias.equals(currentAlias(MASTER)) || alias.startsWith(CONTENT)) store.deleteEntry(alias);
            file.delete();
            String path = file.getBaseFile().getPath();
            if (file.getBaseFile().exists() || new File(path + ".bak").exists() || new File(path + ".new").exists())
                throw new SecurityException("Encrypted account removal did not complete");
        } finally { close(); }
    }

    @Override public synchronized void close() {
        for (byte[] value : values.values()) Arrays.fill(value, (byte) 0);
        values.clear();
        open = false;
    }
}