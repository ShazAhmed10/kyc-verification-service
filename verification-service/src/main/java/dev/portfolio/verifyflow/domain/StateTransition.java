package dev.portfolio.verifyflow.domain;

/**
 * One legal edge of the state machine: this event, applied to an aggregate in {@code from}, moves it
 * to {@code to}.
 *
 * <p>The pair {@code (event, from)} is the identity of the edge. {@code to} is an output, not part of
 * the key - that is what lets one event mean different things depending on where the aggregate
 * currently is.
 */
public record StateTransition(VerificationEvent event, VerificationState from, VerificationState to) {

  /** Key used to look this edge up. Kept as a record so it gets equals/hashCode for free. */
  public record Key(VerificationEvent event, VerificationState from) {}

  public Key key() {
    return new Key(event, from);
  }

  public boolean isSelfLoop() {
    return from == to;
  }

  @Override
  public String toString() {
    return event + ": " + from + " -> " + to;
  }
}
