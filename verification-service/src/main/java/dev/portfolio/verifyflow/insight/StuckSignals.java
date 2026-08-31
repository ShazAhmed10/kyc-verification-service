package dev.portfolio.verifyflow.insight;

import dev.portfolio.verifyflow.domain.VerificationState;
import java.time.Duration;
import java.util.UUID;

/**
 * Facts about one verification, all computed in plain Java.
 *
 * <p>Two separate judgements are reported and they are not the same thing:
 *
 * <ul>
 *   <li>{@code stuck} is <b>absolute</b> - this request has been transient longer than the configured
 *       service expectation. This is the gate, because it still fires when every request is slow.
 *   <li>{@code cohortOutlier} is <b>relative</b> - this request is much slower than its peers. Useful
 *       context for an operator (is it just me, or is everything bad?) but never the trigger.
 * </ul>
 *
 * <p>Getting this the wrong way round is an easy mistake: a purely relative threshold goes blind during
 * a broad outage, because the median it compares against degrades with the population.
 *
 * <p>This record is the boundary between deterministic analysis and language generation. Everything
 * here is measured or derived from the database - no model is involved in producing it, so every field
 * is unit-testable and will say the same thing twice about the same data.
 *
 * <p>The model's job is only to turn this into prose. Asking it to do the arithmetic instead would make
 * the anomaly detection untestable and occasionally wrong, which is the opposite of what you want from
 * something that tells an operator whether to intervene.
 */
public record StuckSignals(
    UUID verificationId,
    VerificationState state,
    boolean settled,
    Duration timeInCurrentState,
    Duration cohortMedianInState,
    Duration stuckThreshold,
    boolean stuck,
    boolean cohortOutlier,
    boolean callbackEverReceived,
    int reconcileAttempts,
    boolean sessionExpired,
    int transitionCount,
    int cohortSize) {

  /** How much longer than its cohort's median this request has been sitting, or empty if no cohort. */
  public java.util.OptionalDouble ratioToCohortMedian() {
    if (cohortMedianInState.isZero() || cohortMedianInState.isNegative()) {
      return java.util.OptionalDouble.empty();
    }
    return java.util.OptionalDouble.of(
        (double) timeInCurrentState.toSeconds() / cohortMedianInState.toSeconds());
  }

  /**
   * The most likely explanation, inferred from the signals alone.
   *
   * <p>Deliberately here rather than in the prompt: a short, deterministic hypothesis is worth having
   * even when the model is unavailable, and it gives the model a starting point rather than asking it
   * to guess from raw numbers.
   */
  public String likelyCause() {
    if (settled) {
      return "none - request has settled";
    }
    if (!stuck) {
      return "none - within expected time for its state";
    }
    if (state == VerificationState.PENDING && !callbackEverReceived && reconcileAttempts == 0) {
      return "vendor callback never arrived and no reconciliation has run";
    }
    if (state == VerificationState.PENDING && !callbackEverReceived) {
      return "vendor callback never arrived; reconciliation ran but did not resolve";
    }
    if (state == VerificationState.IN_REVIEW && reconcileAttempts > 0) {
      return "vendor accepted the request but has not returned a final verdict";
    }
    if (sessionExpired) {
      return "vendor session expired before the subject completed it";
    }
    return "transient state held longer than the cohort, cause not determined from signals";
  }
}
