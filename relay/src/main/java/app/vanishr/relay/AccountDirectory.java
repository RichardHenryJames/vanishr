package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

@Repository
public class AccountDirectory {
    public record Contact(UUID userId, UUID deviceId, String identityKey) { }
    public record GoogleAccount(UUID userId, String handle) { }
    public record Username(UUID userId, String handle) { }
    public record UsernameChange(@NotNull @Pattern(regexp = "[a-z0-9_]{3,32}") String handle) { }
    public record Profile(UUID userId, String handle, String displayName) { }
    public record ProfileChange(@NotBlank @Size(max = 40) String displayName) { }
    public enum UserType { USER, ADMIN }
    public record AccountType(UUID userId, UserType userType) { }
    public record DeviceRequest(@NotNull UUID deviceId, @NotNull @Size(min = 33, max = 33) byte[] identityKey, boolean replaceExisting) { }
    public record PreKey(@Min(1) @Max(16380) int registrationId, @Positive int preKeyId,
                         @NotNull @Size(min = 33, max = 33) byte[] preKey, @Positive int signedPreKeyId,
                         @NotNull @Size(min = 33, max = 33) byte[] signedPreKey,
                         @NotNull @Size(min = 64, max = 64) byte[] signedPreKeySignature,
                         @NotNull @Size(min = 33, max = 33) byte[] identityKey, @Positive int kyberPreKeyId,
                         @NotNull @Size(min = 1569, max = 1569) byte[] kyberPreKey,
                         @NotNull @Size(min = 64, max = 64) byte[] kyberPreKeySignature) { }
    public record KeyUpload(@NotNull @Size(min = 1, max = 32) List<@Valid PreKey> keys) { }
    public record Credentials(UUID id, String passwordHash) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final Clock clock;

    public AccountDirectory(JdbcTemplate database, ObjectMapper json, Clock clock) {
        this.database = database;
        this.json = json;
        this.clock = clock;
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
        return database.query("SELECT id, handle, display_name FROM accounts WHERE id = ?",
                (row, index) -> new Profile(row.getObject("id", UUID.class), row.getString("handle"), row.getString("display_name")), userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
    }

    public AccountType accountType(UUID userId) {
        return database.query("SELECT id, user_type FROM accounts WHERE id = ?",
                (row, index) -> new AccountType(row.getObject("id", UUID.class), UserType.valueOf(row.getString("user_type"))), userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
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
        if (subject == null || !subject.matches("[A-Za-z0-9_-]{1,255}"))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
        UUID userId = UUID.randomUUID();
        String handle = "g_" + userId.toString().replace("-", "").substring(0, 28);
        database.update("INSERT INTO accounts(id, handle, google_subject) VALUES (?, ?, ?) ON CONFLICT (google_subject) DO NOTHING",
                userId, handle, subject);
        return database.queryForObject("SELECT id, handle FROM accounts WHERE google_subject = ?",
                (row, index) -> new GoogleAccount(row.getObject("id", UUID.class), row.getString("handle")), subject);
    }

    public boolean active(UUID userId, UUID deviceId) {
        return Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE user_id = ? AND id = ?)", Boolean.class, userId, deviceId));
    }

    public UUID version(UUID deviceId) {
        return database.queryForObject("SELECT auth_version FROM devices WHERE id = ?", UUID.class, deviceId);
    }

    public boolean active(RelayTypes.Actor actor, UUID version) {
        return Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE user_id = ? AND id = ? AND auth_version = ?)",
                Boolean.class, actor.userId(), actor.deviceId(), version));
    }

    @Transactional
    public void registerDevice(UUID userId, DeviceRequest request) {
        database.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", UUID.class, userId);
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
        return findContact("SELECT user_id, id, identity_key FROM devices WHERE user_id = ?", userId);
    }

    public Contact contact(String handle) {
        return findContact("SELECT devices.user_id, devices.id, devices.identity_key FROM devices JOIN accounts ON accounts.id = devices.user_id WHERE accounts.handle = ?", handle);
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
            if (!contact.identityKey().equals(Base64.getEncoder().encodeToString(key.identityKey()))
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

    @Transactional
    public PreKey claim(UUID recipientId) {
        UUID device = contact(recipientId).deviceId();
        List<String> bundles = database.query("DELETE FROM prekeys WHERE (device_id, id) IN (SELECT device_id, id FROM prekeys WHERE device_id = ? AND expires_at > now() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING public_bundle::text",
                (row, index) -> row.getString(1), device);
        if (bundles.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "prekeys_unavailable");
        try { return json.readValue(bundles.get(0), PreKey.class); }
        catch (Exception failure) { throw new IllegalStateException("Invalid stored public bundle"); }
    }

    @Scheduled(fixedDelay = 60_000)
    public void expirePublicKeys() { database.update("DELETE FROM prekeys WHERE expires_at <= now()"); }
}