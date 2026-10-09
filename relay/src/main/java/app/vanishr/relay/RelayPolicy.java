package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import java.time.Instant;
import static app.vanishr.relay.RelayTypes.*;

public final class RelayPolicy {
    public static final int MAX_MEDIA_BYTES = 2 * 1024 * 1024 + 16;
    public static final int MIN_BACKUP_BYTES = 64;
    public static final int MAX_BACKUP_BYTES = 512 * 1024;
    // Absolute hours (90 days), kept in step with the account_backups expiry CHECK constraint.
    public static final int BACKUP_RETENTION_HOURS = 90 * 24;
    private RelayPolicy() { }

    public static void deadline(Expiry expiry, long expiresAt, Instant now) {
        if (expiry == null || expiresAt <= now.toEpochMilli() || expiresAt > now.plus(expiry.lifetime).toEpochMilli())
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_expiry");
    }

    public static void message(SendRequest request, Actor actor, Instant now) {
        deadline(request.expiry(), request.expiresAt(), now);
        if (actor.userId().equals(request.recipientId()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "self_delivery_not_supported");
        if (request.type() != 2 && request.type() != 3)
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_ciphertext_type");
    }
}