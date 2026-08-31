package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the vendor tells us something changed.
 *
 * <p>The shared-secret check here is defence in depth and nothing more. The reason a weak check is
 * tolerable is structural rather than cryptographic: this endpoint carries no verdict. It can only
 * cause us to go and ask the vendor what the verdict is. A forged call therefore costs us a wasted
 * reconciliation, not a wrong decision.
 *
 * <p>What this is not: it is not an HMAC over the body, and there is no timestamp or nonce, so a
 * captured call can be replayed. Replay is absorbed by the receipt table rather than prevented here.
 * A production integration should sign the body and reject stale timestamps; that is called out in the
 * README's trade-offs rather than left for a reviewer to notice.
 */
@RestController
@RequestMapping("/webhooks/verifypro")
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private final VerificationService service;
  private final VerifyFlowProperties.Webhook cfg;

  public WebhookController(VerificationService service, VerifyFlowProperties props) {
    this.service = service;
    this.cfg = props.webhook();
  }

  @PostMapping
  public ResponseEntity<Void> receive(
      @RequestHeader(value = "X-Webhook-Secret", required = false) String presentedSecret,
      @Valid @RequestBody ApiDtos.CallbackRequest body) {

    if (!secretAccepted(presentedSecret)) {
      log.warn("Rejected callback for {} - bad or missing secret", body.vendorRef());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    boolean accepted = service.handleCallback(body.vendorRef(), body.eventType());

    // 202 either way. A duplicate is not the vendor's problem to solve, and returning an error would
    // make it retry something that has already been handled correctly.
    log.debug("Callback {} / {} accepted={}", body.vendorRef(), body.eventType(), accepted);
    return ResponseEntity.accepted().build();
  }

  private boolean secretAccepted(String presented) {
    if (!cfg.requireSecret()) {
      return true;
    }
    if (cfg.sharedSecret().isBlank() || presented == null) {
      return false;
    }
    // Constant-time compare. The window it closes is narrow given everything above, but a timing-safe
    // comparison costs nothing and a plain equals invites the obvious question in review.
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), cfg.sharedSecret().getBytes(StandardCharsets.UTF_8));
  }
}
