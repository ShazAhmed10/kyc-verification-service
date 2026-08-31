package dev.portfolio.verifyflow.readmodel;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a summary as stale.
 *
 * <p>Separate from the projector so the write path depends only on "this changed" and never on how the
 * projection is built. Invalidation runs in the caller's transaction: if the state change rolls back,
 * so does the invalidation, and the summary stays consistent with the aggregate.
 */
@Component
public class ReadModelInvalidator {

  private static final Logger log = LoggerFactory.getLogger(ReadModelInvalidator.class);

  private final VerificationSummaryRepository summaries;

  public ReadModelInvalidator(VerificationSummaryRepository summaries) {
    this.summaries = summaries;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markStale(UUID verificationId) {
    int rows = summaries.backdateRefreshedAt(verificationId, Instant.EPOCH);
    if (rows == 0) {
      // No summary yet - nothing to invalidate. The first read will build it.
      log.debug("No summary to invalidate for {}", verificationId);
    }
  }
}
