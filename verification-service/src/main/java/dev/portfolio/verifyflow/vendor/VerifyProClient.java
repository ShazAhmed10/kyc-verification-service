package dev.portfolio.verifyflow.vendor;

/**
 * Our side of the VerifyPro contract, narrowed to the two calls we actually make.
 *
 * <p>An interface rather than a concrete client so tests can drive the reconciliation logic without a
 * running vendor, and so the whole vendor coupling is visible in one file.
 */
public interface VerifyProClient {

  /** Opens a session the subject can complete. Called once per verification request. */
  VerifyProSession createSession(String subjectRef);

  /**
   * Reads the current verdict. This is the call that decides the outcome - the webhook only tells us
   * it is worth asking.
   */
  VerifyProResult fetchResult(String vendorRef);
}
