package app.vanishr.crypto;

import org.junit.jupiter.api.Test;
import org.signal.libsignal.protocol.IdentityKeyPair;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SignalRuntimeTest {
    @Test
    void generatesIndependentDeviceIdentitiesWithTheOfficialNativeLibrary() throws Exception {
        IdentityKeyPair alice = IdentityKeyPair.generate();
        IdentityKeyPair bob = IdentityKeyPair.generate();

        assertNotEquals(alice.getPublicKey(), bob.getPublicKey());
        assertArrayEquals(alice.getPublicKey().serialize(),
                new IdentityKeyPair(alice.serialize()).getPublicKey().serialize());
    }

    @Test
    void signalRoundTripRequiresVerificationAndRejectsReplayAndSubstitution() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        SignalClient alice = new SignalClient(aliceId, new TestVault());
        SignalClient bob = new SignalClient(bobId, new TestVault());
        Instant now = Instant.now();
        PublicBundle bundle = bob.generatePreKey(now);

        assertThrows(SecurityException.class, () -> alice.establish(bobId, bundle, now));
        alice.verifyPeer(bobId, bob.publicIdentity());
        bob.verifyPeer(aliceId, alice.publicIdentity());
        alice.establish(bobId, bundle, now);
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        SignalClient.Packet packet = alice.encrypt(bobId, hello, now);
        assertFalse(new String(packet.ciphertext(), StandardCharsets.ISO_8859_1).contains("hello"));
        assertArrayEquals(hello, bob.decrypt(aliceId, packet));
        assertThrows(Exception.class, () -> bob.decrypt(aliceId, packet));

        SignalClient.Packet reply = bob.encrypt(aliceId, "received".getBytes(StandardCharsets.UTF_8), now);
        assertEquals("received", new String(alice.decrypt(bobId, reply), StandardCharsets.UTF_8));
        assertThrows(SecurityException.class, () -> alice.verifyPeer(bobId, IdentityKeyPair.generate().getPublicKey().serialize()));

        SignalClient.Packet next = alice.encrypt(bobId, hello, now);
        byte[] tampered = next.ciphertext().clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(Exception.class, () -> bob.decrypt(aliceId, new SignalClient.Packet(next.type(), tampered)));
        assertArrayEquals(hello, bob.decrypt(aliceId, next));
    }

    @Test
    void imageEncryptionUsesIndependentKeysAndAuthenticatesBytesAndIdentifier() throws Exception {
        UUID mediaId = UUID.randomUUID();
        byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        ImageCipher.EncryptedImage first = ImageCipher.encrypt(mediaId, image);
        ImageCipher.EncryptedImage second = ImageCipher.encrypt(mediaId, image);
        assertFalse(Arrays.equals(image, first.ciphertext()));
        assertFalse(Arrays.equals(first.key(), second.key()));
        assertArrayEquals(image, ImageCipher.decrypt(mediaId, first.ciphertext(), first.key(), first.nonce()));
        assertThrows(Exception.class, () -> ImageCipher.decrypt(UUID.randomUUID(), first.ciphertext(), first.key(), first.nonce()));
        first.ciphertext()[0] ^= 1;
        assertThrows(Exception.class, () -> ImageCipher.decrypt(mediaId, first.ciphertext(), first.key(), first.nonce()));
    }

        @Test
        void authenticatedEnvelopeRejectsExpiryExtensionAndContextSubstitution() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID senderDevice = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID recipientDevice = UUID.randomUUID();
        ChatEnvelope envelope = new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli(), ChatEnvelope.Expiry.HOUR_1, "hello", null);
        assertDoesNotThrow(() -> envelope.verify(id, sender, senderDevice, recipient, recipientDevice,
            envelope.expiry(), envelope.expiresAt(), null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(id, sender, senderDevice, recipient, recipientDevice,
            envelope.expiry(), envelope.expiresAt() + 1, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(UUID.randomUUID(), sender, senderDevice, recipient, recipientDevice,
            envelope.expiry(), envelope.expiresAt(), null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(id, sender, senderDevice, recipient, recipientDevice,
            envelope.expiry(), envelope.expiresAt(), null, now.plusSeconds(3600)));
        }

        static final class TestVault implements SecureVault {
        private Map<String, byte[]> values = new HashMap<>();
        public byte[] get(String name) { return values.get(name); }
        public void put(String name, byte[] value) { values.put(name, value.clone()); }
        public void remove(String name) { values.remove(name); }
        public List<String> names(String prefix) { return values.keySet().stream().filter(name -> name.startsWith(prefix)).collect(Collectors.toList()); }
        public <Result> Result transaction(Operation<Result> operation) throws Exception {
            Map<String, byte[]> before = new HashMap<>(values);
            try { return operation.run(); }
            catch (Exception failure) { values = before; throw failure; }
        }
    }
}