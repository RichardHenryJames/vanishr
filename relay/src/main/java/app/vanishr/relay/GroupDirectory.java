package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;

import static app.vanishr.relay.RelayTypes.Actor;

@Service
public class GroupDirectory {
    public static final int MAX_MEMBERS = 200;
    public static final int MAX_GROUPS = 20;
    public record Member(UUID userId, UUID deviceId, String identityKey, String handle, String displayName, String state, long invitedUntil) { }
    public record Snapshot(UUID id, UUID ownerId, long revision, UUID epoch, boolean closed, List<Member> members) {
        public Member member(UUID userId) {
            return members.stream().filter(member -> member.userId().equals(userId)).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
        }
        public List<Member> active() { return members.stream().filter(member -> member.state().equals("ACTIVE")).toList(); }
    }
    private final JdbcTemplate database;
    private final AccountDirectory accounts;
    private final Clock clock;

    public GroupDirectory(JdbcTemplate database, AccountDirectory accounts, Clock clock) {
        this.database = database; this.accounts = accounts; this.clock = clock;
    }

    private void capacity(UUID userId) {
        database.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", UUID.class, userId);
        Integer count = database.queryForObject("SELECT count(*) FROM group_members m JOIN private_groups g ON g.id=m.group_id WHERE m.user_id=? AND g.closed_at IS NULL AND (m.state='ACTIVE' OR m.invited_until>?)",
                Integer.class, userId, clock.millis());
        if (count != null && count >= MAX_GROUPS) throw new ApiException(HttpStatus.CONFLICT, "group_capacity");
    }

    private Snapshot read(UUID id) {
        List<Snapshot> values = database.query("SELECT id,owner_id,revision,epoch,closed_at FROM private_groups WHERE id=? FOR UPDATE",
                (row, index) -> new Snapshot(id, row.getObject("owner_id", UUID.class), row.getLong("revision"), row.getObject("epoch", UUID.class), row.getTimestamp("closed_at") != null, List.of()), id);
        if (values.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        Snapshot group = values.get(0);
        List<Member> members = database.query("SELECT m.*,a.handle,a.display_name FROM group_members m JOIN accounts a ON a.id=m.user_id WHERE m.group_id=? AND (m.state='ACTIVE' OR m.invited_until>?) ORDER BY m.user_id",
                (row, index) -> new Member(row.getObject("user_id", UUID.class), row.getObject("device_id", UUID.class), row.getString("identity_key"),
                        row.getString("handle"), row.getString("display_name"), row.getString("state"), row.getLong("invited_until")), id, clock.millis());
        return new Snapshot(id, group.ownerId(), group.revision(), group.epoch(), group.closed(), members);
    }

    @Transactional
    public <Result> Result withGroup(Actor actor, UUID id, Long revision, boolean invited, Function<Snapshot, Result> action) {
        Snapshot group = read(id);
        Member member = group.member(actor.userId());
        if (!member.deviceId().equals(actor.deviceId()) || (!invited && !member.state().equals("ACTIVE")))
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        if (!accounts.contact(actor.userId()).identityKey().equals(member.identityKey()))
            throw new ApiException(HttpStatus.CONFLICT, "group_identity_changed");
        if (revision != null && (group.closed() || group.revision() != revision))
            throw new ApiException(HttpStatus.CONFLICT, "group_changed");
        return action.apply(group);
    }

    @Transactional
    public List<Snapshot> list(Actor actor) {
        List<UUID> ids = database.query("SELECT group_id FROM group_members WHERE user_id=? AND device_id=? AND (state='ACTIVE' OR invited_until>?) ORDER BY group_id LIMIT 40",
                (row, index) -> row.getObject(1, UUID.class), actor.userId(), actor.deviceId(), clock.millis());
        return ids.stream().map(this::read).toList();
    }

    @Transactional
    public Snapshot create(Actor actor, UUID id) {
        capacity(actor.userId());
        List<UUID> existing = database.query("SELECT owner_id FROM private_groups WHERE id=?", (row, index) -> row.getObject(1, UUID.class), id);
        if (!existing.isEmpty()) {
            if (!existing.get(0).equals(actor.userId())) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
            return withGroup(actor, id, null, false, Function.identity());
        }
        AccountDirectory.Contact owner = accounts.contact(actor.userId());
        if (!owner.deviceId().equals(actor.deviceId())) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        database.update("INSERT INTO private_groups(id,owner_id,revision,epoch) VALUES (?,?,1,?)", id, actor.userId(), UUID.randomUUID());
        database.update("INSERT INTO group_members(group_id,user_id,device_id,identity_key,state,invited_until) VALUES (?,?,?,?,'ACTIVE',0)",
                id, actor.userId(), actor.deviceId(), owner.identityKey());
        return read(id);
    }

    @Transactional
    public Snapshot invite(Actor actor, UUID id, long revision, List<AccountDirectory.Contact> invitees) {
        return withGroup(actor, id, revision, false, group -> {
            owner(actor, group);
            if (invitees == null || invitees.isEmpty() || invitees.size() >= MAX_MEMBERS || invitees.stream().anyMatch(Objects::isNull)
                    || invitees.stream().map(AccountDirectory.Contact::userId).distinct().count() != invitees.size())
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
            Set<UUID> members = new HashSet<>(); group.members().forEach(member -> members.add(member.userId()));
            for (AccountDirectory.Contact invitee : invitees) {
                if (invitee == null || invitee.userId() == null || !accounts.contact(invitee.userId()).equals(invitee))
                    throw new ApiException(HttpStatus.CONFLICT, "group_identity_changed");
                members.add(invitee.userId());
            }
            if (members.size() > MAX_MEMBERS) throw new ApiException(HttpStatus.CONFLICT, "group_full");
            for (AccountDirectory.Contact invitee : invitees.stream().sorted(Comparator.comparing(AccountDirectory.Contact::userId)).toList()) {
                if (group.members().stream().anyMatch(member -> member.userId().equals(invitee.userId()))) continue;
                capacity(invitee.userId());
                database.update("INSERT INTO group_members(group_id,user_id,device_id,identity_key,state,invited_until) VALUES (?,?,?,?,'INVITED',?) ON CONFLICT (group_id,user_id) DO UPDATE SET device_id=EXCLUDED.device_id,identity_key=EXCLUDED.identity_key,state='INVITED',invited_until=EXCLUDED.invited_until",
                        id, invitee.userId(), invitee.deviceId(), invitee.identityKey(), clock.millis() + 86_400_000L);
            }
            return read(id);
        });
    }

    @Transactional
    public Snapshot accept(Actor actor, UUID id, long revision) {
        return withGroup(actor, id, revision, true, group -> {
            Member member = group.member(actor.userId());
            AccountDirectory.Contact active = accounts.contact(actor.userId());
            if (!active.deviceId().equals(member.deviceId()) || !active.identityKey().equals(member.identityKey()))
                throw new ApiException(HttpStatus.CONFLICT, "group_identity_changed");
            if (member.state().equals("INVITED")) {
                database.update("UPDATE group_members SET state='ACTIVE',invited_until=0 WHERE group_id=? AND user_id=?", id, actor.userId());
                rotate(id);
            }
            return read(id);
        });
    }

    @Transactional
    public void remove(Actor actor, UUID id, long revision, UUID userId) {
        withGroup(actor, id, revision, true, group -> {
            if (!actor.userId().equals(userId)) owner(actor, group);
            if (group.ownerId().equals(userId)) throw new ApiException(HttpStatus.CONFLICT, "owner_must_close_group");
            Member member = group.member(userId);
            database.update("DELETE FROM group_members WHERE group_id=? AND user_id=?", id, userId);
            if (member.state().equals("ACTIVE")) rotate(id);
            return null;
        });
    }

    @Transactional
    public void close(Actor actor, UUID id, long revision) {
        withGroup(actor, id, revision, false, group -> {
            owner(actor, group); rotate(id);
            database.update("UPDATE private_groups SET closed_at=now() WHERE id=?", id);
            return null;
        });
    }

    private void owner(Actor actor, Snapshot group) {
        if (!actor.userId().equals(group.ownerId())) throw new ApiException(HttpStatus.FORBIDDEN, "group_owner_required");
    }

    public void requireCurrentDevices(Snapshot group) {
        Integer count=database.queryForObject("SELECT count(*) FROM group_members m JOIN devices d ON d.id=m.device_id AND d.user_id=m.user_id AND d.identity_key=m.identity_key WHERE m.group_id=? AND m.state='ACTIVE'",Integer.class,group.id());
        if (count==null || count!=group.active().size()) throw new ApiException(HttpStatus.CONFLICT,"group_identity_changed");
    }

    private void rotate(UUID id) { database.update("UPDATE private_groups SET revision=revision+1,epoch=? WHERE id=?", UUID.randomUUID(), id); }

    @Scheduled(fixedDelay = 60_000)
    public void expireInvitations() {
        database.update("DELETE FROM group_members WHERE state='INVITED' AND invited_until<=?", clock.millis());
        database.update("DELETE FROM private_groups WHERE closed_at<=now()-interval '24 hours'");
    }
}