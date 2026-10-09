package app.vanishr.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Client-side encryption for the opt-in account backup. The recovery key is 256 random bits held only by the user
 * (and, after setup, the phone's protected vault); the relay stores the sealed blob and never sees the key.
 * HKDF-SHA256 and AES-256-GCM are standard primitives; this is not a messaging protocol.
 */
public final class BackupCipher {
    public static final int KEY_BYTES = 32;
    public static final int MAX_BLOB_BYTES = 512 * 1024;
    static final byte[] MAGIC = {'V', 'B', 'K', '1'};
    static final int VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int HEADER_BYTES = MAGIC.length + 1 + 16 + SALT_BYTES;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int KEY_TEXT_CHARACTERS = 52;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    public static final int MAX_PLAINTEXT_BYTES = MAX_BLOB_BYTES - HEADER_BYTES - NONCE_BYTES - TAG_BYTES;
    private static final byte[] INFO = "vanishr/backup/v1".getBytes(StandardCharsets.US_ASCII);

    private BackupCipher() { }

    public static byte[] newRecoveryKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** Groups of four letters/digits, for example ABCD-EFGH-..., to make manual copying less error-prone. */
    public static String formatRecoveryKey(byte[] key) {
        requireKey(key);
        String text = base32(key);
        StringBuilder grouped = new StringBuilder(text.length() + text.length() / 4);
        for (int index = 0; index < text.length(); index += 4) {
            if (index > 0) grouped.append('-');
            grouped.append(text, index, index + 4);
        }
        return grouped.toString();
    }

    /** Accepts ASCII case, whitespace and hyphens only as formatting; any other deviation is rejected. */
    public static byte[] parseRecoveryKey(String text) {
        if (text == null || text.length() > 128) throw new IllegalArgumentException("Invalid recovery key");
        byte[] key = new byte[KEY_BYTES];
        int buffer = 0, bits = 0, index = 0, characters = 0;
        for (int position = 0; position < text.length(); position++) {
            char character = text.charAt(position);
            if (character == '-' || Character.isWhitespace(character)) continue;
            if (character >= 'a' && character <= 'z') character -= 'a' - 'A';
            int value = ALPHABET.indexOf(character);
            if (value < 0 || ++characters > KEY_TEXT_CHARACTERS) { Arrays.fill(key, (byte) 0); throw new IllegalArgumentException("Invalid recovery key"); }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                key[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        // The last character carries four padding bits, which must be zero in the canonical encoding.
        if (characters != KEY_TEXT_CHARACTERS || index != KEY_BYTES || buffer != 0) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalArgumentException("Invalid recovery key");
        }
        return key;
    }

    public static byte[] seal(byte[] recoveryKey, UUID userId, byte[] plaintext) throws GeneralSecurityException {
        requireKey(recoveryKey);
        if (userId == null || plaintext == null || plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES)
            throw new IllegalArgumentException("Invalid backup size");
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES], nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] header = header(userId, salt);
        byte[] key = derive(recoveryKey, userId, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(HEADER_BYTES + NONCE_BYTES + encrypted.length).put(header).put(nonce).put(encrypted).array();
        } finally { Arrays.fill(key, (byte) 0); }
    }

    /** Every failure, including a wrong key, another account's blob or tampering, is the same generic exception. */
    public static byte[] open(byte[] recoveryKey, UUID userId, byte[] blob) throws GeneralSecurityException {
        requireKey(recoveryKey);
        if (userId == null || blob == null || blob.length < HEADER_BYTES + NONCE_BYTES + TAG_BYTES + 1 || blob.length > MAX_BLOB_BYTES)
            throw new GeneralSecurityException("Invalid backup");
        byte[] header = Arrays.copyOf(blob, HEADER_BYTES);
        byte[] salt = Arrays.copyOfRange(header, HEADER_BYTES - SALT_BYTES, HEADER_BYTES);
        if (!MessageDigest.isEqual(header, header(userId, salt))) throw new GeneralSecurityException("Invalid backup");
        byte[] key = derive(recoveryKey, userId, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BYTES * 8, blob, HEADER_BYTES, NONCE_BYTES));
            cipher.updateAAD(header);
            return cipher.doFinal(blob, HEADER_BYTES + NONCE_BYTES, blob.length - HEADER_BYTES - NONCE_BYTES);
        } catch (GeneralSecurityException failure) { throw new GeneralSecurityException("Invalid backup"); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    private static byte[] header(UUID userId, byte[] salt) {
        return ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put((byte) VERSION)
                .putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits()).put(salt).array();
    }

    private static byte[] derive(byte[] recoveryKey, UUID userId, byte[] salt) throws GeneralSecurityException {
        byte[] info = ByteBuffer.allocate(INFO.length + 16).put(INFO)
                .putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits()).array();
        return hkdf(recoveryKey, salt, info, KEY_BYTES);
    }

    // RFC 5869 HKDF with HMAC-SHA-256.
    static byte[] hkdf(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        if (length < 1 || length > 255 * 32) throw new IllegalArgumentException("Invalid derived key size");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(inputKeyMaterial);
        byte[] output = new byte[length];
        byte[] block = new byte[0];
        try {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            for (int offset = 0, counter = 1; offset < length; offset += block.length, counter++) {
                mac.update(block);
                mac.update(info);
                mac.update((byte) counter);
                block = mac.doFinal();
                System.arraycopy(block, 0, output, offset, Math.min(block.length, length - offset));
            }
            return output;
        } finally {
            Arrays.fill(pseudoRandomKey, (byte) 0);
            Arrays.fill(block, (byte) 0);
        }
    }

    private static String base32(byte[] data) {
        StringBuilder text = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                text.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) text.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return text.toString();
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) throw new IllegalArgumentException("Invalid recovery key");
    }
}
