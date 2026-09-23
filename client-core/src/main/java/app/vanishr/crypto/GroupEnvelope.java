package app.vanishr.crypto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GroupEnvelope(int version, UUID groupId, UUID epoch, long revision, ChatEnvelope content) {
    public void verify(UUID group, UUID expectedEpoch, long expectedRevision, UUID id, UUID sender, UUID senderDevice,
                       ChatEnvelope.Expiry expiry, long deadline, UUID mediaId, Instant now) {
        if (version != 1 || group == null || expectedEpoch == null || expectedRevision < 1
                || !Objects.equals(groupId, group) || !Objects.equals(epoch, expectedEpoch)
                || revision != expectedRevision || content == null)
            throw new SecurityException("Group membership or message context changed");
        content.verify(id, sender, senderDevice, group, expectedEpoch, expiry, deadline, mediaId, now);
    }
}