package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.statemachine.TransitionRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator actions.
 *
 * <p>Unauthenticated in this prototype, which would be indefensible in production - noted in the
 * README rather than papered over. In a real deployment these sit behind an operator role.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private final VerificationService service;
  private final TransitionRegistry registry;

  public AdminController(VerificationService service, TransitionRegistry registry) {
    this.service = service;
    this.registry = registry;
  }

  @PostMapping("/verifications/{id}/override")
  public ApiDtos.SummaryResponse override(
      @PathVariable UUID id, @Valid @RequestBody ApiDtos.OverrideRequest body) {
    VerificationRequest r = service.override(id, body.approve(), body.reason());
    return new ApiDtos.SummaryResponse(
        r.getId(),
        r.getSubjectRef(),
        r.getState(),
        r.getState().isSettled(),
        -1, // not read-model derived; the summary endpoint is the one that reports counts
        r.getCreatedAt(),
        r.getUpdatedAt(),
        r.getUpdatedAt(),
        registry.allowedEventsFrom(r.getState()));
  }

  /** Re-drives reconciliation for a request the vendor never resolved. */
  @PostMapping("/verifications/{id}/reconcile")
  public ResponseEntity<Void> reconcile(@PathVariable UUID id) {
    service.requestReconcile(id);
    return ResponseEntity.accepted().build();
  }

  /**
   * The state machine, as the running service understands it. Useful for a reviewer, and a cheap way
   * to prove the table in the README has not drifted from the code.
   */
  @GetMapping("/state-machine")
  public List<String> stateMachine() {
    return registry.allEdges().stream().map(Object::toString).sorted().toList();
  }
}
