package dev.portfolio.verifyflow.readmodel;

import dev.portfolio.verifyflow.domain.StateChangeRecord;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds a summary from the write-side truth. The only place that knows the projection's shape.
 */
@Component
public class SummaryProjector {

  private final StateChangeRecordRepository history;
  private final Clock clock;

  public SummaryProjector(StateChangeRecordRepository history, Clock clock) {
    this.history = history;
    this.clock = clock;
  }

  public VerificationSummary project(VerificationRequest request) {
    List<StateChangeRecord> records =
        history.findByVerificationIdOrderByOccurredAtAscIdAsc(request.getId());

    var lastTransition =
        records.isEmpty() ? request.getCreatedAt() : records.get(records.size() - 1).getOccurredAt();

    return new VerificationSummary(
        request.getId(),
        request.getSubjectRef(),
        request.getState(),
        records.size(),
        request.getCreatedAt(),
        lastTransition,
        request.getState().isSettled(),
        clock.instant());
  }
}
