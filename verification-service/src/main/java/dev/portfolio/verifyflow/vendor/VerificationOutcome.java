package dev.portfolio.verifyflow.vendor;

/** The vendor's verdict, as read from its result API. This is the only thing we trust. */
public enum VerificationOutcome {
  APPROVED,
  DECLINED,
  /** The vendor has looked and cannot decide. Not a failure - a legitimate "keep waiting". */
  INCONCLUSIVE,
  /** The vendor has no record of this reference. Treated as a hard problem, not a verdict. */
  UNKNOWN
}
