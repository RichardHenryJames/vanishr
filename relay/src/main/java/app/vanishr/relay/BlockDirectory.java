package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class BlockDirectory {
    public static final int MAX_BLOCKS = 512;
    private final JdbcTemplate database;
    private final AccountDirectory accounts;

    public BlockDirectory(JdbcTemplate database, AccountDirectory accounts) { this.database = database; this.accounts = accounts; }

    public List<UUID> list(UUID user) {
        return database.query("SELECT blocked_id FROM account_blocks WHERE blocker_id = ? ORDER BY blocked_id LIMIT 512",
                (row, index) -> row.getObject(1, UUID.class), user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void put(UUID user, UUID peer) {
        if (user.equals(peer)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        accounts.contact(peer);
        List<UUID> blocked = list(user);
        if (blocked.contains(peer)) return;
        if (blocked.size() >= MAX_BLOCKS) throw new ApiException(HttpStatus.CONFLICT, "block_capacity");
        database.update("INSERT INTO account_blocks(blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING", user, peer);
    }

    public void remove(UUID user, UUID peer) {
        if (user.equals(peer)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        database.update("DELETE FROM account_blocks WHERE blocker_id = ? AND blocked_id = ?", user, peer);
    }
}
