package app.vanishr.relay;

import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.UUID;

public final class RelayTypes {
    private RelayTypes() { }

    public enum Expiry {
        VIEW_ONCE(24), HOUR_1(1), HOURS_6(6), HOURS_24(24);
        public final Duration lifetime;
        Expiry(int hours) { lifetime = Duration.ofHours(hours); }
    }

    public enum State { QUEUED, DELIVERED, READ, DELETED }
    public record Actor(UUID userId, UUID deviceId) { }

    public record SendRequest(@NotNull UUID id, @NotNull UUID recipientId,
                              @NotNull UUID recipientDeviceId, @NotNull Expiry expiry,
                              @Positive long expiresAt, @Min(2) @Max(3) int type,
                              @NotNull @Size(min = 32, max = 65_536) byte[] ciphertext, UUID mediaId) { }

    public record Message(UUID id, UUID senderId, UUID senderDeviceId, UUID recipientId,
                          UUID recipientDeviceId, Expiry expiry, long createdAt, long expiresAt,
                          int type, byte[] ciphertext, UUID mediaId) { }

    public record Receipt(UUID id, UUID senderId, UUID senderDeviceId, UUID recipientId,
                          UUID recipientDeviceId, Expiry expiry, long expiresAt,
                          UUID mediaId, State state, String digest) { }

    public record Status(UUID id, State state, long expiresAt) {
        public static Status from(Receipt receipt) { return new Status(receipt.id(), receipt.state(), receipt.expiresAt()); }
    }

    public record Media(UUID id, UUID senderDeviceId, UUID recipientDeviceId,
                        long expiresAt, UUID messageId, byte[] ciphertext, String digest) { }
}