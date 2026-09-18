package app.vanishr.crypto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ChatEnvelope(int version, UUID id, UUID senderId, UUID senderDeviceId,
                           UUID recipientId, UUID recipientDeviceId, long sentAt, long expiresAt,
                           Expiry expiry, String text, Attachment image) {
    public enum Expiry {
        VIEW_ONCE(24), HOUR_1(1), HOURS_6(6), HOURS_24(24);
        public final long milliseconds;
        Expiry(int hours) { milliseconds = hours * 3_600_000L; }
    }

    public record Attachment(UUID id, byte[] key, byte[] nonce, String mimeType) { }

    public void verify(UUID expectedId, UUID sender, UUID senderDevice, UUID recipient, UUID recipientDevice,
                       Expiry expectedExpiry, long expectedDeadline, UUID mediaId, Instant now) {
        if (version != 1 || !Objects.equals(id, expectedId) || !Objects.equals(senderId, sender)
                || !Objects.equals(senderDeviceId, senderDevice) || !Objects.equals(recipientId, recipient)
                || !Objects.equals(recipientDeviceId, recipientDevice) || expiry == null || expiry != expectedExpiry
                || expiresAt != expectedDeadline || sentAt > now.toEpochMilli() + 300_000
                || sentAt < now.toEpochMilli() - 86_400_000 || expiresAt <= now.toEpochMilli()
                || expiresAt > now.toEpochMilli() + expiry.milliseconds
                || expiresAt <= sentAt || expiresAt - sentAt > expiry.milliseconds)
            throw new SecurityException("Message context or deadline is invalid");
        if (image == null) {
            if (mediaId != null || text == null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 16_384)
                throw new SecurityException("Invalid encrypted text message");
        } else if (text != null || !Objects.equals(image.id(), mediaId) || image.id() == null
                || image.key() == null || image.key().length != 32 || image.nonce() == null || image.nonce().length != 12
                || !("image/jpeg".equals(image.mimeType()) || "image/png".equals(image.mimeType()))) {
            throw new SecurityException("Invalid encrypted image descriptor");
        }
    }
}