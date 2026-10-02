package app.vanishr.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.vanishr.relay.RelayTypes.Actor;

@Service
public class SafetyReports {
    public enum Reason {
        SPAM, HARASSMENT, SEXUAL_CONTENT, CHILD_SAFETY, IMPERSONATION, THREATS, OTHER;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Reason parse(JsonNode value) {
            if (value == null || !value.isTextual()) throw new IllegalArgumentException("Invalid report reason");
            return Reason.valueOf(value.asText());
        }
    }
    public record Submission(@NotNull UUID targetId, @NotNull Reason reason, UUID messageId, UUID groupId) { }
    public record Accepted(UUID id, long expiresAt) { }
    public record Report(UUID id, UUID reporterId, UUID targetId, Reason reason, UUID messageId, UUID groupId,
                         long createdAt, long expiresAt) { }
    public static final long LIFETIME = Duration.ofDays(30).toMillis();
    private final AccountDirectory accounts;
    private final GroupDirectory groups;
    private final RedisRelay direct;
    private final GroupMessages groupMessages;
    private final RateLimiter rates;
    private final JdbcTemplate database;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final DefaultRedisScript<String> submit = new DefaultRedisScript<>();

    public SafetyReports(AccountDirectory accounts, GroupDirectory groups, RedisRelay direct, GroupMessages groupMessages,
                         RateLimiter rates, JdbcTemplate database, StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.accounts = accounts; this.groups = groups; this.direct = direct; this.groupMessages = groupMessages;
        this.rates = rates; this.database = database; this.redis = redis; this.json = json; this.clock = clock;
        submit.setLocation(new ClassPathResource("redis/safety-report.lua")); submit.setResultType(String.class);
    }

    public Accepted submit(Actor actor, Submission request) {
        if (actor.userId().equals(request.targetId())) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        accounts.contact(request.targetId());
        if (request.groupId() != null) {
            groups.withGroup(actor, request.groupId(), null, false, group -> {
                if (group.closed() || !group.member(request.targetId()).state().equals("ACTIVE")
                        || request.messageId() != null && !groupMessages.reportable(actor, request.targetId(), group.id(), request.messageId()))
                    throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
                return null;
            });
        } else if (request.messageId() != null && !direct.reportable(actor, request.targetId(), request.messageId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
        }
        if (!Boolean.TRUE.equals(database.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM admin_identity pin JOIN accounts a ON a.id = pin.user_id
                    WHERE a.user_type = 'ADMIN' AND a.deletion_state = 'ACTIVE')
                """, Boolean.class))) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "safety_review_unavailable");
        rates.require("safety:" + actor.userId(), 5, 86400);
        long now = clock.millis();
        Report report = new Report(UUID.randomUUID(), actor.userId(), request.targetId(), request.reason(), request.messageId(),
                request.groupId(), now, now + LIFETIME);
        String result;
        try {
            result = redis.execute(submit, List.of("safety-report:" + report.id(), "safety-reports"),
                    json.writeValueAsString(report), Long.toString(now), Long.toString(report.expiresAt()), report.id().toString());
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Report serialization failed"); }
        if ("FULL".equals(result)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "safety_queue_full");
        if (!"OK".equals(result)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "safety_review_unavailable");
        return new Accepted(report.id(), report.expiresAt());
    }

    private void admin(Actor actor) {
        if (accounts.accountType(actor.userId()).userType() != AccountDirectory.UserType.ADMIN)
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_required");
    }

    public List<Report> list(Actor actor) {
        admin(actor);
        redis.opsForZSet().removeRangeByScore("safety-reports", 0, clock.millis());
        var ids = redis.opsForZSet().rangeByScore("safety-reports", clock.millis() + 1, Double.POSITIVE_INFINITY, 0, 50);
        List<Report> result = new ArrayList<>();
        if (ids == null) return result;
        for (String id : ids) {
            String value = redis.opsForValue().get("safety-report:" + id);
            if (value == null) { redis.opsForZSet().remove("safety-reports", id); continue; }
            try {
                Report report = json.readValue(value, Report.class);
                if (report.expiresAt() > clock.millis()) result.add(report);
            } catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid safety report state"); }
        }
        return result;
    }

    public void review(Actor actor, UUID id) {
        admin(actor);
        redis.delete("safety-report:" + id);
        redis.opsForZSet().remove("safety-reports", id.toString());
    }
}
