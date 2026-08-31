package dev.portfolio.verifyflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Proof that we have already seen a given vendor callback.
 *
 * <p>The uniqueness of {@code (vendorRef, eventType)} is enforced by the database, not by a
 * read-then-write check in the service. Two concurrent deliveries of the same callback would both
 * pass an application-level check; only one can win a unique index.
 */
@Entity
@Table(name = "webhook_receipt")
public class WebhookReceipt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "vendor_ref", nullable = false, updatable = false, length = 128)
  private String vendorRef;

  @Column(name = "event_type", nullable = false, updatable = false, length = 64)
  private String eventType;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  protected WebhookReceipt() {}

  public WebhookReceipt(String vendorRef, String eventType, Instant receivedAt) {
    this.vendorRef = vendorRef;
    this.eventType = eventType;
    this.receivedAt = receivedAt;
  }

  public Long getId() {
    return id;
  }

  public String getVendorRef() {
    return vendorRef;
  }

  public String getEventType() {
    return eventType;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
