package app.vanishr.relay;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeletionReceipts {
    public static final String PROOF_PATTERN = "[A-Za-z0-9_-]{43}";
    public static final long LIFETIME = 86_400_000L;
    public enum State { PENDING, DELETED }
    public record Proof(@NotNull @Pattern(regexp = PROOF_PATTERN) String deletionProof) {
        @Override public String toString() { return "DeletionProof[redacted]"; }
    }
    public record Status(UUID userId, State state, long expiresAt) { }
    private final JdbcTemplate database;

    public DeletionReceipts(JdbcTemplate database) { this.database = database; }

    static String digest(String proof) {
        if (proof == null || !proof.matches(PROOF_PATTERN)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        return RedisRelay.digest(proof.getBytes(StandardCharsets.US_ASCII));
    }

    void prepare(UUID userId, String proof) {
        if (proof == null) return; // Legacy clients cannot recover a lost success response.
        String digest = digest(proof);
        database.update("DELETE FROM account_deletion_receipts WHERE expires_at <= clock_timestamp() AND (user_id = ? OR proof_digest = ?)", userId, digest);
        database.update("INSERT INTO account_deletion_receipts(proof_digest, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", digest, userId);
        Status stored = find(digest).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "deletion_proof_conflict"));
        if (!stored.userId().equals(userId) || stored.state() != State.PENDING)
            throw new ApiException(HttpStatus.CONFLICT, "deletion_proof_conflict");
    }

    public Status status(String proof) {
        return find(digest(proof)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "deletion_proof_unavailable"));
    }

    private Optional<Status> find(String digest) {
        return database.query("""
                SELECT user_id, state, expires_at FROM account_deletion_receipts
                WHERE proof_digest = ? AND expires_at > clock_timestamp()
                """, (row, index) -> new Status(row.getObject("user_id", UUID.class), State.valueOf(row.getString("state")),
                row.getTimestamp("expires_at").toInstant().toEpochMilli()), digest).stream().findFirst();
    }

    void complete(UUID userId) {
        database.update("""
                UPDATE account_deletion_receipts SET state = 'DELETED'
                WHERE user_id = ? AND state = 'PENDING' AND NOT EXISTS(SELECT 1 FROM accounts WHERE id = ?)
                """, userId, userId);
    }

    @Scheduled(fixedDelay = 60_000)
    public void expire() { database.update("DELETE FROM account_deletion_receipts WHERE expires_at <= clock_timestamp()"); }
}
