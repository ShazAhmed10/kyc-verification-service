package dev.portfolio.verifyflow.readmodel;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationSummaryRepository extends JpaRepository<VerificationSummary, UUID> {

  /**
   * Backdates the freshness stamp without loading the entity.
   *
   * <p>{@code clearAutomatically} is left at its default (false) because callers do not read the
   * summary again in the same transaction; flushing the persistence context here would be pointless
   * work. Written as an update rather than a load-modify-save so an invalidation from the write path
   * cannot lose a concurrent rebuild's row.
   */
  @Modifying
  @Query("update VerificationSummary s set s.refreshedAt = :epoch where s.verificationId = :id")
  int backdateRefreshedAt(@Param("id") UUID id, @Param("epoch") Instant epoch);
}
