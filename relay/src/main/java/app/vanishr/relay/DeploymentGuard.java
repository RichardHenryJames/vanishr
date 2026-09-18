package app.vanishr.relay;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.Properties;

@Component
public class DeploymentGuard implements ApplicationRunner {
    private final StringRedisTemplate redis;
    private final Environment environment;
    public DeploymentGuard(StringRedisTemplate redis, Environment environment) { this.redis = redis; this.environment = environment; }

    static void requireEphemeral(Properties persistence, Properties replication) {
        if (!"no".equals(persistence.getProperty("appendonly")) || !"".equals(persistence.getProperty("save"))
                || !"noeviction".equals(persistence.getProperty("maxmemory-policy"))
                || Long.parseLong(persistence.getProperty("maxmemory", "0")) <= 0
                || !"master".equals(replication.getProperty("role")) || !"0".equals(replication.getProperty("connected_slaves")))
            throw new IllegalStateException("Redis must be bounded, nonpersistent and nonreplicated");
    }

    @Override public void run(ApplicationArguments arguments) {
        if (!environment.matchesProfiles("test")) {
            if (!environment.getProperty("server.ssl.enabled", Boolean.class, false)
                    || !environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false)
                    || !environment.getProperty("spring.datasource.url", "").contains("sslmode=verify-full"))
                throw new IllegalStateException("Production connections must use verified TLS");
        }
        try (var connection = redis.getConnectionFactory().getConnection()) {
            Properties configuration = new Properties();
            for (String key : new String[]{"appendonly", "save", "maxmemory", "maxmemory-policy"})
                configuration.putAll(connection.serverCommands().getConfig(key));
            requireEphemeral(configuration, connection.serverCommands().info("replication"));
        }
    }
}