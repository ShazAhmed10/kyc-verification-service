package dev.portfolio.verifyflow.statemachine;

import static dev.portfolio.verifyflow.domain.VerificationEvent.*;
import static dev.portfolio.verifyflow.domain.VerificationState.*;

import dev.portfolio.verifyflow.domain.StateTransition;
import java.util.List;

/**
 * The state machine, declared once as data.
 *
 * <p>Everything the machine can do is in this list. There is no {@code switch} on state anywhere in
 * the codebase and no state-machine framework - adding a legal move means adding a row here, and the
 * table is validated at startup by {@link TransitionRegistry}.
 *
 * <p>Two rows are worth reading twice:
 *
 * <ul>
 *   <li>{@code RESULT_APPROVED} appears from both PENDING and IN_REVIEW. The IN_REVIEW row is the
 *       happy path; the PENDING row is the case where the vendor's callback never arrived and
 *       reconciliation found the answer anyway. Both are legal, and they are different edges. This is
 *       the reason the lookup key is {@code (event, from)} rather than the event alone.
 *   <li>{@code SESSION_ISSUED} is a self-loop on PENDING. It changes no state, but recording it means
 *       the audit trail can answer "did we ever get a session for this request?" without a separate
 *       column.
 * </ul>
 */
public final class TransitionTable {

  private TransitionTable() {}

  public static final List<StateTransition> TRANSITIONS =
      List.of(
          // --- vendor session handshake ---
          new StateTransition(SESSION_ISSUED, PENDING, PENDING),

          // --- the vendor tells us to come and look ---
          new StateTransition(CALLBACK_RECEIVED, PENDING, IN_REVIEW),

          // --- outcomes, always pulled from the vendor, never taken from the callback ---
          new StateTransition(RESULT_APPROVED, IN_REVIEW, VERIFIED),
          new StateTransition(RESULT_APPROVED, PENDING, VERIFIED),
          new StateTransition(RESULT_DECLINED, IN_REVIEW, REJECTED),
          new StateTransition(RESULT_DECLINED, PENDING, REJECTED),
          new StateTransition(RESULT_INCONCLUSIVE, IN_REVIEW, IN_REVIEW),

          // --- we stopped waiting ---
          new StateTransition(EXPIRE, PENDING, EXPIRED),
          new StateTransition(EXPIRE, IN_REVIEW, EXPIRED),

          // --- operator escape hatches ---
          new StateTransition(OVERRIDE_APPROVE, IN_REVIEW, VERIFIED),
          new StateTransition(OVERRIDE_APPROVE, REJECTED, VERIFIED),
          new StateTransition(OVERRIDE_APPROVE, EXPIRED, VERIFIED),
          new StateTransition(OVERRIDE_REJECT, IN_REVIEW, REJECTED),
          new StateTransition(OVERRIDE_REJECT, VERIFIED, REJECTED),
          new StateTransition(OVERRIDE_REJECT, EXPIRED, REJECTED));

  /** The state a brand-new aggregate starts in. START_VERIFICATION is creation, not a transition. */
  public static final dev.portfolio.verifyflow.domain.VerificationState INITIAL_STATE = PENDING;
}
