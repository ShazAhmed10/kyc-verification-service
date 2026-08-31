package dev.portfolio.verifyflow.readmodel;

import dev.portfolio.verifyflow.domain.VerificationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Denormalised view of a verification, cheap to serve.
 *
 * <p>The column that makes this a read-repair model rather than a cache is {@code refreshedAt}: the
 * read path can tell how old this row is and decide for itself whether to trust it.
 */
@Entity
@Table(name = "verification_summary")
public class VerificationSummary {

  @Id
  @Column(name = "verification_id", nullable = false, updatable = false)
  private UUID verificationId;

  @Column(name = "subject_ref", nullable = false, length = 64)
  private String subjectRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private VerificationState state;

  @Column(name = "transition_count", nullable = false)
  private int transitionCount;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_transition_at", nullable = false)
  private Instant lastTransitionAt;

  @Column(name = "settled", nullable = false)
  private boolean settled;

  @Column(name = "refreshed_at", nullable = false)
  private Instant refreshedAt;

  protected VerificationSummary() {}

  public VerificationSummary(
      UUID verificationId,
      String subjectRef,
      VerificationState state,
      int transitionCount,
      Instant firstSeenAt,
      Instant lastTransitionAt,
      boolean settled,
      Instant refreshedAt) {
    this.verificationId = verificationId;
    this.subjectRef = subjectRef;
    this.state = state;
    this.transitionCount = transitionCount;
    this.firstSeenAt = firstSeenAt;
    this.lastTransitionAt = lastTransitionAt;
    this.settled = settled;
    this.refreshedAt = refreshedAt;
  }

  /** Backdates the freshness stamp so the next read rebuilds. Cheaper than deleting the row. */
  public void invalidate() {
    this.refreshedAt = Instant.EPOCH;
  }

  public boolean isStaleAt(Instant now, java.time.Duration maxAge) {
    return refreshedAt.plus(maxAge).isBefore(now);
  }

  public UUID getVerificationId() {
    return verificationId;
  }

  public String getSubjectRef() {
    return subjectRef;
  }

  public VerificationState getState() {
    return state;
  }

  public int getTransitionCount() {
    return transitionCount;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastTransitionAt() {
    return lastTransitionAt;
  }

  public boolean isSettled() {
    return settled;
  }

  public Instant getRefreshedAt() {
    return refreshedAt;
  }
}
