package app.vanishr.crypto;

import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.groups.state.SenderKeyStore;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.protocol.message.SenderKeyMessage;

import java.util.Objects;
import java.util.UUID;

public final class SignalGroup {
    private final SecureVault vault;
    private final UUID epoch;
    private final SignalProtocolAddress local;
    private final SenderKeyStore store;

    public SignalGroup(SecureVault vault, UUID groupId, UUID epoch, UUID localUser) {
        this.vault = Objects.requireNonNull(vault);
        this.epoch = Objects.requireNonNull(epoch);
        this.local = address(Objects.requireNonNull(localUser));
        String prefix = "group-signal/" + Objects.requireNonNull(groupId) + "/" + epoch + "/";
        this.store = new SenderKeyStore() {
            private String key(SignalProtocolAddress sender, UUID distribution) {
                if (!epoch.equals(distribution)) throw new SecurityException("Group encryption epoch changed");
                return prefix + SignalStore.addressKey(sender);
            }

            @Override public void storeSenderKey(SignalProtocolAddress sender, UUID distribution, SenderKeyRecord record) {
                vault.put(key(sender, distribution), record.serialize());
            }

            @Override public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distribution) {
                byte[] value = vault.get(key(sender, distribution));
                if (value == null) return null;
                try { return new SenderKeyRecord(value); }
                catch (Exception failure) { throw new SecurityException("Group encryption state is unavailable"); }
            }
        };
    }

    private static SignalProtocolAddress address(UUID user) { return new SignalProtocolAddress(user.toString(), 1); }

    public byte[] distribution() throws Exception {
        return vault.transaction(() -> new GroupSessionBuilder(store).create(local, epoch).serialize());
    }

    public void accept(UUID verifiedSender, byte[] distribution) throws Exception {
        if (distribution == null || distribution.length > 4096) throw new SecurityException("Invalid group key distribution");
        SenderKeyDistributionMessage message = new SenderKeyDistributionMessage(distribution);
        if (!epoch.equals(message.getDistributionId())) throw new SecurityException("Group encryption epoch changed");
        vault.transaction(() -> { new GroupSessionBuilder(store).process(address(verifiedSender), message); return null; });
    }

    public byte[] encrypt(byte[] plaintext) throws Exception {
        if (plaintext == null || plaintext.length == 0 || plaintext.length > 32_768) throw new IllegalArgumentException("Invalid content size");
        return vault.transaction(() -> new GroupCipher(store, local).encrypt(epoch, plaintext).serialize());
    }

    public byte[] decrypt(UUID verifiedSender, byte[] ciphertext) throws Exception {
        if (ciphertext == null || ciphertext.length < 32 || ciphertext.length > 65_536) throw new SecurityException("Invalid group ciphertext");
        SenderKeyMessage message = new SenderKeyMessage(ciphertext);
        if (!epoch.equals(message.getDistributionId())) throw new SecurityException("Group encryption epoch changed");
        return vault.transaction(() -> {
            try { return new GroupCipher(store, address(verifiedSender)).decrypt(ciphertext); }
            catch (AssertionError failure) {
                if (failure.getCause() instanceof org.signal.libsignal.protocol.InvalidKeyException)
                    throw new SecurityException("Group signature verification failed");
                throw failure;
            }
        });
    }
}