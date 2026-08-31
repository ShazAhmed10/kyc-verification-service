package dev.portfolio.verifyflow;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
@EnableScheduling
public class VerifyFlowApplication {

  public static void main(String[] args) {
    SpringApplication.run(VerifyFlowApplication.class, args);
  }

  /**
   * Injected everywhere instead of calling Instant.now() inline. Timing is load-bearing in this
   * project - expiry, stuck-detection and the cohort comparison all key off it - so tests need to be
   * able to control it rather than sleep.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
