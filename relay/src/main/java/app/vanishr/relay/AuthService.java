package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    public record Token(UUID userId, UUID deviceId, String accessToken, long expiresAt, String refreshToken, long refreshExpiresAt) {
        public Token(UUID userId, UUID deviceId, String accessToken, long expiresAt) { this(userId, deviceId, accessToken, expiresAt, null, 0); }
        @Override public String toString() { return "Token[redacted]"; }
    }
    public record Refresh(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken,
                          @Pattern(regexp = "[A-Za-z0-9_-]{43}") String nextRefreshToken) {
        @Override public String toString() { return "Refresh[redacted]"; }
    }
    private record StoredSession(Actor actor, UUID deviceVersion, String refreshKey) { }
    private record StoredRefresh(Actor actor, UUID deviceVersion, String accessKey) { }
    private static final Duration ACCESS_LIFETIME = Duration.ofHours(1);
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(30);
    private final AccountDirectory accounts;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
    private final SecureRandom random = new SecureRandom();
    private final DefaultRedisScript<Long> rotate = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> revoke = new DefaultRedisScript<>();

    public AuthService(AccountDirectory accounts, StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.accounts = accounts;
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        rotate.setLocation(new ClassPathResource("redis/auth-rotate.lua"));
        rotate.setResultType(Long.class);
        revoke.setLocation(new ClassPathResource("redis/auth-revoke.lua"));
        revoke.setResultType(Long.class);
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
        if (actor.deviceId() != null) return issueDevice(actor, accounts.version(actor.deviceId()), null, null, null);
        String token = randomToken();
        Duration lifetime = Duration.ofMinutes(5);
        StoredSession session = new StoredSession(actor, null, null);
        try { redis.opsForValue().set(tokenKey(token), json.writeValueAsString(session), lifetime); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Session serialization failed"); }
        return new Token(actor.userId(), null, token, clock.instant().plus(lifetime).toEpochMilli());
    }

    private String randomToken() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Arrays.fill(entropy, (byte) 0);
        return token;
    }

    private Token issueDevice(Actor actor, UUID version, String previousKey, String previousValue, String replacement) {
        String accessToken = randomToken();
        String refreshToken = replacement == null ? randomToken() : replacement;
        String accessKey = tokenKey(accessToken);
        String refreshKey = refreshKey(refreshToken);
        StoredSession session = new StoredSession(actor, version, refreshKey);
        StoredRefresh renewal = new StoredRefresh(actor, version, accessKey);
        long now = clock.millis();
        try {
            Long result = redis.execute(rotate, List.of(previousKey == null ? refreshKey : previousKey, accessKey, refreshKey),
                    previousValue == null ? "" : previousValue, json.writeValueAsString(session), json.writeValueAsString(renewal),
                    Long.toString(ACCESS_LIFETIME.toMillis()), Long.toString(REFRESH_LIFETIME.toMillis()));
            if (!Long.valueOf(1).equals(result)) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Session serialization failed"); }
        return new Token(actor.userId(), actor.deviceId(), accessToken, now + ACCESS_LIFETIME.toMillis(), refreshToken, now + REFRESH_LIFETIME.toMillis());
    }

    public Token refresh(Refresh request) {
        if (request == null || request.refreshToken() == null || !request.refreshToken().matches("[A-Za-z0-9_-]{43}"))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        if (request.nextRefreshToken() != null && (!request.nextRefreshToken().matches("[A-Za-z0-9_-]{43}")
            || request.nextRefreshToken().equals(request.refreshToken()))) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        String key = refreshKey(request.refreshToken());
        String value = redis.opsForValue().get(key);
        StoredRefresh stored;
        try { stored = value == null ? null : json.readValue(value, StoredRefresh.class); }
        catch (Exception failure) { throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required"); }
        if (stored == null || stored.actor() == null || stored.actor().deviceId() == null
                || stored.accessKey() == null || !stored.accessKey().startsWith("auth:") || !accounts.active(stored.actor(), stored.deviceVersion()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        return issueDevice(stored.actor(), stored.deviceVersion(), key, value, request.nextRefreshToken());
    }

    static String tokenKey(String token) { return "auth:" + RedisRelay.digest(token.getBytes(StandardCharsets.US_ASCII)); }
    static String refreshKey(String token) { return "refresh:" + RedisRelay.digest(token.getBytes(StandardCharsets.US_ASCII)); }

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

    public void revoke(String token) { redis.execute(revoke, List.of(tokenKey(token))); }

    public boolean activeSession(String sessionKey, Actor actor) {
        String stored = redis.opsForValue().get(sessionKey);
        if (stored == null) return false;
        try {
            StoredSession session = json.readValue(stored, StoredSession.class);
            return actor.equals(session.actor()) && accounts.active(actor, session.deviceVersion());
        } catch (Exception failure) { return false; }
    }
}