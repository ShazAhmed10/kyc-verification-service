package dev.portfolio.verifyflow.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of a verification request.
 *
 * <p>SETTLED states ({@link #VERIFIED}, {@link #REJECTED}) are the normal end of the flow. They are
 * settled rather than immutable: an operator override can still move them, which is why they are not
 * modelled as absorbing states.
 *
 * <p>{@link #EXPIRED} is reachable only from a transient state and exists so that a request the
 * vendor never resolved does not sit in a transient state forever.
 */
public enum VerificationState {
  PENDING,
  IN_REVIEW,
  VERIFIED,
  REJECTED,
  EXPIRED;

  /** States we are still expecting the vendor to move us out of. */
  public static final Set<VerificationState> TRANSIENT = EnumSet.of(PENDING, IN_REVIEW);

  /** States that represent a decision having been reached. */
  public static final Set<VerificationState> SETTLED = EnumSet.of(VERIFIED, REJECTED);

  public boolean isTransient() {
    return TRANSIENT.contains(this);
  }

  public boolean isSettled() {
    return SETTLED.contains(this);
  }
}
