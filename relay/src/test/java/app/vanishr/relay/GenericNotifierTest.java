package app.vanishr.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenericNotifierTest {
    @Test void providerPayloadContainsOnlyAGenericEventWithABoundedLifetime() throws Exception {
        var actual = new ObjectMapper().valueToTree(GenericNotifier.pushEnvelope("synthetic-provider-token"));
        var expected = new ObjectMapper().valueToTree(Map.of("message", Map.of(
                "token", "synthetic-provider-token", "data", Map.of("event", "new_message"),
                "android", Map.of("priority", "HIGH", "ttl", "60s", "collapse_key", "new_message"))));
        assertEquals(expected, actual);
        assertFalse(actual.get("message").has("notification"));
        assertEquals(1, actual.get("message").get("data").size());
    }

    @Test void tokenRegistrationHasAnAtomicExpiryAndOptOutRemovesIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        GenericNotifier notifier = new GenericNotifier(redis, new ObjectMapper(), mock(RealtimeHub.class), false, "");
        UUID device = UUID.randomUUID();
        try {
            notifier.register(device, "synthetic-provider-token");
            verify(values).set("push:" + device, "synthetic-provider-token", Duration.ofHours(24));
            notifier.unregister(device);
            verify(redis).delete("push:" + device);
        } finally { notifier.close(); }
    }

    @Test void unconfiguredPushStillWakesRealtimeWithoutReadingProviderTokens() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RealtimeHub realtime = mock(RealtimeHub.class);
        GenericNotifier notifier = new GenericNotifier(redis, new ObjectMapper(), realtime, false, "");
        UUID device = UUID.randomUUID();
        try {
            notifier.wake(device);
            verify(realtime).wake(device);
            verifyNoInteractions(redis);
        } finally { notifier.close(); }
    }

    @Test void enablingPushWithoutAValidProjectFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new GenericNotifier(mock(StringRedisTemplate.class),
                new ObjectMapper(), mock(RealtimeHub.class), true, ""));
    }
}