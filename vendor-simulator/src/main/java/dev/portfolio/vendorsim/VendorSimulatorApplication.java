package dev.portfolio.vendorsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * A stand-in for the fictional "VerifyPro" identity-verification vendor.
 *
 * <p>This exists so the verification service talks to something over a real socket rather than a mock
 * bean. That matters for this project: the behaviours worth demonstrating - a callback that never
 * arrives, a result endpoint that is slow, a verdict that changes from inconclusive to approved - are
 * all things a mock would let you assert but not actually experience.
 */
@SpringBootApplication
@EnableAsync
public class VendorSimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(VendorSimulatorApplication.class, args);
  }
}
