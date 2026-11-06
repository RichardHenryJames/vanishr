package app.vanishr.relay;

import org.junit.jupiter.api.Test;
import java.time.Duration;
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

    @Test void aDeadlineEarlierThanTheKindAllowsIsAcceptedWhichIsHowTwelveHoursTravels() {
        long twelveHours = Duration.ofHours(12).toMillis();
        assertDoesNotThrow(() -> RelayPolicy.deadline(Expiry.HOURS_24, now.toEpochMilli() + twelveHours, now));
        // No shorter kind can carry it, so the app sends it as the 24-hour kind with the earlier deadline.
        assertThrows(ApiException.class, () -> RelayPolicy.deadline(Expiry.HOURS_6, now.toEpochMilli() + twelveHours, now));
        assertThrows(ApiException.class, () -> RelayPolicy.deadline(Expiry.HOUR_1, now.toEpochMilli() + twelveHours, now));
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