package dev.portfolio.vendorsim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Fires the callback back to the verification service, after a delay, the way a real vendor would.
 *
 * <p>Asynchronous on purpose: the session-creation response must return immediately, and the callback
 * must arrive later and out of band. Doing it inline would turn the whole integration into a synchronous
 * call and hide the very race the design exists to handle.
 */
@Component
public class CallbackSender {

  private static final Logger log = LoggerFactory.getLogger(CallbackSender.class);

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private final String callbackUrl;
  private final String sharedSecret;
  private final long delayMs;

  public CallbackSender(
      @Value("${vendorsim.callback-url:http://localhost:8080/webhooks/verifypro}") String callbackUrl,
      @Value("${vendorsim.webhook-secret:}") String sharedSecret,
      @Value("${vendorsim.callback-delay-ms:1500}") long delayMs) {
    this.callbackUrl = callbackUrl;
    this.sharedSecret = sharedSecret;
    this.delayMs = delayMs;
  }

  @Async
  public void scheduleCallback(String vendorRef) {
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }

    String body =
        "{\"vendorRef\":\"" + vendorRef + "\",\"eventType\":\"VERIFICATION_COMPLETE\"}";
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(callbackUrl))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (!sharedSecret.isBlank()) {
      b.header("X-Webhook-Secret", sharedSecret);
    }

    try {
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      log.info("Callback for {} -> HTTP {}", vendorRef, res.statusCode());
    } catch (Exception e) {
      // A real vendor would retry. Logging is enough here: the service's sweeper is the backstop and
      // demonstrating that is more interesting than reimplementing vendor-side retry.
      log.warn("Callback for {} failed: {}", vendorRef, e.getMessage());
    }
  }
}
