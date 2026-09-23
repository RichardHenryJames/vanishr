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
    void officialGroupSenderKeysRejectUnknownSendersReplayTamperingAndOldMembershipEpochs() throws Exception {
        UUID group = UUID.randomUUID(); UUID epoch = UUID.randomUUID();
        UUID aliceId = UUID.randomUUID(); UUID bobId = UUID.randomUUID();
        TestVault aliceVault = new TestVault(); TestVault bobVault = new TestVault();
        SignalGroup alice = new SignalGroup(aliceVault, group, epoch, aliceId);
        SignalGroup bob = new SignalGroup(bobVault, group, epoch, bobId);
        byte[] distribution = alice.distribution();
        byte[] plaintext = "private group message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = alice.encrypt(plaintext);
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("private group message"));
        assertThrows(Exception.class, () -> bob.decrypt(aliceId, encrypted));
        bob.accept(aliceId, distribution);
        assertThrows(Exception.class, () -> bob.decrypt(UUID.randomUUID(), encrypted));
        byte[] tampered = encrypted.clone(); tampered[tampered.length - 1] ^= 1;
        assertThrows(Exception.class, () -> bob.decrypt(aliceId, tampered));
        assertArrayEquals(plaintext, bob.decrypt(aliceId, encrypted));
        assertThrows(Exception.class, () -> bob.decrypt(aliceId, encrypted));
        UUID nextEpoch = UUID.randomUUID();
        SignalGroup updatedAlice = new SignalGroup(aliceVault, group, nextEpoch, aliceId);
        byte[] nextDistribution = updatedAlice.distribution();
        byte[] afterRemoval = updatedAlice.encrypt(plaintext);
        assertThrows(SecurityException.class, () -> bob.accept(aliceId, nextDistribution));
        assertThrows(SecurityException.class, () -> bob.decrypt(aliceId, afterRemoval));
        SignalGroup newMember = new SignalGroup(new TestVault(), group, nextEpoch, UUID.randomUUID());
        newMember.accept(aliceId, nextDistribution);
        assertArrayEquals(plaintext, newMember.decrypt(aliceId, afterRemoval));
        assertThrows(SecurityException.class, () -> newMember.decrypt(aliceId, encrypted));
    }

    @Test
    void oneOfficialSenderKeyCiphertextReachesAllOtherMembersOfA200MemberGroup() throws Exception {
        UUID group = UUID.randomUUID(); UUID epoch = UUID.randomUUID(); UUID sender = UUID.randomUUID();
        SignalGroup sending = new SignalGroup(new TestVault(), group, epoch, sender);
        byte[] distribution = sending.distribution();
        byte[] plaintext = "two hundred members".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = sending.encrypt(plaintext);
        for (int member = 1; member < 200; member++) {
            SignalGroup recipient = new SignalGroup(new TestVault(), group, epoch, UUID.randomUUID());
            recipient.accept(sender, distribution);
            assertArrayEquals(plaintext, recipient.decrypt(sender, ciphertext));
        }
    }

    @Test
    void groupEnvelopeBindsMembershipSenderMediaAndOriginalDeadline() {
        Instant now = Instant.now();
        UUID group = UUID.randomUUID(); UUID epoch = UUID.randomUUID(); UUID id = UUID.randomUUID();
        UUID sender = UUID.randomUUID(); UUID device = UUID.randomUUID();
        long deadline = now.plusSeconds(3600).toEpochMilli();
        GroupEnvelope envelope = new GroupEnvelope(1, group, epoch, 3, new ChatEnvelope(1, id, sender, device,
                group, epoch, now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOUR_1, "group content", null));
        assertDoesNotThrow(() -> envelope.verify(group, epoch, 3, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(UUID.randomUUID(), epoch, 3, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(group, UUID.randomUUID(), 3, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(group, epoch, 4, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(group, epoch, 3, id, UUID.randomUUID(), device, ChatEnvelope.Expiry.HOUR_1, deadline, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(group, epoch, 3, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline + 1, null, now));
        assertThrows(SecurityException.class, () -> envelope.verify(group, epoch, 3, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline, UUID.randomUUID(), now));
        assertThrows(SecurityException.class, () -> envelope.verify(group, epoch, 3, id, sender, device, ChatEnvelope.Expiry.HOUR_1, deadline, null, now.plusSeconds(3600)));
    }

        @Test
        void profilePhotosBindVerifiedPartiesRequestAndDeadline() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID(); UUID sender = UUID.randomUUID(); UUID senderDevice = UUID.randomUUID();
        UUID recipient = UUID.randomUUID(); UUID recipientDevice = UUID.randomUUID();
        long deadline = now.plusSeconds(3600).toEpochMilli();
        ChatEnvelope context = new ChatEnvelope(1, id, sender, senderDevice, recipient, recipientDevice,
            now.toEpochMilli(), deadline, ChatEnvelope.Expiry.HOURS_24, "profile-photo", null);
        ProfileEnvelope request = new ProfileEnvelope(1, ProfileEnvelope.Action.REQUEST, id, 0, null, context);
        assertDoesNotThrow(() -> request.verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now));
        ProfileEnvelope update = new ProfileEnvelope(1, ProfileEnvelope.Action.UPDATE, UUID.randomUUID(), 1, new byte[]{1}, context);
        assertDoesNotThrow(() -> update.verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now));
        assertThrows(SecurityException.class, () -> update.verify(id, sender, senderDevice, UUID.randomUUID(), recipientDevice, deadline, now));
        assertThrows(SecurityException.class, () -> update.verify(id, sender, UUID.randomUUID(), recipient, recipientDevice, deadline, now));
        assertThrows(SecurityException.class, () -> update.verify(id, sender, senderDevice, recipient, recipientDevice, deadline + 1, now));
        assertThrows(SecurityException.class, () -> update.verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now.plusSeconds(3600)));
        assertThrows(SecurityException.class, () -> new ProfileEnvelope(1, ProfileEnvelope.Action.REQUEST,
            UUID.randomUUID(), 0, null, context).verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now));
        assertThrows(SecurityException.class, () -> new ProfileEnvelope(1, ProfileEnvelope.Action.UPDATE,
            UUID.randomUUID(), 1, new byte[ProfileEnvelope.MAX_PHOTO_BYTES + 1], context)
            .verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now));
        assertDoesNotThrow(() -> new ProfileEnvelope(1, ProfileEnvelope.Action.UPDATE, UUID.randomUUID(), 2, null, context)
            .verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now));
        assertThrows(SecurityException.class, () -> new ProfileEnvelope(1, ProfileEnvelope.Action.REVOKE, id, 0, new byte[]{1}, context)
            .verify(id, sender, senderDevice, recipient, recipientDevice, deadline, now));
        }

        @Test
        void authenticatedFreshDistributionKeepsUnreadMessagesAndReplayProtection() throws Exception {
        UUID group=UUID.randomUUID(); UUID epoch=UUID.randomUUID(); UUID owner=UUID.randomUUID();
        SignalGroup sender=new SignalGroup(new TestVault(),group,epoch,owner);
        SignalGroup receiver=new SignalGroup(new TestVault(),group,epoch,UUID.randomUUID());
        receiver.accept(owner,sender.distribution());
        byte[] unread=sender.encrypt("unread".getBytes(StandardCharsets.UTF_8));
        for (int index=0;index<2100;index++) sender.encrypt(new byte[]{1,2,3});
        receiver.accept(owner,sender.distribution());
        assertArrayEquals("unread".getBytes(StandardCharsets.UTF_8),receiver.decrypt(owner,unread));
        byte[] current=sender.encrypt("current".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("current".getBytes(StandardCharsets.UTF_8),receiver.decrypt(owner,current));
        assertThrows(Exception.class,() -> receiver.decrypt(owner,current));
    }

    @Test
    void ownerApprovedRosterDigestBindsEveryMemberKeyAndIsOrderIndependent() {
        UUID group=UUID.randomUUID(); UUID owner=UUID.randomUUID(); UUID epoch=UUID.randomUUID();
        String key=Base64.getEncoder().encodeToString(IdentityKeyPair.generate().getPublicKey().serialize());
        GroupRoster.Member first=new GroupRoster.Member(owner,UUID.randomUUID(),key);
        GroupRoster.Member second=new GroupRoster.Member(UUID.randomUUID(),UUID.randomUUID(),key);
        GroupRoster roster=new GroupRoster(group,owner,epoch,1,List.of(first,second));
        assertEquals(roster.digest(),new GroupRoster(group,owner,epoch,1,List.of(second,first)).digest());
        assertNotEquals(roster.digest(),new GroupRoster(group,owner,epoch,1,List.of(first)).digest());
        assertNotEquals(roster.digest(),new GroupRoster(group,owner,epoch,2,List.of(first,second)).digest());
        String changed=Base64.getEncoder().encodeToString(IdentityKeyPair.generate().getPublicKey().serialize());
        assertNotEquals(roster.digest(),new GroupRoster(group,owner,epoch,1,List.of(first,new GroupRoster.Member(second.userId(),second.deviceId(),changed))).digest());
        assertThrows(SecurityException.class,() -> new GroupRoster(group,owner,epoch,1,List.of(first,first)));
        assertThrows(SecurityException.class,() -> new GroupRoster(group,owner,epoch,1,List.of(second)));
        assertThrows(SecurityException.class,() -> roster.member(UUID.randomUUID()));
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