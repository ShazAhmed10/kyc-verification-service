package dev.portfolio.verifyflow.domain;

/**
 * Everything that can move a verification request. The event says what happened; it deliberately
 * does not say what state to move to - see {@link dev.portfolio.verifyflow.statemachine.TransitionTable}.
 */
public enum VerificationEvent {
  /** A client asked us to verify someone. Creates the aggregate. */
  START_VERIFICATION,
  /** The vendor issued us a session for the subject to complete. */
  SESSION_ISSUED,
  /** The vendor told us it has something for us. A trigger only - never carries the outcome. */
  CALLBACK_RECEIVED,
  /** Reconciliation read an approval from the vendor's result API. */
  RESULT_APPROVED,
  /** Reconciliation read a decline from the vendor's result API. */
  RESULT_DECLINED,
  /** The vendor has looked but cannot decide yet. Keeps us waiting, deliberately. */
  RESULT_INCONCLUSIVE,
  /** The sweeper gave up waiting. */
  EXPIRE,
  /** An operator forced an approval. */
  OVERRIDE_APPROVE,
  /** An operator forced a rejection. */
  OVERRIDE_REJECT
}
