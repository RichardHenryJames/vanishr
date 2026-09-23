package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public class GenericNotifier {
    public record Destination(UUID userId, UUID deviceId, UUID conversationId, UUID messageId, long expiresAt) { }
    private record Registration(String token, boolean routeHints) {
        @Override public String toString() { return "PushRegistration[redacted]"; }
    }
    private record Route(String digest, Destination destination) { }
    private static final long ROUTE_LIFETIME = 300_000L;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RealtimeHub realtime;
    private final String projectId;
    private final GoogleCredentials credentials;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> { Thread thread = new Thread(runnable, "generic-push"); thread.setDaemon(true); return thread; });

    public GenericNotifier(StringRedisTemplate redis, ObjectMapper json, RealtimeHub realtime,
                           @Value("${vanishr.push.enabled:false}") boolean enabled,
                           @Value("${vanishr.push.project-id:}") String projectId) {
        this.redis = redis;
        this.json = json;
        this.realtime = realtime;
        this.projectId = projectId;
        if (enabled && !projectId.matches("[a-z][a-z0-9-]{4,62}")) throw new IllegalStateException("A valid FCM project is required");
        try { credentials = enabled ? GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/firebase.messaging") : null; }
        catch (Exception failure) { throw new IllegalStateException("FCM credentials are unavailable"); }
    }

    public void register(UUID deviceId, String token) { redis.opsForValue().set("push:" + deviceId, token, Duration.ofHours(24)); }
    public void register(UUID deviceId, String token, boolean routeHints) {
        if (!routeHints) { register(deviceId, token); return; }
        try { redis.opsForValue().set("push:" + deviceId, json.writeValueAsString(new Registration(token, true)), Duration.ofHours(24)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Push registration failed"); }
    }
    public void unregister(UUID deviceId) { redis.delete("push:" + deviceId); redis.delete("notification:" + deviceId); }

    String reference(Destination destination) {
        long now = System.currentTimeMillis();
        if (destination == null || destination.userId() == null || destination.deviceId() == null || destination.conversationId() == null
                || destination.messageId() == null || destination.expiresAt() <= now) return null;
        byte[] entropy = new byte[32]; new SecureRandom().nextBytes(entropy);
        String reference = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Arrays.fill(entropy, (byte) 0);
        long deadline = Math.min(destination.expiresAt(), now + ROUTE_LIFETIME);
        Destination bounded = new Destination(destination.userId(), destination.deviceId(), destination.conversationId(), destination.messageId(), deadline);
        try { redis.opsForValue().set("notification:" + destination.deviceId(), json.writeValueAsString(new Route(
                RedisRelay.digest(reference.getBytes(StandardCharsets.US_ASCII)), bounded)), Duration.ofMillis(deadline - now)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Notification routing failed"); }
        return reference;
    }

    public Destination resolve(RelayTypes.Actor actor, String reference) {
        if (actor == null || actor.deviceId() == null || reference == null || !reference.matches("[A-Za-z0-9_-]{43}"))
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found");
        String stored = redis.opsForValue().get("notification:" + actor.deviceId());
        Route route;
        try { route = stored == null ? null : json.readValue(stored, Route.class); }
        catch (Exception failure) { throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found"); }
        if (route == null || route.destination() == null || !actor.userId().equals(route.destination().userId())
                || !actor.deviceId().equals(route.destination().deviceId()) || route.destination().expiresAt() <= System.currentTimeMillis()
                || !RedisRelay.digest(reference.getBytes(StandardCharsets.US_ASCII)).equals(route.digest()))
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found");
        return route.destination();
    }

    static Map<String, Object> pushEnvelope(String token) {
        return pushEnvelope(token, null);
    }

    static Map<String, Object> pushEnvelope(String token, String reference) {
        if (reference != null && !reference.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid notification reference");
        Map<String, String> data = reference == null ? Map.of("event", "new_message") : Map.of("event", "new_message", "reference", reference);
        return Map.of("message", Map.of("token", token,
                "data", data,
                "android", Map.of("priority", "HIGH", "ttl", "60s", "collapse_key", "new_message")));
    }

    public void wake(UUID deviceId) {
        wake(deviceId, null);
    }

    public void wake(Destination destination) {
        wake(destination.deviceId(), destination);
    }

    private void wake(UUID deviceId, Destination destination) {
        realtime.wake(deviceId);
        if (credentials == null) return;
        try { executor.execute(() -> send(deviceId, destination)); }
        catch (RejectedExecutionException failure) { }
    }

    private void send(UUID deviceId, Destination destination) {
        try {
            String token = redis.opsForValue().get("push:" + deviceId);
            if (token == null) return;
            boolean routeHints = false;
            if (token.startsWith("{")) {
                Registration registration = json.readValue(token, Registration.class);
                token = registration.token(); routeHints = registration.routeHints();
            }
            if (destination != null && destination.expiresAt() <= System.currentTimeMillis()) return;
            credentials.refreshIfExpired();
            String reference = routeHints && destination != null ? reference(destination) : null;
            String body = json.writeValueAsString(pushEnvelope(token, reference));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}