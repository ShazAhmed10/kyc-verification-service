package dev.portfolio.verifyflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only history. One row per applied transition, including self-loops.
 *
 * <p>This table is the reason the {@code /explain} endpoint can say anything useful: the current state
 * tells you where a request is, and this tells you how it got there and what did not happen.
 */
@Entity
@Table(name = "state_change_record")
public class StateChangeRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "verification_id", nullable = false, updatable = false)
  private UUID verificationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event", nullable = false, updatable = false, length = 48)
  private VerificationEvent event;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_state", nullable = false, updatable = false, length = 32)
  private VerificationState fromState;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_state", nullable = false, updatable = false, length = 32)
  private VerificationState toState;

  /** Short human-readable note. Never a vendor payload dump, never PII. */
  @Column(name = "detail", length = 512)
  private String detail;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  protected StateChangeRecord() {
    // JPA
  }

  public StateChangeRecord(
      UUID verificationId,
      VerificationEvent event,
      VerificationState fromState,
      VerificationState toState,
      String detail,
      Instant occurredAt) {
    this.verificationId = verificationId;
    this.event = event;
    this.fromState = fromState;
    this.toState = toState;
    this.detail = detail;
    this.occurredAt = occurredAt;
  }

  public Long getId() {
    return id;
  }

  public UUID getVerificationId() {
    return verificationId;
  }

  public VerificationEvent getEvent() {
    return event;
  }

  public VerificationState getFromState() {
    return fromState;
  }

  public VerificationState getToState() {
    return toState;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
