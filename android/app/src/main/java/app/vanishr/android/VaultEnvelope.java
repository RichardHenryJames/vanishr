package app.vanishr.android;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The file format of the protected store from version 4 on.
 *
 * <p>The store is one file that is written whole at every commit. Until version 3 that file was encrypted by a hardware-backed Android
 * Keystore key, and every Keystore call moves its data in small pieces through the keystore service: a megabyte took about a quarter of a
 * second to encrypt on the test emulator, so each message, receipt or reaction cost more the more the phone held, and opening the store
 * cost the same again. Now the file is encrypted in this process with AES-256-GCM under a random data key, and only that 32-byte data key
 * is protected by the Keystore key: it is stored in the file's header, encrypted ("wrapped") by the same Keystore key as before, and is
 * unwrapped once when the store is opened. Without the Keystore key (which cannot be exported, and is deleted with the account) the file
 * cannot be read, as before. The data key is held in memory only while the store is open, where all of the store's plaintext is anyway.
 *
 * <p>Layout: {@code version (1 byte) | wrapped key length (1 byte) | wrapped key | nonce (12 bytes) | ciphertext and 16-byte tag}.
 * The header, and a context that names what the file is, are authenticated together with the contents, so that a changed header, or a file
 * moved from where it belongs, fails to open instead of opening as something else.
 *
 * <p>Nothing here knows about Android: the Keystore is reached through {@link KeyWrapper}, so the format can be tested on a development
 * machine.
 */
final class VaultEnvelope {
    static final byte VERSION = 4;
    static final int KEY_BYTES = 32;
    /** The most a file can add to what it holds: the header, the nonce and the tag. */
    static final int MAXIMUM_OVERHEAD = 2 + 255 + 12 + 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Protects a data key with a key that never leaves the Android Keystore. */
    interface KeyWrapper {
        byte[] wrap(byte[] dataKey) throws Exception;
        byte[] unwrap(byte[] wrapped) throws Exception;
    }

    /** What an opened file gives: its contents, and the data key and wrapped key to use for the next one. The caller wipes the arrays. */
    record Opened(byte[] plaintext, byte[] dataKey, byte[] wrappedKey) { }

    private VaultEnvelope() { }

    static byte[] newDataKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    /** Whether the bytes start as a file of this version; it says nothing about whether the file can be opened. */
    static boolean isEnvelope(byte[] stored) { return stored != null && stored.length > 0 && stored[0] == VERSION; }

    /** The first {@code length} bytes of {@code plaintext}, encrypted under {@code dataKey}; each call uses a new random nonce. */
    static byte[] seal(byte[] dataKey, byte[] wrappedKey, String context, byte[] plaintext, int length) throws GeneralSecurityException {
        if (dataKey == null || dataKey.length != KEY_BYTES) throw new IllegalArgumentException("A 256-bit data key is required");
        if (wrappedKey == null || wrappedKey.length == 0 || wrappedKey.length > 255) throw new IllegalArgumentException("Invalid wrapped key");
        if (length < 0 || length > plaintext.length) throw new IllegalArgumentException("Invalid length");
        byte[] header = new byte[2 + wrappedKey.length];
        header[0] = VERSION;
        header[1] = (byte) wrappedKey.length;
        System.arraycopy(wrappedKey, 0, header, 2, wrappedKey.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"));
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != NONCE_BYTES) throw new GeneralSecurityException("Unexpected nonce size");
        cipher.updateAAD(authenticated(context, header, header.length));
        byte[] sealed = new byte[header.length + NONCE_BYTES + length + TAG_BYTES];
        System.arraycopy(header, 0, sealed, 0, header.length);
        System.arraycopy(nonce, 0, sealed, header.length, NONCE_BYTES);
        int written = cipher.doFinal(plaintext, 0, length, sealed, header.length + NONCE_BYTES);
        if (written != length + TAG_BYTES) throw new GeneralSecurityException("Unexpected ciphertext size");
        return sealed;
    }

    /**
     * Opens a file made by {@link #seal}. Anything that is not exactly such a file, or that was changed, or that belongs to another
     * context, is refused; the wrapper is asked for the data key once.
     */
    static Opened open(KeyWrapper wrapper, String context, byte[] sealed) throws Exception {
        if (sealed == null || sealed.length < 2 + 1 + NONCE_BYTES + TAG_BYTES || sealed[0] != VERSION) throw new SecurityException("Invalid protected record");
        int wrappedLength = sealed[1] & 0xFF;
        int headerLength = 2 + wrappedLength;
        if (wrappedLength == 0 || sealed.length < headerLength + NONCE_BYTES + TAG_BYTES) throw new SecurityException("Invalid protected record");
        byte[] wrapped = Arrays.copyOfRange(sealed, 2, headerLength);
        byte[] dataKey = wrapper.unwrap(wrapped);
        try {
            if (dataKey == null || dataKey.length != KEY_BYTES) throw new SecurityException("Invalid protected record");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_BYTES * 8, sealed, headerLength, NONCE_BYTES));
            cipher.updateAAD(authenticated(context, sealed, headerLength));
            byte[] plaintext = cipher.doFinal(sealed, headerLength + NONCE_BYTES, sealed.length - headerLength - NONCE_BYTES);
            return new Opened(plaintext, dataKey, wrapped);
        } catch (Exception failure) {
            if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
            throw failure;
        }
    }

    // What is authenticated besides the contents: what the file is, and its header.
    private static byte[] authenticated(String context, byte[] header, int headerLength) {
        byte[] name = context.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[name.length + 1 + headerLength];
        System.arraycopy(name, 0, result, 0, name.length);
        System.arraycopy(header, 0, result, name.length + 1, headerLength);
        return result;
    }
}
