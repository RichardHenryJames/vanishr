package app.vanishr.crypto;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;

public record PublicBundle(int registrationId, int preKeyId, byte[] preKey,
                           int signedPreKeyId, byte[] signedPreKey, byte[] signedPreKeySignature,
                           byte[] identityKey, int kyberPreKeyId, byte[] kyberPreKey,
                           byte[] kyberPreKeySignature) {
    public PreKeyBundle toSignal() throws Exception {
        return new PreKeyBundle(registrationId, 1, preKeyId, new ECPublicKey(preKey),
                signedPreKeyId, new ECPublicKey(signedPreKey), signedPreKeySignature,
                new IdentityKey(identityKey), kyberPreKeyId, new KEMPublicKey(kyberPreKey),
                kyberPreKeySignature);
    }
}