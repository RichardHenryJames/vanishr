package app.vanishr.relay;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static app.vanishr.relay.RelayTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class RelayPolicyTest {
    private final Instant now = Instant.parse("2026-09-16T00:00:00Z");

    @Test void acceptsOnlyBoundedFutureDeadlinesForEveryPolicy() {
        for (Expiry expiry : Expiry.values()) {
            assertDoesNotThrow(() -> RelayPolicy.deadline(expiry, now.plus(expiry.lifetime).toEpochMilli(), now));
            assertThrows(ApiException.class, () -> RelayPolicy.deadline(expiry, now.toEpochMilli(), now));
            assertThrows(ApiException.class, () -> RelayPolicy.deadline(expiry, now.plus(expiry.lifetime).plusMillis(1).toEpochMilli(), now));
        }
    }

    @Test void rejectsRedisPersistenceAndReplication() {
        java.util.Properties configuration = new java.util.Properties();
        configuration.setProperty("appendonly", "no");
        configuration.setProperty("save", "");
        configuration.setProperty("maxmemory", "67108864");
        configuration.setProperty("maxmemory-policy", "noeviction");
        java.util.Properties replication = new java.util.Properties();
        replication.setProperty("role", "master");
        replication.setProperty("connected_slaves", "0");
        assertDoesNotThrow(() -> DeploymentGuard.requireEphemeral(configuration, replication));
        configuration.setProperty("appendonly", "yes");
        assertThrows(IllegalStateException.class, () -> DeploymentGuard.requireEphemeral(configuration, replication));
        configuration.setProperty("appendonly", "no");
        replication.setProperty("connected_slaves", "1");
        assertThrows(IllegalStateException.class, () -> DeploymentGuard.requireEphemeral(configuration, replication));
    }
}