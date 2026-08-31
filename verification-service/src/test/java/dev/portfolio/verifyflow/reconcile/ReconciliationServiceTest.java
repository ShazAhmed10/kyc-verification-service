package dev.portfolio.verifyflow.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.portfolio.verifyflow.domain.StateChangeRecord;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.domain.VerificationState;
import dev.portfolio.verifyflow.readmodel.ReadModelInvalidator;
import dev.portfolio.verifyflow.reconcile.ReconciliationService.ReconcileOutcome;
import dev.portfolio.verifyflow.statemachine.TransitionEngine;
import dev.portfolio.verifyflow.testsupport.MutableClock;
import dev.portfolio.verifyflow.statemachine.TransitionRegistry;
import dev.portfolio.verifyflow.vendor.VendorCallFailedException;
import dev.portfolio.verifyflow.vendor.VerificationOutcome;
import dev.portfolio.verifyflow.vendor.VerifyProClient;
import dev.portfolio.verifyflow.vendor.VerifyProResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation path is where correctness under at-least-once delivery is decided, so these tests
 * are about repeats, failures and ordering rather than the happy path alone.
 */
class ReconciliationServiceTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
  private static final String VENDOR_REF = "vp_abc123";

  private VerificationRequestRepository requests;
  private VerifyProClient vendor;
  private ReadModelInvalidator invalidator;
  private StateChangeRecordRepository history;
  private ReconciliationService service;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    requests = mock(VerificationRequestRepository.class);
    vendor = mock(VerifyProClient.class);
    invalidator = mock(ReadModelInvalidator.class);
    history = mock(StateChangeRecordRepository.class);
    when(history.save(any(StateChangeRecord.class))).thenAnswer(i -> i.getArgument(0));

    clock = new MutableClock(T0);
    TransitionRegistry registry = new TransitionRegistry();
    registry.index();
    // A real engine, not a mock: these tests should fail if the state machine is wrong, not just if
    // the service forgets to call it.
    TransitionEngine engine = new TransitionEngine(registry, history, clock);
    service = new ReconciliationService(requests, vendor, engine, invalidator, clock);
  }

  private VerificationRequest requestIn(VerificationState state) {
    VerificationRequest r = VerificationRequest.start("subject-1", VerificationState.PENDING, T0);
    r.attachVendorSession(VENDOR_REF, T0.plusSeconds(900), T0);
    if (state == VerificationState.IN_REVIEW) {
      TransitionRegistry reg = new TransitionRegistry();
      reg.index();
      new TransitionEngine(reg, history, clock)
          .apply(r, dev.portfolio.verifyflow.domain.VerificationEvent.CALLBACK_RECEIVED, null);
    }
    return r;
  }

  private void given(VerificationRequest r) {
    when(requests.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
  }

  private void vendorSays(VerificationOutcome outcome) {
    when(vendor.fetchResult(VENDOR_REF)).thenReturn(new VerifyProResult(VENDOR_REF, outcome, "test"));
  }

  @Test
  @DisplayName("an approval settles the request and invalidates the read model")
  void approvalSettles() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.APPROVED);

    assertThat(service.reconcile(r.getId())).isEqualTo(ReconcileOutcome.SETTLED);
    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);
    verify(invalidator).markStale(r.getId());
  }

  @Test
  @DisplayName("the verdict is always read from the vendor, never taken from the trigger")
  void verdictComesFromTheVendor() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.DECLINED);

    service.reconcile(r.getId());

    // Nothing about the callback influenced this - the outcome came from fetchResult.
    verify(vendor).fetchResult(VENDOR_REF);
    assertThat(r.getState()).isEqualTo(VerificationState.REJECTED);
  }

  @Test
  @DisplayName("approval works straight from PENDING when the callback never arrived")
  void approvalFromPendingWhenCallbackLost() {
    VerificationRequest r = requestIn(VerificationState.PENDING); // no CALLBACK_RECEIVED ever
    given(r);
    vendorSays(VerificationOutcome.APPROVED);

    assertThat(service.reconcile(r.getId())).isEqualTo(ReconcileOutcome.SETTLED);
    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);
  }

  @Test
  @DisplayName("a redelivered task for a settled request is a cheap no-op and never re-asks the vendor")
  void redeliveryOnSettledIsNoOp() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.APPROVED);
    service.reconcile(r.getId()); // settles it

    ReconcileOutcome second = service.reconcile(r.getId());

    assertThat(second).isEqualTo(ReconcileOutcome.ALREADY_SETTLED);
    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);
    // Exactly one vendor call across two deliveries - the early return matters for cost, not just
    // correctness, because a hot retry loop would otherwise hammer the vendor.
    verify(vendor).fetchResult(VENDOR_REF);
  }

  @Test
  @DisplayName("running the same reconciliation twice produces the same state - idempotent by construction")
  void reconciliationIsIdempotent() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.APPROVED);

    service.reconcile(r.getId());
    VerificationState afterFirst = r.getState();
    service.reconcile(r.getId());

    assertThat(r.getState()).isEqualTo(afterFirst);
  }

  @Test
  @DisplayName("a vendor failure propagates so the transaction rolls back and the broker redelivers")
  void vendorFailurePropagates() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    when(vendor.fetchResult(VENDOR_REF)).thenThrow(new VendorCallFailedException("503 from vendor"));

    assertThatThrownBy(() -> service.reconcile(r.getId()))
        .isInstanceOf(VendorCallFailedException.class);

    // Critical: not knowing the answer must never be recorded as an answer.
    assertThat(r.getState()).isEqualTo(VerificationState.IN_REVIEW);
    assertThat(r.getState().isSettled()).isFalse();
    verify(invalidator, never()).markStale(any());
  }

  @Test
  @DisplayName("INCONCLUSIVE keeps the request waiting, and a later APPROVED settles it")
  void inconclusiveThenApproved() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);

    vendorSays(VerificationOutcome.INCONCLUSIVE);
    assertThat(service.reconcile(r.getId())).isEqualTo(ReconcileOutcome.STILL_WAITING);
    assertThat(r.getState()).isEqualTo(VerificationState.IN_REVIEW);

    vendorSays(VerificationOutcome.APPROVED);
    assertThat(service.reconcile(r.getId())).isEqualTo(ReconcileOutcome.SETTLED);
    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);
  }

  @Test
  @DisplayName("an unrecognised vendor outcome leaves the request unsettled rather than guessing")
  void unknownOutcomeLeavesUnsettled() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.UNKNOWN);

    assertThat(service.reconcile(r.getId())).isEqualTo(ReconcileOutcome.UNRESOLVED);
    assertThat(r.getState()).isEqualTo(VerificationState.IN_REVIEW);
  }

  @Test
  @DisplayName("a request with no vendor session cannot be reconciled")
  void noVendorRef() {
    VerificationRequest r = VerificationRequest.start("subject-1", VerificationState.PENDING, T0);
    given(r);

    assertThat(service.reconcile(r.getId())).isEqualTo(ReconcileOutcome.NO_VENDOR_REF);
    verify(vendor, never()).fetchResult(anyString());
  }

  @Test
  @DisplayName("a task for an unknown request is reported, not retried forever")
  void unknownRequest() {
    UUID ghost = UUID.randomUUID();
    when(requests.findByIdForUpdate(ghost)).thenReturn(Optional.empty());

    assertThat(service.reconcile(ghost)).isEqualTo(ReconcileOutcome.NOT_FOUND);
  }

  @Test
  @DisplayName("each attempt is counted, which is what stuck-detection later keys off")
  void attemptsAreCounted() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.INCONCLUSIVE);

    service.reconcile(r.getId());
    service.reconcile(r.getId());

    assertThat(r.getReconcileAttempts()).isEqualTo(2);
  }

  @Test
  @DisplayName("the row is taken with a write lock, so concurrent deliveries serialise")
  void usesLockingRead() {
    VerificationRequest r = requestIn(VerificationState.IN_REVIEW);
    given(r);
    vendorSays(VerificationOutcome.APPROVED);

    service.reconcile(r.getId());

    // Asserting the locking finder specifically: a plain findById here would allow two deliveries to
    // interleave read-decide-write.
    verify(requests).findByIdForUpdate(eq(r.getId()));
    verify(requests, never()).findById(any());
  }
}
