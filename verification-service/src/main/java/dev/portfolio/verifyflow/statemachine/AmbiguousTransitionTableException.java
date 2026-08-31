package dev.portfolio.verifyflow.statemachine;

/**
 * Thrown at startup when the declared table contains two rows with the same {@code (event, from)}
 * key. Failing here is deliberate: an ambiguous table would otherwise resolve by whichever row was
 * found first, which is a silent correctness bug that only shows up in production data.
 */
public class AmbiguousTransitionTableException extends RuntimeException {
  public AmbiguousTransitionTableException(String message) {
    super(message);
  }
}
