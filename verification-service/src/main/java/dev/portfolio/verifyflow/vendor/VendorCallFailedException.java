package dev.portfolio.verifyflow.vendor;

/**
 * The vendor could not be reached, or answered in a way we cannot interpret.
 *
 * <p>Distinct from a DECLINED verdict on purpose: a decline is an answer and settles the request, a
 * failure means we still do not know and must retry. Conflating the two would reject people because
 * of our own network.
 */
public class VendorCallFailedException extends RuntimeException {
  public VendorCallFailedException(String message) {
    super(message);
  }

  public VendorCallFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
