package dev.portfolio.verifyflow.reconcile;

import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.readmodel.ReadModelInvalidator;
import dev.portfolio.verifyflow.statemachine.TransitionEngine;
import dev.portfolio.verifyflow.vendor.VerifyProClient;
import dev.portfolio.verifyflow.vendor.VerifyProResult;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "something happened at the vendor" into a settled verification.
 *
 * <p>This is the step that decides outcomes, and it works by asking the vendor rather than believing
 * whatever arrived in the callback. Everything about how it handles repeats follows from that: because
 * the verdict is always re-read, running this twice produces the same answer, and a duplicate delivery
 * is wasted work rather than a wrong result.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  private final VerificationRequestRepository requests;
  private final VerifyProClient vendor;
  private final TransitionEngine engine;
  private final ReadModelInvalidator invalidator;
  private final Clock clock;

  public ReconciliationService(
      VerificationRequestRepository requests,
      VerifyProClient vendor,
      TransitionEngine engine,
      ReadModelInvalidator invalidator,
      Clock clock) {
    this.requests = requests;
    this.vendor = vendor;
    this.engine = engine;
    this.invalidator = invalidator;
    this.clock = clock;
  }

  /**
   * Reconciles one request against the vendor.
   *
   * @return what happened, for the caller's logs and for tests
   */
  @Transactional
  public ReconcileOutcome reconcile(UUID verificationId) {
    Optional<VerificationRequest> maybe = requests.findByIdForUpdate(verificationId);
    if (maybe.isEmpty()) {
      // Can happen if a task outlived its request. Not retryable - there is nothing to do.
      log.warn("Reconciliation task for unknown request {}", verificationId);
      return ReconcileOutcome.NOT_FOUND;
    }
    VerificationRequest request = maybe.get();

    if (request.getState().isSettled()) {
      // The normal at-least-once case: a redelivery, or a second callback after we already decided.
      log.debug("{} already settled as {}, nothing to reconcile", verificationId, request.getState());
      return ReconcileOutcome.ALREADY_SETTLED;
    }

    if (request.getVendorRef() == null) {
      // We never got a session, so there is nothing at the vendor to ask about.
      log.warn("{} has no vendor reference; cannot reconcile", verificationId);
      return ReconcileOutcome.NO_VENDOR_REF;
    }

    request.recordReconcileAttempt(clock.instant());

    // A failure here throws, the transaction rolls back, and the broker redelivers. That is
    // deliberate: not knowing the verdict must never be recorded as a verdict.
    VerifyProResult result = vendor.fetchResult(request.getVendorRef());

    VerificationEvent event =
        switch (result.outcome()) {
          case APPROVED -> VerificationEvent.RESULT_APPROVED;
          case DECLINED -> VerificationEvent.RESULT_DECLINED;
          case INCONCLUSIVE -> VerificationEvent.RESULT_INCONCLUSIVE;
          case UNKNOWN -> null;
        };

    if (event == null) {
      // The vendor does not recognise the reference, or answered with something we cannot map.
      // Leaving the request transient is the honest outcome; the sweeper will expire it eventually.
      log.warn(
          "Vendor returned UNKNOWN for {} (ref {}); leaving in {}",
          verificationId,
          request.getVendorRef(),
          request.getState());
      return ReconcileOutcome.UNRESOLVED;
    }

    String detail = "vendor outcome " + result.outcome() + (result.reason() == null ? "" : " (" + result.reason() + ")");
    boolean applied = engine.applyIfAllowed(request, event, detail);
    if (!applied) {
      return ReconcileOutcome.ALREADY_SETTLED;
    }

    invalidator.markStale(verificationId);
    return request.getState().isSettled() ? ReconcileOutcome.SETTLED : ReconcileOutcome.STILL_WAITING;
  }

  /** What a reconciliation attempt concluded. */
  public enum ReconcileOutcome {
    SETTLED,
    STILL_WAITING,
    ALREADY_SETTLED,
    UNRESOLVED,
    NO_VENDOR_REF,
    NOT_FOUND
  }
}
