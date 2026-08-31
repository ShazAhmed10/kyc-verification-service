package dev.portfolio.verifyflow.insight;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the {@link StuckSignals} for a verification.
 *
 * <p>Detection is <b>absolute</b>: a request is stuck when it has been in a transient state longer than
 * {@code verifyflow.insight.stuck-floor}, the configured expectation for how long that should take.
 *
 * <p>An earlier version of this class made the threshold relative - {@code multiplier x} the median age
 * of everything else currently in the same state. That is wrong in a way worth recording, because it is
 * an appealing design: when the whole population slows down, the median rises with it and nothing is
 * ever flagged. A request two hours into a state whose cohort is also two hours old scores a ratio of
 * 1.0 and looks perfectly healthy. Relative comparison is exactly the wrong instrument for detecting a
 * systemic stall.
 *
 * <p>The cohort comparison is still computed, as {@code cohortOutlier} and the ratio, because it answers
 * a different and genuinely useful question for whoever is on call: is this one request unusual, or is
 * everything in this state equally bad? It informs the narrative; it does not gate the alert.
 */
@Service
public class StuckSignalCalculator {

  private final VerificationRequestRepository requests;
  private final StateChangeRecordRepository history;
  private final VerifyFlowProperties.Insight cfg;
  private final Clock clock;

  public StuckSignalCalculator(
      VerificationRequestRepository requests,
      StateChangeRecordRepository history,
      VerifyFlowProperties props,
      Clock clock) {
    this.requests = requests;
    this.history = history;
    this.cfg = props.insight();
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public StuckSignals calculate(VerificationRequest request) {
    Instant now = clock.instant();
    Duration inState = Duration.between(request.getStateEnteredAt(), now);

    // Peers in the same state, excluding this request so it cannot skew its own baseline.
    List<Instant> cohort =
        requests.findEntryTimesInState(request.getState()).stream()
            .filter(t -> !t.equals(request.getStateEnteredAt()))
            .toList();
    Duration median = medianAge(cohort, now);

    // Absolute gate. Deliberately independent of the cohort - see the class comment.
    Duration threshold = cfg.stuckFloor();
    boolean settled = request.getState().isSettled();
    boolean stuck = !settled && inState.compareTo(threshold) > 0;

    // Relative context only.
    boolean outlier =
        !settled
            && !median.isZero()
            && inState.toSeconds() > median.toSeconds() * cfg.stuckMultiplier();

    boolean callbackSeen =
        history.existsByVerificationIdAndEvent(request.getId(), VerificationEvent.CALLBACK_RECEIVED);
    boolean sessionExpired =
        request.getSessionExpiresAt() != null && request.getSessionExpiresAt().isBefore(now);
    int transitions = history.findByVerificationIdOrderByOccurredAtAscIdAsc(request.getId()).size();

    return new StuckSignals(
        request.getId(),
        request.getState(),
        settled,
        inState,
        median,
        threshold,
        stuck,
        outlier,
        callbackSeen,
        request.getReconcileAttempts(),
        sessionExpired,
        transitions,
        cohort.size());
  }

  /** Median age of the cohort. Returns ZERO for an empty cohort so callers fall back to the floor. */
  private Duration medianAge(List<Instant> entryTimes, Instant now) {
    if (entryTimes.isEmpty()) {
      return Duration.ZERO;
    }
    // The query returns them ascending by entry time, so ages are descending. Either way the middle
    // element is the median; sorting explicitly keeps this correct if the query ever changes.
    List<Long> ages =
        entryTimes.stream().map(t -> Duration.between(t, now).toSeconds()).sorted().toList();
    int n = ages.size();
    long medianSeconds =
        (n % 2 == 1) ? ages.get(n / 2) : (ages.get(n / 2 - 1) + ages.get(n / 2)) / 2;
    return Duration.ofSeconds(medianSeconds);
  }
}
