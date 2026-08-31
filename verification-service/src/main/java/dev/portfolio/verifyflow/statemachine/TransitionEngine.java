package dev.portfolio.verifyflow.statemachine;

import dev.portfolio.verifyflow.domain.StateChangeRecord;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationState;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place a verification request changes state.
 *
 * <p>Every move goes: look the edge up, refuse it if it is not declared, apply it, append the audit
 * row. The audit append is inside the caller's transaction on purpose - a state change that is not
 * recorded is worse than one that did not happen, because nothing downstream can explain it later.
 */
@Service
public class TransitionEngine {

  private static final Logger log = LoggerFactory.getLogger(TransitionEngine.class);

  private final TransitionRegistry registry;
  private final StateChangeRecordRepository history;
  private final Clock clock;

  public TransitionEngine(
      TransitionRegistry registry, StateChangeRecordRepository history, Clock clock) {
    this.registry = registry;
    this.history = history;
    this.clock = clock;
  }

  /**
   * Applies {@code event} to {@code request}.
   *
   * @return the state the request is now in
   * @throws IllegalTransitionException if the machine declares no edge for this event from the
   *     request's current state
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public VerificationState apply(VerificationRequest request, VerificationEvent event, String detail) {
    VerificationState from = request.getState();
    VerificationState to = registry.resolveTarget(event, from); // throws if undeclared
    Instant now = clock.instant();

    request.applyState(to, now);
    history.save(new StateChangeRecord(request.getId(), event, from, to, detail, now));

    if (from == to) {
      log.debug("{} recorded on {} (self-loop in {})", event, request.getId(), from);
    } else {
      log.info("{} moved {} from {} to {}", event, request.getId(), from, to);
    }
    return to;
  }

  /**
   * Applies the event only if the machine allows it, otherwise does nothing and reports it.
   *
   * <p>This exists for genuinely at-least-once callers - a redelivered reconciliation task for a
   * request that has already settled is not an error, it is the delivery guarantee working. Callers
   * that are not idempotent by nature should use {@link #apply} and let the exception surface.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean applyIfAllowed(
      VerificationRequest request, VerificationEvent event, String detail) {
    if (!registry.isAllowed(event, request.getState())) {
      log.debug(
          "Skipping {} on {}: not legal from {} (already settled or superseded)",
          event,
          request.getId(),
          request.getState());
      return false;
    }
    apply(request, event, detail);
    return true;
  }

  public TransitionRegistry registry() {
    return registry;
  }
}
