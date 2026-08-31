package dev.portfolio.verifyflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The aggregate. One row per thing we are trying to verify.
 *
 * <p>State is only ever changed through {@link dev.portfolio.verifyflow.statemachine.TransitionEngine},
 * which is why the setter is package-private-ish in spirit and named {@code applyState} rather than
 * {@code setState} - it is not a bean property, it is a state move that has already been validated.
 */
@Entity
@Table(name = "verification_request")
public class VerificationRequest {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Opaque reference to whoever we are verifying. No PII stored here on purpose. */
  @Column(name = "subject_ref", nullable = false, updatable = false, length = 64)
  private String subjectRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private VerificationState state;

  /** The vendor's own identifier for this verification. Null until a session is issued. */
  @Column(name = "vendor_ref", length = 128)
  private String vendorRef;

  @Column(name = "session_expires_at")
  private Instant sessionExpiresAt;

  /** How many times reconciliation has run. Used for stuck-detection, not for control flow. */
  @Column(name = "reconcile_attempts", nullable = false)
  private int reconcileAttempts;

  /** When the aggregate entered its current state. Drives expiry and stuck-detection. */
  @Column(name = "state_entered_at", nullable = false)
  private Instant stateEnteredAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Optimistic lock. Two reconciliation deliveries for the same request will both read, both decide,
   * and one will fail to write - which is what we want, because the loser then retries against the
   * state the winner produced.
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected VerificationRequest() {
    // JPA
  }

  private VerificationRequest(UUID id, String subjectRef, VerificationState state, Instant now) {
    this.id = id;
    this.subjectRef = subjectRef;
    this.state = state;
    this.stateEnteredAt = now;
    this.createdAt = now;
    this.updatedAt = now;
    this.reconcileAttempts = 0;
  }

  /** Factory for a brand-new request. Creation is not a transition, so it does not go via the engine. */
  public static VerificationRequest start(String subjectRef, VerificationState initialState, Instant now) {
    return new VerificationRequest(UUID.randomUUID(), subjectRef, initialState, now);
  }

  /**
   * Moves the aggregate. Only the transition engine should call this - it has already checked the
   * move is legal and is responsible for writing the audit row.
   */
  public void applyState(VerificationState newState, Instant now) {
    if (newState != this.state) {
      this.stateEnteredAt = now;
    }
    this.state = newState;
    this.updatedAt = now;
  }

  public void attachVendorSession(String vendorRef, Instant expiresAt, Instant now) {
    this.vendorRef = vendorRef;
    this.sessionExpiresAt = expiresAt;
    this.updatedAt = now;
  }

  public void recordReconcileAttempt(Instant now) {
    this.reconcileAttempts++;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getSubjectRef() {
    return subjectRef;
  }

  public VerificationState getState() {
    return state;
  }

  public String getVendorRef() {
    return vendorRef;
  }

  public Instant getSessionExpiresAt() {
    return sessionExpiresAt;
  }

  public int getReconcileAttempts() {
    return reconcileAttempts;
  }

  public Instant getStateEnteredAt() {
    return stateEnteredAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
