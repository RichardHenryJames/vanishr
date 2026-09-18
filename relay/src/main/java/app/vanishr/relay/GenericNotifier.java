package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public class GenericNotifier {
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
    public void unregister(UUID deviceId) { redis.delete("push:" + deviceId); }

    static Map<String, Object> pushEnvelope(String token) {
        return Map.of("message", Map.of("token", token,
                "data", Map.of("event", "new_message"),
                "android", Map.of("priority", "HIGH", "ttl", "60s", "collapse_key", "new_message")));
    }

    public void wake(UUID deviceId) {
        realtime.wake(deviceId);
        if (credentials == null) return;
        try { executor.execute(() -> send(deviceId)); }
        catch (RejectedExecutionException failure) { }
    }

    private void send(UUID deviceId) {
        try {
            String token = redis.opsForValue().get("push:" + deviceId);
            if (token == null) return;
            credentials.refreshIfExpired();
                String body = json.writeValueAsString(pushEnvelope(token));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}