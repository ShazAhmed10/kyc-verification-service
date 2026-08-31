package dev.portfolio.verifyflow.reconcile;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.domain.VerificationState;
import dev.portfolio.verifyflow.readmodel.ReadModelInvalidator;
import dev.portfolio.verifyflow.statemachine.TransitionEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The safety net for requests the vendor never resolved.
 *
 * <p>It does two different things and the order matters. For a request that has been transient for
 * longer than the TTL, it first tries one more reconciliation - because the common cause is a lost
 * callback rather than a vendor that has no answer, and asking again is cheap. Only requests that have
 * already been reconciled at least once and are still transient get expired.
 *
 * <p>This is what makes the lost-message window in {@link ReconcileDispatcher} recoverable rather than
 * permanent: a queue publish that failed leaves a transient request, and this picks it up.
 */
@Component
public class ExpirySweeper {

  private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

  private final VerificationRequestRepository requests;
  private final TransitionEngine engine;
  private final ReadModelInvalidator invalidator;
  private final ApplicationEventPublisher events;
  private final VerifyFlowProperties.Expiry cfg;
  private final Clock clock;

  public ExpirySweeper(
      VerificationRequestRepository requests,
      TransitionEngine engine,
      ReadModelInvalidator invalidator,
      ApplicationEventPublisher events,
      VerifyFlowProperties props,
      Clock clock) {
    this.requests = requests;
    this.engine = engine;
    this.invalidator = invalidator;
    this.events = events;
    this.cfg = props.expiry();
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${verifyflow.expiry.sweep-interval-ms:60000}")
  public void sweep() {
    SweepResult result = runSweep();
    if (result.retried() > 0 || result.expired() > 0) {
      log.info("Expiry sweep: {} retried, {} expired", result.retried(), result.expired());
    }
  }

  /** Extracted so a test can run one pass deterministically rather than waiting for the schedule. */
  @Transactional
  public SweepResult runSweep() {
    Instant threshold = clock.instant().minus(cfg.transientTtl());
    List<VerificationRequest> stale =
        requests.findStaleInStates(new ArrayList<>(VerificationState.TRANSIENT), threshold);

    int retried = 0;
    int expired = 0;
    for (VerificationRequest r : stale) {
      if (r.getVendorRef() != null && r.getReconcileAttempts() == 0) {
        // Never asked the vendor. Most likely a callback that never arrived, or a publish that failed.
        events.publishEvent(new ReconcileRequested(r.getId(), "sweeper:never-reconciled"));
        retried++;
        continue;
      }
      if (engine.applyIfAllowed(r, VerificationEvent.EXPIRE, "expired after " + cfg.transientTtl())) {
        invalidator.markStale(r.getId());
        expired++;
      }
    }
    return new SweepResult(retried, expired, stale.size());
  }

  public record SweepResult(int retried, int expired, int examined) {}
}
