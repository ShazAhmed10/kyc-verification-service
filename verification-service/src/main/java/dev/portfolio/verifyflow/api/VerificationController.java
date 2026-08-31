package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.readmodel.ReadRepairService;
import dev.portfolio.verifyflow.readmodel.VerificationSummary;
import dev.portfolio.verifyflow.statemachine.TransitionRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/verifications")
public class VerificationController {

  private final VerificationService service;
  private final ReadRepairService readModel;
  private final StateChangeRecordRepository history;
  private final TransitionRegistry registry;

  public VerificationController(
      VerificationService service,
      ReadRepairService readModel,
      StateChangeRecordRepository history,
      TransitionRegistry registry) {
    this.service = service;
    this.readModel = readModel;
    this.history = history;
    this.registry = registry;
  }

  @PostMapping
  public ResponseEntity<ApiDtos.StartResponse> start(@Valid @RequestBody ApiDtos.StartRequest body) {
    VerificationRequest r = service.start(body.subjectRef());
    // The session token is returned once, here, and never stored in our read model - the client hands
    // it straight to the vendor's flow.
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiDtos.StartResponse(r.getId(), r.getState(), "issued", r.getSessionExpiresAt()));
  }

  /** Served from the read model, repaired on the way past if stale. */
  @GetMapping("/{id}")
  public ApiDtos.SummaryResponse get(@PathVariable UUID id) {
    VerificationSummary s = readModel.get(id);
    return new ApiDtos.SummaryResponse(
        s.getVerificationId(),
        s.getSubjectRef(),
        s.getState(),
        s.isSettled(),
        s.getTransitionCount(),
        s.getFirstSeenAt(),
        s.getLastTransitionAt(),
        s.getRefreshedAt(),
        registry.allowedEventsFrom(s.getState()));
  }

  /** Straight from the write side - history is the audit trail and is never served stale. */
  @GetMapping("/{id}/history")
  public ApiDtos.HistoryResponse history(@PathVariable UUID id) {
    VerificationSummary s = readModel.get(id);
    List<ApiDtos.HistoryEntry> entries =
        history.findByVerificationIdOrderByOccurredAtAscIdAsc(id).stream()
            .map(
                r ->
                    new ApiDtos.HistoryEntry(
                        r.getEvent(), r.getFromState(), r.getToState(), r.getDetail(), r.getOccurredAt()))
            .toList();
    return new ApiDtos.HistoryResponse(id, s.getState(), entries);
  }
}
