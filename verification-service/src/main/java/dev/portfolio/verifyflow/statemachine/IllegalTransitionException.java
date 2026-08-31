package dev.portfolio.verifyflow.statemachine;

import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationState;

/** Thrown when an event is applied to an aggregate that has no edge for it from its current state. */
public class IllegalTransitionException extends RuntimeException {

  private final VerificationEvent event;
  private final VerificationState from;

  public IllegalTransitionException(VerificationEvent event, VerificationState from) {
    super("No transition declared for event " + event + " from state " + from);
    this.event = event;
    this.from = from;
  }

  public VerificationEvent getEvent() {
    return event;
  }

  public VerificationState getFrom() {
    return from;
  }
}
