package dev.portfolio.verifyflow.vendor;

/**
 * The authoritative result.
 *
 * <p>{@code reason} is a short vendor-supplied code, kept for the audit trail. It is deliberately not
 * a free-text blob and never a document payload - none of that belongs in our database.
 */
public record VerifyProResult(String vendorRef, VerificationOutcome outcome, String reason) {}
