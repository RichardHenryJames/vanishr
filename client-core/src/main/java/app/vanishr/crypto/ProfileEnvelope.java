package app.vanishr.crypto;

import java.time.Instant;
import java.util.UUID;

public record ProfileEnvelope(int version, Action action, UUID requestId, long revision,
                              byte[] photo, ChatEnvelope message) {
    public static final int MAX_PHOTO_BYTES = 32 * 1024;
    public enum Action { REQUEST, UPDATE, REVOKE }

    public void verify(UUID id, UUID sender, UUID senderDevice, UUID recipient, UUID recipientDevice,
                       long deadline, Instant now) {
        if (version != 1 || action == null || requestId == null || message == null
                || id == null || sender == null || senderDevice == null || recipient == null || recipientDevice == null
                || !"profile-photo".equals(message.text()) || message.image() != null)
            throw new SecurityException("Invalid profile message");
        message.verify(id, sender, senderDevice, recipient, recipientDevice,
                ChatEnvelope.Expiry.HOURS_24, deadline, null, now);
        if (action == Action.UPDATE) {
            if (revision <= 0 || (photo != null && (photo.length == 0 || photo.length > MAX_PHOTO_BYTES)))
                throw new SecurityException("Invalid profile photo update");
        } else if (revision != 0 || photo != null || (action == Action.REQUEST && !requestId.equals(id))) {
            throw new SecurityException("Invalid profile photo request");
        }
    }

    @Override public String toString() { return "ProfileEnvelope[redacted]"; }
}