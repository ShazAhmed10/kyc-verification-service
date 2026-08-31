package dev.portfolio.verifyflow.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, UUID> {

  Optional<VerificationRequest> findByVendorRef(String vendorRef);

  /**
   * Pessimistic read for the reconciliation path. Two concurrent deliveries for the same request
   * serialise here rather than racing to write and having one lose on the version check - cheaper
   * than a retry loop when contention is expected rather than exceptional.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from VerificationRequest v where v.id = :id")
  Optional<VerificationRequest> findByIdForUpdate(@Param("id") UUID id);

  /** Candidates for the expiry sweeper: still transient and sitting there too long. */
  @Query(
      "select v from VerificationRequest v "
          + "where v.state in :states and v.stateEnteredAt < :threshold "
          + "order by v.stateEnteredAt asc")
  List<VerificationRequest> findStaleInStates(
      @Param("states") List<VerificationState> states, @Param("threshold") Instant threshold);

  /** Used by the stuck-signal calculator to compare one request against its cohort. */
  @Query(
      "select v.stateEnteredAt from VerificationRequest v "
          + "where v.state = :state order by v.stateEnteredAt asc")
  List<Instant> findEntryTimesInState(@Param("state") VerificationState state);
}
