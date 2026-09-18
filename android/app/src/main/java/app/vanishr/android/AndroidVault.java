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
    static String phoneAlias(String alias) { return alias + ".phone"; }
    static final class PhoneLockedException extends SecurityException { }
    static final class PhoneLockRequiredException extends SecurityException { }
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
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) throw new PhoneLockRequiredException();
        boolean strongBox = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        try { return generate(alias, strongBox); }
        catch (StrongBoxUnavailableException failure) { return generate(alias, false); }
    }

    private SecretKey generate(String alias, boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder specification = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(specification.build());
        return generator.generateKey();
    }

    public byte[] seal(String alias, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(phoneAlias(alias), true));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(13 + encrypted.length).put((byte) 2).put(cipher.getIV()).put(encrypted).array();
    }

    public byte[] unseal(String alias, byte[] encrypted) throws Exception {
        if (encrypted == null || encrypted.length < 30 || (encrypted[0] != 1 && encrypted[0] != 2)) throw new SecurityException("Invalid protected record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(encrypted[0] == 1 ? alias : phoneAlias(alias), false), new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(encrypted, 13, encrypted.length - 13);
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
            if (encrypted[0] == 1) {
                try {
                    persist();
                    keyStore().deleteEntry(MASTER);
                } catch (Exception failure) { close(); throw failure; }
            }
        } else {
            key(phoneAlias(MASTER), true);
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
        if (keyguard == null || keyguard.isDeviceLocked()) throw new PhoneLockedException();
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
        requireOpen();
        if (transactionDepth > 0) return operation.run();
        Map<String, byte[]> before = new HashMap<>(values);
        changed = false;
        transactionDepth++;
        try {
            Result result = operation.run();
            if (changed) persist();
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

    private void persist() throws Exception {
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
    }

    static void deleteLegacyContentKey(String alias) throws Exception {
        if (!alias.startsWith(CONTENT) || alias.endsWith(".phone")) throw new SecurityException("Invalid legacy content alias");
        keyStore().deleteEntry(alias);
    }

    synchronized void migrateProtectedRecords(Map<String, String> aliases) throws Exception {
        Set<String> replaced = new HashSet<>();
        transaction(() -> {
            for (var record : aliases.entrySet()) {
                byte[] encrypted = get(record.getKey());
                if (encrypted == null || encrypted.length == 0 || encrypted[0] != 1) continue;
                String alias = record.getValue();
                if (!alias.startsWith(CONTENT) || alias.endsWith(".phone")) throw new SecurityException("Invalid legacy content alias");
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

    synchronized void saveAccount(UUID userId) throws Exception {
        String prefix = savedAccountPrefix(Objects.requireNonNull(userId));
        transaction(() -> {
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

    public synchronized void eraseAccount() throws Exception {
        requireOpen();
        if (transactionDepth != 0) throw new IllegalStateException("Account removal cannot run inside a transaction");
        try {
            KeyStore store = keyStore();
            List<String> aliases = Collections.list(store.aliases());
            for (String alias : aliases) if (alias.equals(MASTER) || alias.equals(phoneAlias(MASTER)) || alias.startsWith(CONTENT)) store.deleteEntry(alias);
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