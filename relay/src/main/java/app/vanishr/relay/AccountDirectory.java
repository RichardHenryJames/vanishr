package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class AccountDirectory {
    static final String USERNAME_PATTERN = "[a-z0-9_-]{3,32}";
    public record Contact(UUID userId, UUID deviceId, String identityKey) { }
    public record GoogleAccount(UUID userId, String handle) { }
    public record Username(UUID userId, String handle) { }
    public record UsernameChange(@NotNull @Pattern(regexp = USERNAME_PATTERN) String handle) { }
    public record Profile(UUID userId, String handle, String displayName) { }
    public record ProfileChange(@NotBlank @Size(max = 40) String displayName) { }
    public enum UserType { USER, ADMIN }
    public record AccountType(UUID userId, UserType userType) { }
    public record Introduction(UUID userId, UUID deviceId, String identityKey, String handle, String displayName) { }
    public record Introductions(UUID userId, Contact admin, List<Introduction> contacts, UUID nextAfter) { }
    public record DeviceRequest(@NotNull UUID deviceId, @NotNull @Size(min = 33, max = 33) byte[] identityKey, boolean replaceExisting) { }
    public record PreKey(@Min(1) @Max(16380) int registrationId, @PositiveOrZero int preKeyId,
                         @Size(min = 33, max = 33) byte[] preKey, @Positive int signedPreKeyId,
                         @NotNull @Size(min = 33, max = 33) byte[] signedPreKey,
                         @NotNull @Size(min = 64, max = 64) byte[] signedPreKeySignature,
                         @NotNull @Size(min = 33, max = 33) byte[] identityKey, @Positive int kyberPreKeyId,
                         @NotNull @Size(min = 1569, max = 1569) byte[] kyberPreKey,
                         @NotNull @Size(min = 64, max = 64) byte[] kyberPreKeySignature) { }
    public record KeyUpload(@NotNull @Size(min = 1, max = 32) List<@Valid PreKey> keys) { }
    public record FallbackUpload(@NotNull @Valid PreKey key, @Positive long expiresAt) { }
    public record KeyCount(int remaining, boolean fallbackSupported, int fallbackKeyId, long fallbackExpiresAt) { }
    public record Credentials(UUID id, String passwordHash) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final Clock clock;
    private final Supplier<String> googleUsernames;

    @Autowired
    public AccountDirectory(JdbcTemplate database, ObjectMapper json, Clock clock) {
        this(database, json, clock, GoogleUsernames::next);
    }

    AccountDirectory(JdbcTemplate database, ObjectMapper json, Clock clock, Supplier<String> googleUsernames) {
        this.database = database;
        this.json = json;
        this.clock = clock;
        this.googleUsernames = Objects.requireNonNull(googleUsernames);
    }

    public UUID create(String handle, String passwordHash) {
        UUID id = UUID.randomUUID();
        try { database.update("INSERT INTO accounts(id, handle, password_hash) VALUES (?, ?, ?)", id, handle, passwordHash); }
        catch (DataIntegrityViolationException failure) { throw new ApiException(HttpStatus.CONFLICT, "account_unavailable"); }
        return id;
    }

    public Optional<Credentials> credentials(String handle) {
        return database.query("SELECT id, password_hash FROM accounts WHERE handle = ?",
                (row, index) -> new Credentials(row.getObject("id", UUID.class), row.getString("password_hash")), handle).stream().findFirst();
    }

    public boolean exists(UUID userId) {
        return Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id = ?)", Boolean.class, userId));
    }

    public boolean available(UUID userId) {
        return Boolean.TRUE.equals(database.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM accounts WHERE id = ? AND deletion_state = 'ACTIVE')", Boolean.class, userId));
    }

    public void requireAvailable(UUID userId) {
        if (!available(userId)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    public Username username(UUID userId) {
        return database.query("SELECT id, handle FROM accounts WHERE id = ?",
                (row, index) -> new Username(row.getObject("id", UUID.class), row.getString("handle")), userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
    }

    public Username rename(UUID userId, UsernameChange change) {
        try {
            if (database.update("UPDATE accounts SET handle = ? WHERE id = ?", change.handle(), userId) != 1)
                throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        } catch (DataIntegrityViolationException failure) { throw new ApiException(HttpStatus.CONFLICT, "account_unavailable"); }
        return new Username(userId, change.handle());
    }

    public Profile profile(UUID userId) {
        return database.query("SELECT id, handle, display_name FROM accounts WHERE id = ? AND deletion_state = 'ACTIVE'",
                (row, index) -> new Profile(row.getObject("id", UUID.class), row.getString("handle"), row.getString("display_name")), userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
    }

    public AccountType accountType(UUID userId) {
        return database.query("""
                SELECT a.id, a.user_type, admin.user_id AS admin_user_id
                FROM accounts a LEFT JOIN admin_identity admin ON admin.user_id = a.id
                WHERE a.id = ? AND a.deletion_state = 'ACTIVE'
                """, (row, index) -> {
                    UserType type = UserType.valueOf(row.getString("user_type"));
                    if (type == UserType.ADMIN && row.getObject("admin_user_id", UUID.class) == null)
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "admin_identity_unavailable");
                    return new AccountType(row.getObject("id", UUID.class), type);
                }, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Introductions introductions(UUID userId, UUID after, UUID peerId) {
        UUID adminId = database.query("""
                SELECT pin.user_id FROM admin_identity pin JOIN accounts a ON a.id = pin.user_id
                WHERE a.user_type = 'ADMIN' AND a.deletion_state = 'ACTIVE'
                """, (row, index) -> row.getObject("user_id", UUID.class)).stream().findFirst().orElse(null);
        if (adminId == null) return new Introductions(userId, null, List.of(), null);
        if (blocked(userId, adminId)) return new Introductions(userId, null, List.of(), null);
        if (!adminId.equals(userId) && !Boolean.TRUE.equals(database.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_introductions WHERE user_id = ? AND admin_id = ?)",
                Boolean.class, userId, adminId))) return new Introductions(userId, null, List.of(), null);
        Contact admin = contact(adminId);
        List<Introduction> contacts = database.query("""
                SELECT a.id, d.id AS device_id, d.identity_key, a.handle, a.display_name
                FROM admin_introductions link
                JOIN accounts a ON a.id = CASE WHEN link.admin_id = ? THEN link.user_id ELSE link.admin_id END
                JOIN devices d ON d.user_id = a.id
                WHERE link.admin_id = ? AND (link.admin_id = ? OR link.user_id = ?)
                  AND a.deletion_state = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM account_blocks b
                      WHERE (b.blocker_id = ? AND b.blocked_id = a.id) OR (b.blocked_id = ? AND b.blocker_id = a.id))
                  AND (?::uuid IS NULL OR (link.introduced_at, link.user_id) < (
                      SELECT cursor.introduced_at, cursor.user_id
                      FROM admin_introductions cursor
                      WHERE cursor.admin_id = ? AND cursor.user_id = ?))
                  AND (?::uuid IS NULL OR a.id = ?)
                ORDER BY link.introduced_at DESC, link.user_id DESC LIMIT 65
                """, (row, index) -> new Introduction(row.getObject("id", UUID.class),
                row.getObject("device_id", UUID.class), row.getString("identity_key"),
                row.getString("handle"), row.getString("display_name")),
                userId, adminId, userId, userId, userId, userId, after, adminId, after, peerId, peerId);
        UUID next = contacts.size() > 64 ? contacts.get(63).userId() : null;
        return new Introductions(userId, admin, List.copyOf(contacts.subList(0, Math.min(64, contacts.size()))), next);
    }

    public Profile updateProfile(UUID userId, ProfileChange change) {
        String name = change.displayName().strip();
        if (name.isEmpty() || name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        if (database.update("UPDATE accounts SET display_name = ? WHERE id = ?", name, userId) != 1)
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        return profile(userId);
    }

    @Transactional
    public GoogleAccount googleAccount(String subject) {
        validateGoogleSubject(subject);
        Optional<GoogleAccount> existing = findGoogleAccount(subject);
        if (existing.isPresent()) return existing.get();
        for (int attempt = 0; attempt < 16; attempt++) {
            UUID userId = UUID.randomUUID();
            String handle = googleUsernames.get();
            // Ignore uniqueness conflicts without aborting the PostgreSQL transaction.
            int inserted = database.update("INSERT INTO accounts(id, handle, google_subject) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    userId, handle, subject);
            if (inserted == 1) return new GoogleAccount(userId, handle);
            existing = findGoogleAccount(subject);
            if (existing.isPresent()) return existing.get();
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_sign_in_unavailable");
    }

    private Optional<GoogleAccount> findGoogleAccount(String subject) {
        return database.query("SELECT id, handle FROM accounts WHERE google_subject = ?",
                (row, index) -> new GoogleAccount(row.getObject("id", UUID.class), row.getString("handle")), subject)
                .stream().findFirst();
    }

    public GoogleAccount existingGoogleAccount(String subject, UUID expectedUserId) {
        validateGoogleSubject(subject);
        return database.query("SELECT id, handle FROM accounts WHERE google_subject = ? AND id = ?",
                (row, index) -> new GoogleAccount(row.getObject("id", UUID.class), row.getString("handle")), subject, expectedUserId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed"));
    }

    private void validateGoogleSubject(String subject) {
        if (subject == null || !subject.matches("[A-Za-z0-9_-]{1,255}"))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
    }

    public boolean active(UUID userId, UUID deviceId) {
        return Boolean.TRUE.equals(database.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM devices d JOIN accounts a ON a.id = d.user_id
                WHERE d.user_id = ? AND d.id = ? AND a.deletion_state = 'ACTIVE')
                """, Boolean.class, userId, deviceId));
    }

    public UUID version(UUID deviceId) {
        return database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, deviceId);
    }

    public boolean active(RelayTypes.Actor actor, UUID version) {
        return Boolean.TRUE.equals(database.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM devices d JOIN accounts a ON a.id = d.user_id
                WHERE d.user_id = ? AND d.id = ? AND d.auth_version = ? AND a.deletion_state = 'ACTIVE')
                """,
                Boolean.class, actor.userId(), actor.deviceId(), version));
    }

    public boolean blocked(UUID first, UUID second) {
        return Boolean.TRUE.equals(database.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM account_blocks WHERE
                    (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, first, second, second, first));
    }

    public boolean canInteract(UUID first, UUID second) {
        return available(first) && available(second) && !blocked(first, second);
    }

    public void requireInteraction(UUID first, UUID second) {
        if (!canInteract(first, second)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    public void requireUnblockedGroup(Set<UUID> users) {
        String placeholders = String.join(",", Collections.nCopies(users.size(), "?"));
        List<UUID> parameters = new ArrayList<>(users);
        parameters.addAll(users);
        if (Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM account_blocks WHERE blocker_id IN ("
                + placeholders + ") AND blocked_id IN (" + placeholders + "))", Boolean.class, parameters.toArray())))
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    public Optional<UUID> deviceOwner(UUID deviceId) {
        return database.query("SELECT user_id FROM devices WHERE id = ?",
                (row, index) -> row.getObject(1, UUID.class), deviceId).stream().findFirst();
    }

    public boolean routeAllowed(GenericNotifier.Destination destination) {
        if (!active(destination.userId(), destination.deviceId())) return false;
        if (exists(destination.conversationId())) return canInteract(destination.userId(), destination.conversationId());
        return Boolean.TRUE.equals(database.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM group_members m JOIN private_groups g ON g.id = m.group_id
                    WHERE m.group_id = ? AND m.user_id = ? AND m.device_id = ? AND m.state = 'ACTIVE' AND g.closed_at IS NULL)
                """, Boolean.class, destination.conversationId(), destination.userId(), destination.deviceId()));
    }

    @Transactional
    public void registerDevice(UUID userId, DeviceRequest request) {
        database.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", UUID.class, userId);
        requireAvailable(userId);
        Integer count = database.queryForObject("SELECT COUNT(*) FROM devices WHERE user_id = ?", Integer.class, userId);
        if (count != null && count > 0 && !request.replaceExisting()) {
            Contact registered = contact(userId);
            if (registered.deviceId().equals(request.deviceId())
                    && registered.identityKey().equals(Base64.getEncoder().encodeToString(request.identityKey()))) return;
            throw new ApiException(HttpStatus.CONFLICT, "device_already_registered");
        }
        database.update("DELETE FROM devices WHERE user_id = ?", userId);
        try {
                database.update("INSERT INTO devices(id, user_id, identity_key, auth_version) VALUES (?, ?, ?, ?)",
                    request.deviceId(), userId, Base64.getEncoder().encodeToString(request.identityKey()), UUID.randomUUID());
        } catch (DataIntegrityViolationException failure) { throw new ApiException(HttpStatus.CONFLICT, "device_unavailable"); }
    }

    public Contact contact(UUID userId) {
        requireAvailable(userId);
        return findContact("SELECT user_id, id, identity_key FROM devices WHERE user_id = ?", userId);
    }

    public Contact contact(String handle) {
        return findContact("SELECT devices.user_id, devices.id, devices.identity_key FROM devices JOIN accounts ON accounts.id = devices.user_id WHERE accounts.handle = ? AND accounts.deletion_state = 'ACTIVE'", handle);
    }

    private Contact findContact(String query, Object parameter) {
        return database.query(query, (row, index) -> new Contact(row.getObject("user_id", UUID.class), row.getObject("id", UUID.class), row.getString("identity_key")), parameter)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
    }

    @Transactional
    public void uploadKeys(RelayTypes.Actor actor, KeyUpload upload) {
        database.queryForObject("SELECT id FROM devices WHERE id = ? FOR UPDATE", UUID.class, actor.deviceId());
        Contact contact = contact(actor.userId());
        int count = keyCount(actor.deviceId());
        if (count + upload.keys().size() > 256) throw new ApiException(HttpStatus.CONFLICT, "prekey_capacity");
        for (PreKey key : upload.keys()) {
            if (key.preKeyId() <= 0 || key.preKey() == null
                    || !contact.identityKey().equals(Base64.getEncoder().encodeToString(key.identityKey()))
                    || key.preKeyId() != key.signedPreKeyId() || key.preKeyId() != key.kyberPreKeyId())
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_public_bundle");
            try {
                database.update("INSERT INTO prekeys(device_id, id, public_bundle, expires_at) VALUES (?, ?, ?::jsonb, to_timestamp(?)) ON CONFLICT DO NOTHING",
                        actor.deviceId(), key.preKeyId(), json.writeValueAsString(key), clock.instant().plusSeconds(86400).getEpochSecond());
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Public key serialization failed"); }
        }
    }

    public int keyCount(UUID deviceId) {
        Integer count = database.queryForObject("SELECT COUNT(*) FROM prekeys WHERE device_id = ? AND expires_at > now()", Integer.class, deviceId);
        return count == null ? 0 : count;
    }

    public KeyCount keyStatus(UUID deviceId) {
        return database.query("SELECT id, expires_at FROM fallback_prekeys WHERE device_id = ? AND expires_at > now()",
                (row, index) -> new KeyCount(keyCount(deviceId), true, row.getInt("id"), row.getTimestamp("expires_at").getTime()),
                deviceId).stream().findFirst().orElseGet(() -> new KeyCount(keyCount(deviceId), true, 0, 0));
    }

    @Transactional
    public void uploadFallback(RelayTypes.Actor actor, FallbackUpload upload) {
        database.queryForObject("SELECT id FROM devices WHERE id = ? FOR UPDATE", UUID.class, actor.deviceId());
        Contact contact = contact(actor.userId());
        PreKey key = upload.key();
        long now = clock.millis();
        if (key.preKeyId() != 0 || key.preKey() != null || key.signedPreKeyId() != key.kyberPreKeyId()
                || !contact.identityKey().equals(Base64.getEncoder().encodeToString(key.identityKey()))
                || upload.expiresAt() <= now || upload.expiresAt() > now + 30L * 86400_000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_public_bundle");
        try {
            int changed = database.update("""
                    INSERT INTO fallback_prekeys(device_id, id, public_bundle, created_at, expires_at)
                    VALUES (?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (device_id) DO UPDATE SET id = EXCLUDED.id, public_bundle = EXCLUDED.public_bundle,
                        created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                    WHERE fallback_prekeys.id < EXCLUDED.id
                    """, actor.deviceId(), key.kyberPreKeyId(), json.writeValueAsString(key),
                    new java.sql.Timestamp(now), new java.sql.Timestamp(upload.expiresAt()));
            if (changed == 0 && !Boolean.TRUE.equals(database.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fallback_prekeys
                        WHERE device_id = ? AND id = ? AND public_bundle = ?::jsonb AND expires_at = ?)
                    """, Boolean.class, actor.deviceId(), key.kyberPreKeyId(), json.writeValueAsString(key),
                    new java.sql.Timestamp(upload.expiresAt()))))
                throw new ApiException(HttpStatus.CONFLICT, "fallback_key_changed");
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Public key serialization failed");
        }
    }

    @Transactional
    public PreKey claim(UUID recipientId) {
        return claim(recipientId, false);
    }

    @Transactional
    public PreKey claim(UUID recipientId, boolean fallback) {
        UUID device = contact(recipientId).deviceId();
        List<String> bundles = database.query("DELETE FROM prekeys WHERE (device_id, id) IN (SELECT device_id, id FROM prekeys WHERE device_id = ? AND expires_at > now() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING public_bundle::text",
                (row, index) -> row.getString(1), device);
        if (bundles.isEmpty() && fallback)
            bundles = database.query("SELECT public_bundle::text FROM fallback_prekeys WHERE device_id = ? AND expires_at > now()",
                    (row, index) -> row.getString(1), device);
        if (bundles.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "prekeys_unavailable");
        try { return json.readValue(bundles.get(0), PreKey.class); }
        catch (Exception failure) { throw new IllegalStateException("Invalid stored public bundle"); }
    }

    @Scheduled(fixedDelay = 60_000)
    public void expirePublicKeys() {
        database.update("DELETE FROM prekeys WHERE expires_at <= now()");
        database.update("DELETE FROM fallback_prekeys WHERE expires_at <= now()");
    }
}