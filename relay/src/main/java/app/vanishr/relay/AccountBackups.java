package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountBackups {
    public record Status(boolean exists, long size, long updatedAt, long expiresAt) { }
    private final JdbcTemplate database;

    public AccountBackups(JdbcTemplate database) { this.database = database; }

    /** Replaces the caller's single opaque blob and restarts its bounded retention in the same statement. */
    public Status put(UUID userId, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < RelayPolicy.MIN_BACKUP_BYTES || ciphertext.length > RelayPolicy.MAX_BACKUP_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_backup");
        int changed = database.update("""
                INSERT INTO account_backups(user_id, ciphertext, updated_at, expires_at)
                SELECT id, ?, now(), now() + make_interval(hours => ?) FROM accounts WHERE id = ? AND deletion_state = 'ACTIVE'
                ON CONFLICT (user_id) DO UPDATE SET ciphertext = EXCLUDED.ciphertext,
                    updated_at = EXCLUDED.updated_at, expires_at = EXCLUDED.expires_at
                """, ciphertext, RelayPolicy.BACKUP_RETENTION_HOURS, userId);
        if (changed != 1) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        return status(userId);
    }

    public Optional<byte[]> get(UUID userId) {
        return database.query("SELECT ciphertext FROM account_backups WHERE user_id = ? AND expires_at > now()",
                (row, index) -> row.getBytes(1), userId).stream().findFirst();
    }

    public Status status(UUID userId) {
        return database.query("SELECT octet_length(ciphertext) AS size, updated_at, expires_at FROM account_backups WHERE user_id = ? AND expires_at > now()",
                (row, index) -> new Status(true, row.getLong("size"), row.getTimestamp("updated_at").getTime(),
                        row.getTimestamp("expires_at").getTime()), userId).stream().findFirst().orElse(new Status(false, 0, 0, 0));
    }

    public void delete(UUID userId) { database.update("DELETE FROM account_backups WHERE user_id = ?", userId); }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() { database.update("DELETE FROM account_backups WHERE expires_at <= now()"); }
}
