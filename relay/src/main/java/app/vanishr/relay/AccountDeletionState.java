package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public class AccountDeletionState {
    private final JdbcTemplate database;
    private final DeletionReceipts receipts;

    public AccountDeletionState(JdbcTemplate database, DeletionReceipts receipts) { this.database = database; this.receipts = receipts; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(UUID userId, String proof) {
        if (database.update("UPDATE accounts SET deletion_state = 'DELETING' WHERE id = ?", userId) != 1)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        receipts.prepare(userId, proof);
    }
}
