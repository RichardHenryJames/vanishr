package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static app.vanishr.relay.RelayTypes.Actor;

@Service
public class AccountSafety {
    private record Membership(UUID group, UUID device, String state) { }
    private final JdbcTemplate database;
    private final AccountDeletionState deletion;
    private final BlockDirectory blocks;
    private final EphemeralCleanup cleanup;
    private final RealtimeHub realtime;
    private final DeletionReceipts receipts;
    private final SafetyGate gate;

    public AccountSafety(JdbcTemplate database, AccountDeletionState deletion, BlockDirectory blocks,
                         EphemeralCleanup cleanup, RealtimeHub realtime, DeletionReceipts receipts, SafetyGate gate) {
        this.database = database; this.deletion = deletion; this.blocks = blocks; this.cleanup = cleanup; this.realtime = realtime;
        this.receipts = receipts;
        this.gate = gate;
    }

    @Transactional
    public void block(Actor actor, UUID peer) {
        blocks.put(actor.userId(), peer);
        cleanup.direct(actor.userId(), peer);
        database.update("DELETE FROM admin_introductions WHERE (user_id = ? AND admin_id = ?) OR (user_id = ? AND admin_id = ?)",
                actor.userId(), peer, peer, actor.userId());
        List<Membership> invitations = database.query("""
                DELETE FROM group_members invitation WHERE state = 'INVITED'
                    AND ((user_id = ? AND EXISTS(SELECT 1 FROM group_members other WHERE other.group_id = invitation.group_id AND other.user_id = ?))
                      OR (user_id = ? AND EXISTS(SELECT 1 FROM group_members other WHERE other.group_id = invitation.group_id AND other.user_id = ?)))
                RETURNING group_id, device_id, state
                """, (row, index) -> new Membership(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3)),
                actor.userId(), peer, peer, actor.userId());
        invitations.forEach(member -> cleanup.revokeGroupRecipient(member.group(), member.device()));
    }

    @Transactional
    public void delete(Actor actor, String proof) {
        if (actor.deviceId() != null) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
        // This small independent commit makes any later Redis/DB failure fail closed and retryable.
        deletion.begin(actor.userId(), proof);
        erase(actor);
    }

    @Transactional
    public DeletionReceipts.Status retry(String proof) {
        DeletionReceipts.Status status = receipts.status(proof);
        if (status.state() == DeletionReceipts.State.DELETED) return status;
        if (!Boolean.TRUE.equals(database.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM accounts WHERE id = ? AND deletion_state = 'DELETING')", Boolean.class, status.userId())))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "account_cleanup_failed");
        gate.requireExclusive();
        erase(new Actor(status.userId(), null));
        return receipts.status(proof);
    }

    private void erase(Actor actor) {
        String handle = database.queryForObject("SELECT handle FROM accounts WHERE id = ?", String.class, actor.userId());
        boolean pinned = Boolean.TRUE.equals(database.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_identity WHERE user_id = ?)", Boolean.class, actor.userId()));
        var owned = new HashSet<>(database.queryForList("SELECT id FROM private_groups WHERE owner_id = ?", UUID.class, actor.userId()));
        List<Membership> memberships = database.query("SELECT group_id, device_id, state FROM group_members WHERE user_id = ?",
                (row, index) -> new Membership(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3)), actor.userId());
        realtime.disconnectAccount(actor.userId());
        cleanup.account(actor.userId(), owned, pinned, handle);
        for (Membership member : memberships) {
            if (!owned.contains(member.group())) {
                cleanup.revokeGroupRecipient(member.group(), member.device());
                if (member.state().equals("ACTIVE")) database.update(
                        "UPDATE private_groups SET revision = revision + 1, epoch = ? WHERE id = ?", UUID.randomUUID(), member.group());
            }
        }
        for (UUID group : owned) database.update(
                "UPDATE private_groups SET revision = revision + 1, epoch = ?, closed_at = now() WHERE id = ?", UUID.randomUUID(), group);
        database.update("DELETE FROM admin_introductions WHERE user_id = ? OR admin_id = ?", actor.userId(), actor.userId());
        // Cascades erase devices/prekeys, memberships, owned closed groups, the encrypted backup, and both block directions.
        if (database.update("DELETE FROM accounts WHERE id = ? AND deletion_state = 'DELETING'", actor.userId()) != 1)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "account_cleanup_failed");
        receipts.complete(actor.userId());
    }
}
