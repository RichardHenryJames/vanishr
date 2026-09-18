package app.vanishr.crypto;

import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SignalStore implements IdentityKeyStore, PreKeyStore, SignedPreKeyStore,
        SessionStore, KyberPreKeyStore {
    private final SecureVault vault;

    SignalStore(SecureVault vault) { this.vault = vault; }

    private byte[] required(String name) {
        byte[] value = vault.get(name);
        if (value == null) throw new IllegalStateException("Required private state is unavailable");
        return value;
    }

    static String addressKey(SignalProtocolAddress address) {
        return address.getName() + "/" + address.getDeviceId();
    }

    @Override public IdentityKeyPair getIdentityKeyPair() {
        try { return new IdentityKeyPair(required("identity")); }
        catch (Exception failure) { throw new IllegalStateException("Private identity is unavailable"); }
    }

    @Override public int getLocalRegistrationId() {
        return ByteBuffer.wrap(required("registration")).getInt();
    }

    @Override public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identity) {
        if (!isTrustedIdentity(address, identity, Direction.SENDING))
            throw new SecurityException("Peer identity must be independently verified");
        return IdentityChange.NEW_OR_UNCHANGED;
    }

    @Override public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identity, Direction direction) {
        return identity.equals(getIdentity(address));
    }

    @Override public IdentityKey getIdentity(SignalProtocolAddress address) {
        byte[] value = vault.get("peer/" + addressKey(address));
        if (value == null) return null;
        try { return new IdentityKey(value); }
        catch (Exception failure) { throw new IllegalStateException("Stored peer identity is corrupt"); }
    }

    @Override public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        try { return new PreKeyRecord(required("pre/" + id)); }
        catch (Exception failure) { throw new InvalidKeyIdException("Prekey unavailable"); }
    }

    @Override public void storePreKey(int id, PreKeyRecord record) { vault.put("pre/" + id, record.serialize()); }
    @Override public boolean containsPreKey(int id) { return vault.get("pre/" + id) != null; }
    @Override public void removePreKey(int id) { vault.remove("pre/" + id); }

    @Override public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        try { return new SignedPreKeyRecord(required("signed/" + id)); }
        catch (Exception failure) { throw new InvalidKeyIdException("Signed prekey unavailable"); }
    }

    @Override public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> result = new ArrayList<>();
        for (String name : vault.names("signed/")) {
            try { result.add(new SignedPreKeyRecord(required(name))); }
            catch (Exception failure) { throw new IllegalStateException("Stored prekey is corrupt"); }
        }
        return result;
    }

    @Override public void storeSignedPreKey(int id, SignedPreKeyRecord record) { vault.put("signed/" + id, record.serialize()); }
    @Override public boolean containsSignedPreKey(int id) { return vault.get("signed/" + id) != null; }
    @Override public void removeSignedPreKey(int id) { vault.remove("signed/" + id); }

    @Override public SessionRecord loadSession(SignalProtocolAddress address) {
        byte[] value = vault.get("session/" + addressKey(address));
        if (value == null) return new SessionRecord();
        try { return new SessionRecord(value); }
        catch (Exception failure) { throw new IllegalStateException("Stored session is corrupt"); }
    }

    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) {
        List<SessionRecord> result = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            if (!containsSession(address)) throw new IllegalStateException("Session unavailable");
            result.add(loadSession(address));
        }
        return result;
    }

    @Override public List<Integer> getSubDeviceSessions(String name) { return Collections.emptyList(); }
    @Override public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        vault.put("session/" + addressKey(address), record.serialize());
    }
    @Override public boolean containsSession(SignalProtocolAddress address) { return vault.get("session/" + addressKey(address)) != null; }
    @Override public void deleteSession(SignalProtocolAddress address) { vault.remove("session/" + addressKey(address)); }
    @Override public void deleteAllSessions(String name) {
        for (String key : vault.names("session/" + name + "/")) vault.remove(key);
    }

    @Override public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        try { return new KyberPreKeyRecord(required("kyber/" + id)); }
        catch (Exception failure) { throw new InvalidKeyIdException("Post-quantum prekey unavailable"); }
    }

    @Override public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> result = new ArrayList<>();
        for (String name : vault.names("kyber/")) {
            try { result.add(new KyberPreKeyRecord(required(name))); }
            catch (Exception failure) { throw new IllegalStateException("Stored prekey is corrupt"); }
        }
        return result;
    }

    @Override public void storeKyberPreKey(int id, KyberPreKeyRecord record) { vault.put("kyber/" + id, record.serialize()); }
    @Override public boolean containsKyberPreKey(int id) { return vault.get("kyber/" + id) != null; }
    @Override public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey baseKey) {
        vault.remove("kyber/" + id);
    }
}