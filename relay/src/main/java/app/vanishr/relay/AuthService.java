package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static app.vanishr.relay.RelayTypes.*;

@Service
public class AuthService {
    public record Login(@NotNull @Pattern(regexp = "[a-z0-9_]{3,32}") String handle,
                        @NotNull @Size(min = 16, max = 64) String password, UUID deviceId) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Token(UUID userId, UUID deviceId, String accessToken, long expiresAt) {
        @Override public String toString() { return "Token[redacted]"; }
    }
    private record StoredSession(Actor actor, UUID deviceVersion) { }
    private final AccountDirectory accounts;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
    private final SecureRandom random = new SecureRandom();

    public AuthService(AccountDirectory accounts, StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.accounts = accounts;
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    private void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_password_length");
    }

    public Token register(Login login) {
        validatePassword(login.password());
        UUID userId = accounts.create(login.handle(), passwords.encode(login.password()));
        return issue(new Actor(userId, null));
    }

    public Token login(Login login) {
        validatePassword(login.password());
        Optional<AccountDirectory.Credentials> credentials = accounts.credentials(login.handle());
        boolean valid = passwords.matches(login.password(), credentials.map(AccountDirectory.Credentials::passwordHash).orElse(dummyHash));
        if (!valid || credentials.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
        UUID userId = credentials.get().id();
        if (login.deviceId() != null && !accounts.active(userId, login.deviceId())) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
        return issue(new Actor(userId, login.deviceId()));
    }

    public Token issue(Actor actor) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Arrays.fill(entropy, (byte) 0);
        Duration lifetime = Duration.ofMinutes(actor.deviceId() == null ? 5 : 60);
        StoredSession session = new StoredSession(actor, actor.deviceId() == null ? null : accounts.version(actor.deviceId()));
        try { redis.opsForValue().set(tokenKey(token), json.writeValueAsString(session), lifetime); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Session serialization failed"); }
        return new Token(actor.userId(), actor.deviceId(), token, clock.instant().plus(lifetime).toEpochMilli());
    }

    static String tokenKey(String token) { return "auth:" + RedisRelay.digest(token.getBytes(StandardCharsets.US_ASCII)); }

    public Actor authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        String stored = redis.opsForValue().get(tokenKey(token));
        if (stored == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        StoredSession session;
        try { session = json.readValue(stored, StoredSession.class); }
        catch (Exception failure) { throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required"); }
        Actor actor = session.actor();
        if (actor.deviceId() != null && !accounts.active(actor, session.deviceVersion()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        return actor;
    }

    public void revoke(String token) { redis.delete(tokenKey(token)); }

    public boolean activeSession(String sessionKey, Actor actor) {
        String stored = redis.opsForValue().get(sessionKey);
        if (stored == null) return false;
        try {
            StoredSession session = json.readValue(stored, StoredSession.class);
            return actor.equals(session.actor()) && accounts.active(actor, session.deviceVersion());
        } catch (Exception failure) { return false; }
    }
}