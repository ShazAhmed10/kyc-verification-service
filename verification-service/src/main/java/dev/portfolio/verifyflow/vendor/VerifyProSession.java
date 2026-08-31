package dev.portfolio.verifyflow.vendor;

import java.time.Instant;

/** What the vendor hands back when we open a verification session for a subject. */
public record VerifyProSession(String vendorRef, String sessionToken, Instant expiresAt) {}
