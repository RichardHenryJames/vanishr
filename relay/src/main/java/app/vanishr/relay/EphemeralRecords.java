package app.vanishr.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Repository
public class EphemeralRecords {
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "local ttl = redis.call('PTTL', KEYS[1]); if ttl <= 0 or ttl > 86400000 then return 0 end; "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL'); return 1", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public EphemeralRecords(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }

    public void keys(String prefix, Consumer<String> action) {
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(128).build())) {
            cursor.forEachRemaining(action);
        }
    }

    public void visit(String prefix, BiConsumer<String, JsonNode> action) {
        keys(prefix, key -> {
            JsonNode value = get(key);
            if (value != null) action.accept(key, value);
        });
    }

    public JsonNode get(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) return null; // Expiry may remove an item between SCAN and GET.
        try { return json.readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid ephemeral cleanup state"); }
    }

    public void replace(String key, JsonNode before, JsonNode after) {
        String current = redis.opsForValue().get(key);
        if (current == null) return;
        try {
            if (!json.readTree(current).equals(before)) throw new IllegalStateException("Ephemeral cleanup conflict");
            Long replaced = redis.execute(REPLACE, List.of(key), current, json.writeValueAsString(after));
            if (!Long.valueOf(1).equals(replaced) && Boolean.TRUE.equals(redis.hasKey(key)))
                throw new IllegalStateException("Ephemeral cleanup conflict");
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid ephemeral cleanup state"); }
    }

    public static UUID id(JsonNode value, String field) {
        JsonNode identifier = value.get(field);
        return identifier == null || identifier.isNull() ? null : UUID.fromString(identifier.asText());
    }
}
