package dev.portfolio.verifyflow.insight;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls Claude through the official Anthropic Java SDK.
 *
 * <p>Configuration choices worth explaining, since they are the kind of thing an interviewer asks
 * about:
 *
 * <ul>
 *   <li><b>Effort is LOW.</b> The model is writing two paragraphs of prose from facts that have already
 *       been computed. It is not doing the analysis, so paying for deep reasoning would buy nothing.
 *       This is the direct consequence of keeping detection in {@link StuckSignalCalculator}.
 *   <li><b>Adaptive thinking is on.</b> It costs little at low effort and keeps the output coherent when
 *       the signals conflict.
 *   <li><b>The API key is read from the environment only.</b> There is no committed default and the
 *       feature disables itself rather than starting up in a broken state.
 *   <li><b>A refusal is treated as unavailability, not an error.</b> The endpoint falls back to the
 *       deterministic report. Production code would additionally opt into server-side refusal
 *       fallbacks; that binding is deliberately not guessed at here.
 * </ul>
 */
@Component
public class AnthropicLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

  private final VerifyFlowProperties.Llm cfg;
  private final AnthropicClient client;

  public AnthropicLlmClient(VerifyFlowProperties props) {
    this.cfg = props.llm();
    if (cfg.usable()) {
      this.client =
          AnthropicOkHttpClient.builder()
              .apiKey(cfg.apiKey())
              .timeout(cfg.timeout())
              .build();
      log.info("LLM explanations enabled (model={})", cfg.model());
    } else {
      this.client = null;
      log.info("LLM explanations disabled - no API key configured; /explain will return the deterministic report only");
    }
  }

  @Override
  public boolean available() {
    return client != null;
  }

  @Override
  public Optional<String> complete(String systemPrompt, String userPrompt) {
    if (client == null) {
      return Optional.empty();
    }
    try {
      MessageCreateParams params =
          MessageCreateParams.builder()
              .model(cfg.model())
              .maxTokens((long) cfg.maxTokens())
              .thinking(ThinkingConfigAdaptive.builder().build())
              .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
              .system(systemPrompt)
              .addUserMessage(userPrompt)
              .build();

      Message response = client.messages().create(params);

      // Always check why generation stopped before reading content: a refusal returns HTTP 200 with
      // no usable text, and reading content blindly would surface an empty explanation as a success.
      if (response.stopReason().map(Object::toString).filter(r -> r.contains("refusal")).isPresent()) {
        log.warn("Model declined to answer; falling back to the deterministic report");
        return Optional.empty();
      }

      String text =
          response.content().stream()
              .flatMap(block -> block.text().stream())
              .map(t -> t.text())
              .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

      return text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    } catch (Exception e) {
      // An explanation is a convenience. Losing it must never turn a working read endpoint into a 500.
      log.warn("LLM call failed: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
