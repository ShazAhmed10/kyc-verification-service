package dev.portfolio.verifyflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt, Long> {
  boolean existsByVendorRefAndEventType(String vendorRef, String eventType);
}
