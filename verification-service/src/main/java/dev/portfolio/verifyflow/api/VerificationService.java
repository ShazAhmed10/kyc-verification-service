package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.domain.WebhookReceipt;
import dev.portfolio.verifyflow.domain.WebhookReceiptRepository;
import dev.portfolio.verifyflow.readmodel.ReadModelInvalidator;
import dev.portfolio.verifyflow.reconcile.ReconcileRequested;
import dev.portfolio.verifyflow.statemachine.TransitionEngine;
import dev.portfolio.verifyflow.statemachine.TransitionTable;
import dev.portfolio.verifyflow.vendor.VerifyProClient;
import dev.portfolio.verifyflow.vendor.VerifyProSession;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entry points for starting a verification and for accepting a vendor callback. */
@Service
public class VerificationService {

  private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

  private final VerificationRequestRepository requests;
  private final WebhookReceiptRepository receipts;
  private final VerifyProClient vendor;
  private final TransitionEngine engine;
  private final ReadModelInvalidator invalidator;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public VerificationService(
      VerificationRequestRepository requests,
      WebhookReceiptRepository receipts,
      VerifyProClient vendor,
      TransitionEngine engine,
      ReadModelInvalidator invalidator,
      ApplicationEventPublisher events,
      Clock clock) {
    this.requests = requests;
    this.receipts = receipts;
    this.vendor = vendor;
    this.engine = engine;
    this.invalidator = invalidator;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Creates a verification and opens a vendor session for it.
   *
   * <p>The vendor call happens inside the transaction, which is a compromise worth naming: it keeps
   * the request and its vendor reference consistent - we never persist a request we failed to open a
   * session for - at the cost of holding a database transaction open across a network call. With a
   * higher-volume service this would move to a two-step create-then-attach so the transaction does not
   * span the call. Noted in the README rather than hidden.
   */
  @Transactional
  public VerificationRequest start(String subjectRef) {
    VerificationRequest request =
        VerificationRequest.start(subjectRef, TransitionTable.INITIAL_STATE, clock.instant());
    requests.save(request);

    VerifyProSession session = vendor.createSession(subjectRef);
    request.attachVendorSession(session.vendorRef(), session.expiresAt(), clock.instant());
    engine.apply(request, VerificationEvent.SESSION_ISSUED, "vendor session " + session.vendorRef());

    log.info("Started verification {} for subject {}", request.getId(), subjectRef);
    return request;
  }

  /**
   * Handles a vendor callback.
   *
   * <p>Three things happen, in this order and for a reason: de-duplicate, move the state, then ask for
   * reconciliation. The callback itself carries no verdict - it only tells us it is worth asking - so
   * this method never decides an outcome.
   *
   * @return true if this callback was new, false if it was a duplicate we had already recorded
   */
  @Transactional
  public boolean handleCallback(String vendorRef, String eventType) {
    Optional<VerificationRequest> maybe = requests.findByVendorRef(vendorRef);
    if (maybe.isEmpty()) {
      log.warn("Callback for unknown vendor reference {}", vendorRef);
      return false;
    }
    VerificationRequest request = maybe.get();

    try {
      receipts.save(new WebhookReceipt(vendorRef, eventType, clock.instant()));
    } catch (DataIntegrityViolationException dup) {
      // The unique index did its job. The vendor is entitled to retry; this is not an error.
      log.info("Duplicate callback {} / {} ignored", vendorRef, eventType);
      return false;
    }

    engine.applyIfAllowed(request, VerificationEvent.CALLBACK_RECEIVED, "vendor event " + eventType);
    invalidator.markStale(request.getId());

    // Queued only once this transaction commits - see ReconcileDispatcher.
    events.publishEvent(new ReconcileRequested(request.getId(), "callback:" + eventType));
    return true;
  }

  /** Operator action. Kept separate from the vendor-driven path so the audit trail distinguishes them. */
  @Transactional
  public VerificationRequest override(UUID id, boolean approve, String reason) {
    VerificationRequest request =
        requests.findByIdForUpdate(id).orElseThrow(() -> new java.util.NoSuchElementException("No verification " + id));
    VerificationEvent event =
        approve ? VerificationEvent.OVERRIDE_APPROVE : VerificationEvent.OVERRIDE_REJECT;
    engine.apply(request, event, "operator override: " + reason);
    invalidator.markStale(id);
    return request;
  }

  /** Lets an operator re-drive reconciliation for a request that never resolved. */
  @Transactional
  public void requestReconcile(UUID id) {
    VerificationRequest request =
        requests.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("No verification " + id));
    events.publishEvent(new ReconcileRequested(request.getId(), "manual"));
  }
}
