package app.vanishr.crypto;

import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.*;
import org.signal.libsignal.protocol.state.*;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

public final class SignalClient {
    public record Packet(int type, byte[] ciphertext) { }

    private final SecureVault vault;
    private final SignalStore store;
    private final SignalProtocolAddress localAddress;

    public SignalClient(UUID userId, SecureVault vault) throws Exception {
        this.vault = vault;
        this.store = new SignalStore(vault);
        this.localAddress = address(userId);
        vault.transaction(() -> {
            if (vault.get("identity") == null) {
                vault.put("identity", IdentityKeyPair.generate().serialize());
                vault.put("registration", integer(KeyHelper.generateRegistrationId(false)));
                vault.put("next-key", integer(new SecureRandom().nextInt(1_000_000) + 1));
            }
            return null;
        });
    }

    private static byte[] integer(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    private static SignalProtocolAddress address(UUID userId) { return new SignalProtocolAddress(userId.toString(), 1); }

    public byte[] publicIdentity() { return store.getIdentityKeyPair().getPublicKey().serialize(); }

    public SignalClient isolatedSession(SecureVault destination) throws Exception {
        if (destination == vault || !destination.names("").isEmpty()) throw new IllegalArgumentException("Session store must be empty");
        return vault.transaction(() -> {
            byte[] identity = store.getIdentityKeyPair().serialize();
            try {
                destination.transaction(() -> {
                    destination.put("identity", identity);
                    destination.put("registration", integer(store.getLocalRegistrationId()));
                    destination.put("next-key", integer(new SecureRandom().nextInt(1_000_000) + 1));
                    return null;
                });
                return new SignalClient(UUID.fromString(localAddress.getName()), destination);
            } finally { java.util.Arrays.fill(identity, (byte) 0); }
        });
    }

    public void verifyPeer(UUID peer, byte[] independentlyVerifiedIdentity) throws Exception {
        IdentityKey identity = new IdentityKey(independentlyVerifiedIdentity);
        vault.transaction(() -> {
            IdentityKey previous = store.getIdentity(address(peer));
            if (previous != null && !previous.equals(identity))
                throw new SecurityException("Identity changed; reset and independently reverify this contact");
            vault.put("peer/" + SignalStore.addressKey(address(peer)), identity.serialize());
            return null;
        });
    }

    public PublicBundle generatePreKey(Instant now) throws Exception {
        return vault.transaction(() -> {
            int keyId = Math.addExact(ByteBuffer.wrap(vault.get("next-key")).getInt(), 1);
            vault.put("next-key", integer(keyId));
            ECKeyPair preKey = ECKeyPair.generate();
            ECKeyPair signed = ECKeyPair.generate();
            KEMKeyPair kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            IdentityKeyPair identity = store.getIdentityKeyPair();
            byte[] signature = identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize());
            byte[] kyberSignature = identity.getPrivateKey().calculateSignature(kyber.getPublicKey().serialize());
            store.storePreKey(keyId, new PreKeyRecord(keyId, preKey));
            store.storeSignedPreKey(keyId, new SignedPreKeyRecord(keyId, now.toEpochMilli(), signed, signature));
            store.storeKyberPreKey(keyId, new KyberPreKeyRecord(keyId, now.toEpochMilli(), kyber, kyberSignature));
            return new PublicBundle(store.getLocalRegistrationId(), keyId, preKey.getPublicKey().serialize(),
                    keyId, signed.getPublicKey().serialize(), signature, publicIdentity(), keyId,
                    kyber.getPublicKey().serialize(), kyberSignature);
        });
    }

    public void prunePreKeys(Instant oldestAccepted) throws Exception {
        vault.transaction(() -> {
            for (SignedPreKeyRecord record : store.loadSignedPreKeys()) {
                if (record.getTimestamp() < oldestAccepted.toEpochMilli()) {
                    store.removeSignedPreKey(record.getId());
                    store.removePreKey(record.getId());
                    vault.remove("kyber/" + record.getId());
                }
            }
            return null;
        });
    }

    public boolean hasSession(UUID peer) { return store.containsSession(address(peer)); }

    public boolean isVerified(UUID peer) { return store.getIdentity(address(peer)) != null; }

    public void forgetPeer(UUID peer) throws Exception {
        vault.transaction(() -> {
            store.deleteAllSessions(peer.toString());
            vault.remove("peer/" + SignalStore.addressKey(address(peer)));
            return null;
        });
    }

    public void establish(UUID peer, PublicBundle bundle, Instant now) throws Exception {
        vault.transaction(() -> {
            if (!store.isTrustedIdentity(address(peer), new IdentityKey(bundle.identityKey()), IdentityKeyStore.Direction.SENDING))
                throw new SecurityException("Peer identity must be independently verified");
            new SessionBuilder(store, store, store, store, address(peer), localAddress).process(bundle.toSignal(), now);
            return null;
        });
    }

    private SessionCipher cipher(UUID peer) {
        return new SessionCipher(store, store, store, store, store, localAddress, address(peer));
    }

    public Packet encrypt(UUID peer, byte[] plaintext, Instant now) throws Exception {
        if (plaintext.length == 0 || plaintext.length > 32_768) throw new IllegalArgumentException("Invalid content size");
        return vault.transaction(() -> {
            CiphertextMessage encrypted = cipher(peer).encrypt(plaintext, now);
            return new Packet(encrypted.getType(), encrypted.serialize());
        });
    }

    public byte[] decrypt(UUID peer, Packet packet) throws Exception {
        if (packet.ciphertext().length > 65_536) throw new IllegalArgumentException("Invalid ciphertext size");
        return vault.transaction(() -> switch (packet.type()) {
            case CiphertextMessage.PREKEY_TYPE -> cipher(peer).decrypt(new PreKeySignalMessage(packet.ciphertext()));
            case CiphertextMessage.WHISPER_TYPE -> cipher(peer).decrypt(new SignalMessage(packet.ciphertext()));
            default -> throw new SecurityException("Unsupported encrypted message type");
        });
    }
}