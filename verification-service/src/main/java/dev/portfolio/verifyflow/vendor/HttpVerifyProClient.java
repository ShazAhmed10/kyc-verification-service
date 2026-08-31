package dev.portfolio.verifyflow.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the vendor, built on the JDK client rather than a framework one to keep the
 * dependency surface small and the timeout handling explicit.
 *
 * <p>Two timeouts matter and they are set separately: a short connect timeout so an unreachable
 * vendor fails fast, and a longer read timeout because the result call can legitimately be slow while
 * the vendor finishes its own checks. Both come from config, so an environment with a slower vendor
 * does not need a rebuild.
 */
@Component
public class HttpVerifyProClient implements VerifyProClient {

  private static final Logger log = LoggerFactory.getLogger(HttpVerifyProClient.class);
  private static final String API_KEY_HEADER = "X-Api-Key";

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final VerifyFlowProperties.Vendor cfg;

  public HttpVerifyProClient(VerifyFlowProperties props, ObjectMapper mapper) {
    this.cfg = props.vendor();
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build();
  }

  @Override
  public VerifyProSession createSession(String subjectRef) {
    String body = "{\"subjectRef\":\"" + subjectRef + "\"}";
    JsonNode json = send("/sessions", body, "createSession");
    return new VerifyProSession(
        json.path("vendorRef").asText(),
        json.path("sessionToken").asText(),
        Instant.parse(json.path("expiresAt").asText()));
  }

  @Override
  public VerifyProResult fetchResult(String vendorRef) {
    JsonNode json = send("/results/" + vendorRef, null, "fetchResult");
    String raw = json.path("outcome").asText("");
    VerificationOutcome outcome;
    try {
      outcome = VerificationOutcome.valueOf(raw);
    } catch (IllegalArgumentException e) {
      // An outcome we do not recognise is a contract change, not a verdict. Surfacing it as UNKNOWN
      // keeps the request unsettled and retryable instead of guessing.
      log.warn("Vendor returned unrecognised outcome '{}' for {}", raw, vendorRef);
      outcome = VerificationOutcome.UNKNOWN;
    }
    return new VerifyProResult(vendorRef, outcome, json.path("reason").asText(null));
  }

  private JsonNode send(String path, String jsonBody, String op) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path))
            .timeout(cfg.readTimeout())
            .header("Accept", "application/json");
    if (!cfg.apiKey().isBlank()) {
      b.header(API_KEY_HEADER, cfg.apiKey());
    }
    if (jsonBody == null) {
      b.GET();
    } else {
      b.header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    }

    try {
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) {
        throw new VendorCallFailedException(
            op + " returned HTTP " + res.statusCode() + " from " + path);
      }
      return mapper.readTree(res.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VendorCallFailedException(op + " interrupted", e);
    } catch (VendorCallFailedException e) {
      throw e;
    } catch (Exception e) {
      throw new VendorCallFailedException(op + " failed against " + path, e);
    }
  }
}
