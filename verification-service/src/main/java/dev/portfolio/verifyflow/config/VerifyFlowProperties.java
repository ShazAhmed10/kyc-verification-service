package dev.portfolio.verifyflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All tunables in one typed tree, bound from {@code verifyflow.*}.
 *
 * <p>Bound as a record so the values are immutable after startup and a typo in a key name is a
 * startup failure rather than a silently-absent value. Nothing here carries a committed secret: the
 * vendor API key and the webhook shared secret default to empty and must come from the environment.
 */
@ConfigurationProperties(prefix = "verifyflow")
public record VerifyFlowProperties(
    Vendor vendor, Webhook webhook, Reconcile reconcile, Expiry expiry, ReadModel readmodel, Insight insight, Llm llm) {

  public record Vendor(
      @DefaultValue("http://localhost:9090") String baseUrl,
      @DefaultValue("") String apiKey,
      @DefaultValue("3s") Duration connectTimeout,
      @DefaultValue("10s") Duration readTimeout) {}

  public record Webhook(
      @DefaultValue("") String sharedSecret, @DefaultValue("true") boolean requireSecret) {}

  public record Reconcile(
      @DefaultValue("verifyflow.reconcile") String queue,
      @DefaultValue("verifyflow.reconcile.exchange") String exchange,
      @DefaultValue("reconcile") String routingKey,
      @DefaultValue("verifyflow.reconcile.dlq") String dlq) {}

  public record Expiry(
      @DefaultValue("PT15M") Duration transientTtl, @DefaultValue("60000") long sweepIntervalMs) {}

  public record ReadModel(@DefaultValue("PT30S") Duration maxAge) {}

  public record Insight(
      @DefaultValue("3.0") double stuckMultiplier, @DefaultValue("PT5M") Duration stuckFloor) {}

  public record Llm(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("") String apiKey,
      @DefaultValue("claude-opus-5") String model,
      @DefaultValue("900") int maxTokens,
      @DefaultValue("30s") Duration timeout) {

    /** The AI endpoint degrades to a deterministic report when this is false. */
    public boolean usable() {
      return enabled && apiKey != null && !apiKey.isBlank();
    }
  }
}
