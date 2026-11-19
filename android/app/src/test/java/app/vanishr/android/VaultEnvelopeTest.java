package app.vanishr.android;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** The file format of the protected store: what opens, what is refused, and that only the small key is handed to the Keystore. */
public class VaultEnvelopeTest {
    private static final String CONTEXT = "vanishr.vault.v1";

    /** Stands for the Keystore key: it wraps a data key with a key of its own and counts how often it is asked. */
    private static final class TestWrapper implements VaultEnvelope.KeyWrapper {
        final byte[] key;
        final AtomicInteger wraps = new AtomicInteger(), unwraps = new AtomicInteger();
        TestWrapper(int seed) { key = new byte[32]; new Random(seed).nextBytes(key); }
        @Override public byte[] wrap(byte[] dataKey) throws Exception {
            wraps.incrementAndGet();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] encrypted = cipher.doFinal(dataKey);
            byte[] result = new byte[12 + encrypted.length];
            System.arraycopy(cipher.getIV(), 0, result, 0, 12);
            System.arraycopy(encrypted, 0, result, 12, encrypted.length);
            return result;
        }
        @Override public byte[] unwrap(byte[] wrapped) throws Exception {
            unwraps.incrementAndGet();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, wrapped, 0, 12));
            return cipher.doFinal(wrapped, 12, wrapped.length - 12);
        }
    }

    private static byte[] bytes(int length, int seed) { byte[] value = new byte[length]; new Random(seed).nextBytes(value); return value; }

    private static byte[] sealed(TestWrapper wrapper, byte[] plaintext) throws Exception {
        byte[] dataKey = VaultEnvelope.newDataKey();
        return VaultEnvelope.seal(dataKey, wrapper.wrap(dataKey), CONTEXT, plaintext, plaintext.length);
    }

    @Test public void whatIsSealedOpensAgainAndGivesBackTheKeysForTheNextCommit() throws Exception {
        TestWrapper wrapper = new TestWrapper(1);
        byte[] plaintext = bytes(5_000, 2);
        byte[] file = sealed(wrapper, plaintext);
        assertTrue(VaultEnvelope.isEnvelope(file));
        assertEquals(VaultEnvelope.VERSION, file[0]);
        VaultEnvelope.Opened opened = VaultEnvelope.open(wrapper, CONTEXT, file);
        assertArrayEquals(plaintext, opened.plaintext());
        assertEquals(32, opened.dataKey().length);
        // The same keys seal the next commit, and the Keystore is not asked again for it.
        byte[] next = VaultEnvelope.seal(opened.dataKey(), opened.wrappedKey(), CONTEXT, plaintext, plaintext.length);
        assertEquals(1, wrapper.wraps.get());
        assertArrayEquals(plaintext, VaultEnvelope.open(wrapper, CONTEXT, next).plaintext());
        assertEquals("One unwrap for each time a file is opened", 2, wrapper.unwraps.get());
    }

    @Test public void theKeystoreIsAskedOnlyForTheSmallKeyWhateverTheSizeOfTheFile() throws Exception {
        TestWrapper wrapper = new TestWrapper(3);
        byte[] big = bytes(6 * 1024 * 1024, 4);
        byte[] file = sealed(wrapper, big);
        assertEquals(1, wrapper.wraps.get());
        assertEquals(big.length + 2 + 60 + 12 + 16, file.length);
        assertArrayEquals(big, VaultEnvelope.open(wrapper, CONTEXT, file).plaintext());
        assertEquals(1, wrapper.unwraps.get());
    }

    @Test public void eachSealUsesANewNonceSoTheSameContentsGiveDifferentFiles() throws Exception {
        TestWrapper wrapper = new TestWrapper(5);
        byte[] dataKey = VaultEnvelope.newDataKey(), wrapped = wrapper.wrap(dataKey);
        byte[] plaintext = bytes(100, 6);
        byte[] first = VaultEnvelope.seal(dataKey, wrapped, CONTEXT, plaintext, plaintext.length);
        byte[] second = VaultEnvelope.seal(dataKey, wrapped, CONTEXT, plaintext, plaintext.length);
        assertFalse(Arrays.equals(first, second));
        int nonce = 2 + wrapped.length;
        assertFalse("Not the same nonce", Arrays.equals(Arrays.copyOfRange(first, nonce, nonce + 12), Arrays.copyOfRange(second, nonce, nonce + 12)));
    }

    @Test public void theFileHoldsNothingReadable() throws Exception {
        TestWrapper wrapper = new TestWrapper(7);
        byte[] plaintext = "a very recognisable message about coffee at four".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String file = new String(sealed(wrapper, plaintext), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(file.contains("coffee"));
    }

    @Test public void aChangeToAnyByteOfTheFileIsRefused() throws Exception {
        TestWrapper wrapper = new TestWrapper(8);
        byte[] plaintext = bytes(300, 9);
        byte[] file = sealed(wrapper, plaintext);
        for (int index = 0; index < file.length; index++) {
            byte[] changed = file.clone();
            changed[index] ^= 0x01;
            int at = index;
            assertThrows("Byte " + at + " of " + file.length + " changed and the file still opened", Exception.class, () -> VaultEnvelope.open(wrapper, CONTEXT, changed));
        }
    }

    @Test public void aFileThatIsCutShortOrExtendedIsRefused() throws Exception {
        TestWrapper wrapper = new TestWrapper(10);
        byte[] file = sealed(wrapper, bytes(200, 11));
        for (int length = 0; length < file.length; length++) {
            byte[] cut = Arrays.copyOf(file, length);
            assertThrows("Cut to " + length, Exception.class, () -> VaultEnvelope.open(wrapper, CONTEXT, cut));
        }
        byte[] longer = Arrays.copyOf(file, file.length + 1);
        assertThrows(Exception.class, () -> VaultEnvelope.open(wrapper, CONTEXT, longer));
        assertThrows(SecurityException.class, () -> VaultEnvelope.open(wrapper, CONTEXT, null));
        assertThrows(SecurityException.class, () -> VaultEnvelope.open(wrapper, CONTEXT, new byte[0]));
    }

    @Test public void aFileOpensOnlyForTheContextItWasMadeFor() throws Exception {
        TestWrapper wrapper = new TestWrapper(12);
        byte[] file = sealed(wrapper, bytes(100, 13));
        assertThrows(GeneralSecurityException.class, () -> VaultEnvelope.open(wrapper, "vanishr.content.1", file));
        assertThrows(GeneralSecurityException.class, () -> VaultEnvelope.open(wrapper, "", file));
    }

    @Test public void aFileWrappedByAnotherKeystoreKeyDoesNotOpen() throws Exception {
        byte[] file = sealed(new TestWrapper(14), bytes(100, 15));
        assertThrows(GeneralSecurityException.class, () -> VaultEnvelope.open(new TestWrapper(16), CONTEXT, file));
    }

    @Test public void aWrapperThatGivesBackTheWrongKeyOrNoKeyIsRefused() throws Exception {
        TestWrapper real = new TestWrapper(17);
        byte[] file = sealed(real, bytes(100, 18));
        VaultEnvelope.KeyWrapper wrong = new VaultEnvelope.KeyWrapper() {
            @Override public byte[] wrap(byte[] dataKey) { return dataKey; }
            @Override public byte[] unwrap(byte[] wrapped) { return VaultEnvelope.newDataKey(); }
        };
        assertThrows(GeneralSecurityException.class, () -> VaultEnvelope.open(wrong, CONTEXT, file));
        VaultEnvelope.KeyWrapper none = new VaultEnvelope.KeyWrapper() {
            @Override public byte[] wrap(byte[] dataKey) { return dataKey; }
            @Override public byte[] unwrap(byte[] wrapped) { return null; }
        };
        assertThrows(SecurityException.class, () -> VaultEnvelope.open(none, CONTEXT, file));
        VaultEnvelope.KeyWrapper short16 = new VaultEnvelope.KeyWrapper() {
            @Override public byte[] wrap(byte[] dataKey) { return dataKey; }
            @Override public byte[] unwrap(byte[] wrapped) { return new byte[16]; }
        };
        assertThrows(SecurityException.class, () -> VaultEnvelope.open(short16, CONTEXT, file));
    }

    @Test public void theWrappedKeyIsReadBeforeAnythingElseAndIsNeverLongerThanTheFile() throws Exception {
        TestWrapper wrapper = new TestWrapper(19);
        byte[] file = sealed(wrapper, bytes(50, 20));
        byte[] lying = file.clone();
        lying[1] = (byte) 255;
        assertThrows(SecurityException.class, () -> VaultEnvelope.open(wrapper, CONTEXT, lying));
        lying[1] = 0;
        assertThrows(SecurityException.class, () -> VaultEnvelope.open(wrapper, CONTEXT, lying));
        assertEquals("The wrapper is not called for a header that cannot be right", 0, wrapper.unwraps.get());
    }

    @Test public void anOlderOrUnknownVersionIsNotOpenedAsThisOne() throws Exception {
        TestWrapper wrapper = new TestWrapper(21);
        byte[] file = sealed(wrapper, bytes(50, 22));
        for (int version : new int[]{0, 1, 2, 3, 5, 255}) {
            byte[] other = file.clone();
            other[0] = (byte) version;
            assertFalse(VaultEnvelope.isEnvelope(other));
            assertThrows(SecurityException.class, () -> VaultEnvelope.open(wrapper, CONTEXT, other));
        }
    }

    @Test public void anEmptyStoreAndOnlyTheFirstBytesOfABufferAreSealed() throws Exception {
        TestWrapper wrapper = new TestWrapper(23);
        byte[] dataKey = VaultEnvelope.newDataKey(), wrapped = wrapper.wrap(dataKey);
        byte[] empty = VaultEnvelope.seal(dataKey, wrapped, CONTEXT, new byte[0], 0);
        assertEquals(0, VaultEnvelope.open(wrapper, CONTEXT, empty).plaintext().length);
        byte[] buffer = bytes(1_000, 24);
        byte[] part = VaultEnvelope.seal(dataKey, wrapped, CONTEXT, buffer, 100);
        assertArrayEquals(Arrays.copyOf(buffer, 100), VaultEnvelope.open(wrapper, CONTEXT, part).plaintext());
        assertThrows(IllegalArgumentException.class, () -> VaultEnvelope.seal(dataKey, wrapped, CONTEXT, buffer, 1_001));
        assertThrows(IllegalArgumentException.class, () -> VaultEnvelope.seal(dataKey, wrapped, CONTEXT, buffer, -1));
        assertThrows(IllegalArgumentException.class, () -> VaultEnvelope.seal(new byte[16], wrapped, CONTEXT, buffer, 10));
        assertThrows(IllegalArgumentException.class, () -> VaultEnvelope.seal(dataKey, new byte[0], CONTEXT, buffer, 10));
        assertThrows(IllegalArgumentException.class, () -> VaultEnvelope.seal(dataKey, new byte[256], CONTEXT, buffer, 10));
    }

    @Test public void theLargestStoreSealsAndOpensInWellUnderTheTimeTheKeystoreNeededForOneMegabyte() throws Exception {
        TestWrapper wrapper = new TestWrapper(25);
        byte[] big = bytes(32 * 1024 * 1024, 26);
        byte[] dataKey = VaultEnvelope.newDataKey(), wrapped = wrapper.wrap(dataKey);
        long started = System.nanoTime();
        byte[] file = VaultEnvelope.seal(dataKey, wrapped, CONTEXT, big, big.length);
        byte[] opened = VaultEnvelope.open(wrapper, CONTEXT, file).plaintext();
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertArrayEquals(big, opened);
        // The Keystore took about 250 ms for each megabyte on the test emulator. This is 64 MB of encryption and decryption.
        assertTrue("Sealing and opening 32 MB took " + millis + " ms", millis < 5_000);
        assertTrue(file.length <= big.length + VaultEnvelope.MAXIMUM_OVERHEAD);
    }
}
