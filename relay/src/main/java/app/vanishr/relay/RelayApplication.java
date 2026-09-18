package app.vanishr.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class RelayApplication {
    public static void main(String[] args) { SpringApplication.run(RelayApplication.class, args); }
    @Bean public Clock clock() { return Clock.systemUTC(); }
}