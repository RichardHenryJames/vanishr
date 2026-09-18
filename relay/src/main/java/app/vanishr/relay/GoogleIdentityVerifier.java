package app.vanishr.relay;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Component
public class GoogleIdentityVerifier {
    private final String clientId;
    private final Set<String> allowedPresenters;
    private final GoogleIdTokenVerifier verifier;
    private final Clock clock;

    @Autowired
    public GoogleIdentityVerifier(@Value("${vanishr.google.web-client-id:}") String clientId,
                                  @Value("${vanishr.google.android-client-ids:}") String androidClientIds, Clock clock) {
        this(clientId, androidClientIds, clock, clientId.isBlank() ? null : new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(List.of(clientId)).setAcceptableTimeSkewSeconds(30).setClock(clock::millis).build());
    }

    GoogleIdentityVerifier(String clientId, String androidClientIds, Clock clock, GoogleIdTokenVerifier verifier) {
        if (!clientId.isEmpty() && !clientId.matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))
            throw new IllegalStateException("Invalid Google OAuth configuration");
        this.clientId = clientId;
        this.clock = clock;
        this.verifier = verifier;
        allowedPresenters = new HashSet<>();
        allowedPresenters.add(clientId);
        for (String presenter : androidClientIds.split(",")) {
            if (presenter.isBlank()) continue;
            if (!presenter.trim().matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))
                throw new IllegalStateException("Invalid Google OAuth configuration");
            allowedPresenters.add(presenter.trim());
        }
    }

    public String clientId() { return clientId; }
    public boolean enabled() { return verifier != null && !clientId.isEmpty(); }

    public String verify(String token, String nonce) {
        if (!enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_sign_in_unavailable");
        try {
            GoogleIdToken verified = verifier.verify(token);
            if (verified == null) throw new SecurityException();
            GoogleIdToken.Payload payload = verified.getPayload();
            long now = clock.instant().getEpochSecond();
            if (payload.getExpirationTimeSeconds() == null || payload.getExpirationTimeSeconds() <= now
                    || payload.getIssuedAtTimeSeconds() == null || payload.getIssuedAtTimeSeconds() < now - 600
                    || payload.getIssuedAtTimeSeconds() > now + 30 || payload.getNonce() == null
                    || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.US_ASCII), payload.getNonce().getBytes(StandardCharsets.US_ASCII))
                    || (payload.getAuthorizedParty() != null && !allowedPresenters.contains(payload.getAuthorizedParty()))
                    || payload.getSubject() == null || !payload.getSubject().matches("[A-Za-z0-9_-]{1,255}"))
                throw new SecurityException();
            return payload.getSubject();
        } catch (Exception failure) { throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_failed"); }
    }
}