package dev.portfolio.verifyflow.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateChangeRecordRepository extends JpaRepository<StateChangeRecord, Long> {

  List<StateChangeRecord> findByVerificationIdOrderByOccurredAtAscIdAsc(UUID verificationId);

  boolean existsByVerificationIdAndEvent(UUID verificationId, VerificationEvent event);

  long countByVerificationIdAndEvent(UUID verificationId, VerificationEvent event);
}
