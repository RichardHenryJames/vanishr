package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoogleAuthTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
    private final String audience = "test-web.apps.googleusercontent.com";
    private final String androidId = "test-android.apps.googleusercontent.com";

    private String signed(KeyPair pair, String issuer, String target, long expiry, String nonce, String presenter) throws Exception {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload().setIssuer(issuer).setSubject("123456789")
                .setAudience(target).setIssuedAtTimeSeconds(clock.instant().getEpochSecond()).setExpirationTimeSeconds(expiry)
                .setNonce(nonce).setAuthorizedParty(presenter);
        return JsonWebSignature.signUsingRsaSha256(pair.getPrivate(), GsonFactory.getDefaultInstance(),
                new JsonWebSignature.Header().setAlgorithm("RS256"), payload);
    }

    @Test void officialVerifierRejectsForgedExpiredWrongAudienceIssuerNonceAndPresenter() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair google = generator.generateKeyPair();
        GooglePublicKeysManager keys = mock(GooglePublicKeysManager.class);
        when(keys.getJsonFactory()).thenReturn(GsonFactory.getDefaultInstance());
        when(keys.getPublicKeys()).thenReturn(List.of(google.getPublic()));
        GoogleIdTokenVerifier library = new GoogleIdTokenVerifier.Builder(keys).setAudience(List.of(audience))
                .setClock(clock::millis).setAcceptableTimeSkewSeconds(30).build();
        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier(audience, androidId, clock, library);
        long expiry = clock.instant().getEpochSecond() + 300;
        assertEquals("123456789", verifier.verify(signed(google, "https://accounts.google.com", audience, expiry, "nonce", androidId), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify(signed(generator.generateKeyPair(), "https://accounts.google.com", audience, expiry, "nonce", androidId), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify(signed(google, "https://attacker.example", audience, expiry, "nonce", androidId), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify(signed(google, "https://accounts.google.com", "other-client", expiry, "nonce", androidId), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify(signed(google, "https://accounts.google.com", audience, expiry - 301, "nonce", androidId), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify(signed(google, "https://accounts.google.com", audience, expiry, "other-nonce", androidId), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify(signed(google, "https://accounts.google.com", audience, expiry, "nonce", "attacker"), "nonce"));
        assertThrows(ApiException.class, () -> verifier.verify("invalid-token", "nonce"));
    }

    @Test void challengeHasAtomicTtlAndCanBeExchangedOnlyOnce() {
        GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
        when(verifier.enabled()).thenReturn(true); when(verifier.clientId()).thenReturn(audience);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> pending = new HashMap<>();
        doAnswer(call -> { pending.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));
        when(values.getAndDelete(anyString())).thenAnswer(call -> pending.remove(call.getArgument(0)));
        AccountDirectory accounts = mock(AccountDirectory.class); AuthService auth = mock(AuthService.class);
        UUID user = UUID.randomUUID();
        when(accounts.googleAccount("subject")).thenReturn(new AccountDirectory.GoogleAccount(user, "g_test"));
        when(verifier.verify(eq("test-token"), anyString())).thenReturn("subject");
        GoogleAuth google = new GoogleAuth(verifier, accounts, auth, redis, new ObjectMapper(), clock);
        GoogleAuth.Challenge challenge = google.challenge(new GoogleAuth.Start(null));
        assertEquals(43, challenge.id().length()); assertEquals(43, challenge.nonce().length());
        assertEquals(clock.millis() + 300_000, challenge.expiresAt());
        assertEquals("g_test", google.signIn(new GoogleAuth.SignIn(challenge.id(), "test-token")).handle());
        assertThrows(ApiException.class, () -> google.signIn(new GoogleAuth.SignIn(challenge.id(), "test-token")));
        verify(verifier, times(1)).verify("test-token", challenge.nonce());
        verify(auth).issue(new RelayTypes.Actor(user, null));
    }

    @Test void absentConfigurationNeverEnablesGoogleOrTouchesStorage() {
        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier("", "", clock);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GoogleAuth auth = new GoogleAuth(verifier, mock(AccountDirectory.class), mock(AuthService.class), redis, new ObjectMapper(), clock);
        assertFalse(verifier.enabled());
        assertThrows(ApiException.class, () -> auth.challenge(new GoogleAuth.Start(null)));
        assertThrows(ApiException.class, () -> auth.signIn(new GoogleAuth.SignIn("id", "token")));
        verifyNoInteractions(redis);
    }
}