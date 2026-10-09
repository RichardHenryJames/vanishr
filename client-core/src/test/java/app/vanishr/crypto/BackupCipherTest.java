package app.vanishr.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackupCipherTest {
    private final UUID user = UUID.randomUUID();

    private static byte[] bytes(int length) {
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        return value;
    }

    @Test
    void recoveryKeysAreRandomGroupedAndRoundTripWithOnlyFormattingTolerance() {
        byte[] key = BackupCipher.newRecoveryKey();
        assertEquals(BackupCipher.KEY_BYTES, key.length);
        assertFalse(Arrays.equals(key, BackupCipher.newRecoveryKey()));
        String text = BackupCipher.formatRecoveryKey(key);
        assertTrue(text.matches("([A-Z2-7]{4}-){12}[A-Z2-7]{4}"), text);
        assertArrayEquals(key, BackupCipher.parseRecoveryKey(text));
        assertArrayEquals(key, BackupCipher.parseRecoveryKey("  " + text.toLowerCase() + "\n"));
        assertArrayEquals(key, BackupCipher.parseRecoveryKey(text.replace('-', ' ')));
        assertArrayEquals(key, BackupCipher.parseRecoveryKey(text.replace("-", "")));
        for (byte[] edge : new byte[][]{new byte[32], new byte[]{-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}})
            assertArrayEquals(edge, BackupCipher.parseRecoveryKey(BackupCipher.formatRecoveryKey(edge)));
    }

    @Test
    void malformedRecoveryKeysAreRejected() {
        String valid = BackupCipher.formatRecoveryKey(bytes(32));
        String compact = valid.replace("-", "");
        for (String invalid : new String[]{null, "", valid.substring(0, valid.length() - 1), valid + "A", compact + "AA",
                valid.replace('A', '1').replace('B', '0').replace('C', '8') + "1", "ß".repeat(26), compact.substring(1) + "\u0661",
                valid.replace("-", "_"), "\u0000".repeat(52), "A".repeat(500)})
            assertThrows(IllegalArgumentException.class, () -> BackupCipher.parseRecoveryKey(invalid), String.valueOf(invalid));
        // The last character holds four padding bits that must be zero, so each key has one canonical spelling.
        assertTrue(compact.endsWith("A") || compact.endsWith("Q"), compact);
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.parseRecoveryKey(compact.substring(0, 51) + "B"));
    }

    @Test
    void sealedBackupsRoundTripAndEachSealUsesFreshRandomness() throws Exception {
        byte[] key = BackupCipher.newRecoveryKey();
        byte[] plaintext = "{\"v\":1,\"contacts\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] first = BackupCipher.seal(key, user, plaintext), second = BackupCipher.seal(key, user, plaintext);
        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(plaintext, BackupCipher.open(key, user, first));
        assertArrayEquals(plaintext, BackupCipher.open(key, user, second));
        assertEquals(plaintext.length + 37 + 12 + 16, first.length);
        assertEquals('V', first[0]);
        assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains("contacts"));
    }

    @Test
    void wrongKeyWrongAccountAndAnyModifiedByteFailClosedWithOneGenericError() throws Exception {
        byte[] key = BackupCipher.newRecoveryKey();
        byte[] blob = BackupCipher.seal(key, user, bytes(300));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(BackupCipher.newRecoveryKey(), user, blob));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, UUID.randomUUID(), blob));
        for (int index = 0; index < blob.length; index++) {
            byte[] changed = blob.clone();
            changed[index] ^= 1;
            GeneralSecurityException failure = assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, user, changed),
                    "byte " + index);
            assertEquals("Invalid backup", failure.getMessage());
        }
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, user, Arrays.copyOf(blob, blob.length - 1)));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, user, Arrays.copyOf(blob, 40)));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, user, new byte[0]));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, user, null));
        assertThrows(GeneralSecurityException.class, () -> BackupCipher.open(key, user, new byte[BackupCipher.MAX_BLOB_BYTES + 1]));
    }

    @Test
    void sizeBoundsAreEnforcedExactlyAtTheRelayLimit() throws Exception {
        byte[] key = BackupCipher.newRecoveryKey();
        byte[] maximum = bytes(BackupCipher.MAX_PLAINTEXT_BYTES);
        byte[] blob = BackupCipher.seal(key, user, maximum);
        assertEquals(BackupCipher.MAX_BLOB_BYTES, blob.length);
        assertArrayEquals(maximum, BackupCipher.open(key, user, blob));
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.seal(key, user, new byte[BackupCipher.MAX_PLAINTEXT_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.seal(key, user, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.seal(new byte[31], user, bytes(8)));
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.seal(key, null, bytes(8)));
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.open(new byte[33], user, blob));
    }

    @Test
    void derivationMatchesRfc5869Vectors() throws Exception {
        HexFormat hex = HexFormat.of();
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                hex.formatHex(BackupCipher.hkdf(hex.parseHex("0b".repeat(22)), hex.parseHex("000102030405060708090a0b0c"),
                        hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42)));
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                hex.formatHex(BackupCipher.hkdf(hex.parseHex("0b".repeat(22)), new byte[0], new byte[0], 42)));
        assertThrows(IllegalArgumentException.class, () -> BackupCipher.hkdf(new byte[32], new byte[0], new byte[0], 0));
    }
}
