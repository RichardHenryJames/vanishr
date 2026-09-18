package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

@RestController
public class GoogleAuth {
    public record Start(UUID deviceId) { }
    public record Challenge(String id, String nonce, String clientId, long expiresAt) {
        @Override public String toString() { return "Challenge[redacted]"; }
    }
    public record SignIn(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String challengeId,
                         @NotNull @Size(min = 100, max = 16384) String idToken) {
        @Override public String toString() { return "SignIn[redacted]"; }
    }
    public record SignedIn(AuthService.Token session, String handle) {
        @Override public String toString() { return "SignedIn[redacted]"; }
    }
    private record Pending(UUID deviceId, String nonce, long expiresAt) { }
    private final GoogleIdentityVerifier verifier;
    private final AccountDirectory accounts;
    private final AuthService auth;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public GoogleAuth(GoogleIdentityVerifier verifier, AccountDirectory accounts, AuthService auth,
                      StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.verifier = verifier; this.accounts = accounts; this.auth = auth;
        this.redis = redis; this.json = json; this.clock = clock;
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String key(String id) { return "google:" + RedisRelay.digest(id.getBytes(StandardCharsets.US_ASCII)); }

    @PostMapping("/auth/google/challenge")
    public Challenge challenge(@Valid @RequestBody Start request) {
        if (!verifier.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_sign_in_unavailable");
        String id = randomValue();
        String nonce = randomValue();
        long deadline = clock.instant().plusSeconds(300).toEpochMilli();
        try { redis.opsForValue().set(key(id), json.writeValueAsString(new Pending(request.deviceId(), nonce, deadline)), Duration.ofMinutes(5)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Challenge serialization failed"); }
        return new Challenge(id, nonce, verifier.clientId(), deadline);
    }

    @PostMapping("/auth/google")
    public SignedIn signIn(@Valid @RequestBody SignIn request) {
        if (!verifier.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_sign_in_unavailable");
        String stored = redis.opsForValue().getAndDelete(key(request.challengeId()));
        if (stored == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
        Pending pending;
        try { pending = json.readValue(stored, Pending.class); }
        catch (Exception failure) { throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed"); }
        if (pending.expiresAt() <= clock.millis()) throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
        String subject = verifier.verify(request.idToken(), pending.nonce());
        AccountDirectory.GoogleAccount account = accounts.googleAccount(subject);
        if (pending.deviceId() != null && !accounts.active(account.userId(), pending.deviceId()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed");
        return new SignedIn(auth.issue(new RelayTypes.Actor(account.userId(), pending.deviceId())), account.handle());
    }
}