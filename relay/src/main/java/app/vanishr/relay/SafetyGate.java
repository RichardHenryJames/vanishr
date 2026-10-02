package app.vanishr.relay;

import jakarta.servlet.ServletException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.function.Supplier;

@Service
public class SafetyGate {
    private final JdbcTemplate database;

    public SafetyGate(JdbcTemplate database) { this.database = database; }

    @FunctionalInterface
    public interface Request { void run() throws IOException, ServletException; }

    private void lock(boolean exclusive) {
        // Nonblocking PostgreSQL locks coordinate cleanup with in-flight requests on every relay.
        String function = exclusive ? "pg_try_advisory_xact_lock" : "pg_try_advisory_xact_lock_shared";
        if (!Boolean.TRUE.equals(database.queryForObject("SELECT " + function + "(861904231)", Boolean.class)))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "safety_operation_in_progress");
    }

    @Transactional(rollbackFor = Exception.class)
    public void request(boolean exclusive, Request request) throws IOException, ServletException {
        lock(exclusive);
        request.run();
    }

    @Transactional
    public <T> T shared(Supplier<T> operation) {
        lock(false);
        return operation.get();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireExclusive() { lock(true); }
}
