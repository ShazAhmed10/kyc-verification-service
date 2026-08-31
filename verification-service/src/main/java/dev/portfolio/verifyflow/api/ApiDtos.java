package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for the HTTP layer. Grouped so the API contract reads in one place. */
public final class ApiDtos {

  private ApiDtos() {}

  public record StartRequest(
      @NotBlank(message = "subjectRef is required") @Size(max = 64) String subjectRef) {}

  public record StartResponse(UUID id, VerificationState state, String sessionToken, Instant sessionExpiresAt) {}

  public record SummaryResponse(
      UUID id,
      String subjectRef,
      VerificationState state,
      boolean settled,
      int transitionCount,
      Instant firstSeenAt,
      Instant lastTransitionAt,
      Instant refreshedAt,
      Set<VerificationEvent> allowedNextEvents) {}

  public record HistoryEntry(
      VerificationEvent event, VerificationState from, VerificationState to, String detail, Instant occurredAt) {}

  public record HistoryResponse(UUID id, VerificationState currentState, List<HistoryEntry> history) {}

  public record CallbackRequest(@NotBlank String vendorRef, @NotBlank String eventType) {}

  public record OverrideRequest(boolean approve, @NotBlank @Size(max = 200) String reason) {}

  public record ErrorResponse(String error, String message) {}
}
