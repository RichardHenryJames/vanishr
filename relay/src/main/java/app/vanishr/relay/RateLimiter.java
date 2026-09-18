package app.vanishr.relay;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class RateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return count", Long.class);
    private final StringRedisTemplate redis;
    public RateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void require(String bucket, int maximum, int seconds) {
        Long count = redis.execute(INCREMENT, List.of("rate:" + bucket), Integer.toString(seconds));
        if (count == null || count > maximum) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
    }
}