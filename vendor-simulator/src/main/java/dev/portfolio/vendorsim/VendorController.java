package dev.portfolio.vendorsim;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The two endpoints the verification service calls, plus the callback the vendor initiates. */
@RestController
public class VendorController {

  private static final Logger log = LoggerFactory.getLogger(VendorController.class);

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final CallbackSender callbacks;
  private final Clock clock = Clock.systemUTC();

  public VendorController(CallbackSender callbacks) {
    this.callbacks = callbacks;
  }

  @PostMapping("/sessions")
  public Map<String, Object> createSession(@RequestBody Map<String, String> body) {
    String subjectRef = body.getOrDefault("subjectRef", "unknown");
    Behaviour behaviour = Behaviour.forSubject(subjectRef);
    String vendorRef = "vp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

    Session session = new Session(vendorRef, subjectRef, behaviour, clock.instant());
    sessions.put(vendorRef, session);
    log.info("Session {} created for {} (behaviour={})", vendorRef, subjectRef, behaviour);

    if (behaviour != Behaviour.SILENT) {
      callbacks.scheduleCallback(vendorRef);
    } else {
      log.info("Session {} is SILENT - no callback will be sent", vendorRef);
    }

    return Map.of(
        "vendorRef", vendorRef,
        "sessionToken", "tok_" + vendorRef,
        "expiresAt", clock.instant().plusSeconds(900).toString());
  }

  @GetMapping("/results/{vendorRef}")
  public ResponseEntity<Map<String, Object>> result(@PathVariable String vendorRef) {
    Session s = sessions.get(vendorRef);
    if (s == null) {
      return ResponseEntity.status(404).body(Map.of("outcome", "UNKNOWN", "reason", "no_such_reference"));
    }

    int read = s.resultReads.incrementAndGet();

    switch (s.behaviour) {
      case FLAKY -> {
        if (read <= 2) {
          log.info("{}: failing result read #{} (FLAKY)", vendorRef, read);
          return ResponseEntity.status(503).body(Map.of("error", "temporarily_unavailable"));
        }
      }
      case SLOW -> sleep(15_000);
      case UNKNOWN -> {
        return ResponseEntity.ok(Map.of("outcome", "UNKNOWN", "reason", "not_found_upstream"));
      }
      default -> {
        // no special handling
      }
    }

    String outcome =
        switch (s.behaviour) {
          case DECLINE -> "DECLINED";
          case FLIP -> read <= 1 ? "INCONCLUSIVE" : "APPROVED";
          default -> "APPROVED";
        };

    log.info("{}: result read #{} -> {}", vendorRef, read, outcome);
    return ResponseEntity.ok(Map.of("outcome", outcome, "reason", "simulated_" + s.behaviour));
  }

  /** Lets a demo or test force a duplicate callback, to show de-duplication working. */
  @PostMapping("/admin/resend/{vendorRef}")
  public ResponseEntity<Void> resend(@PathVariable String vendorRef) {
    if (!sessions.containsKey(vendorRef)) {
      return ResponseEntity.notFound().build();
    }
    callbacks.scheduleCallback(vendorRef);
    return ResponseEntity.accepted().build();
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
