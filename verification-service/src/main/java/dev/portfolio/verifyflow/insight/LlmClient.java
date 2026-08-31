package dev.portfolio.verifyflow.insight;

/**
 * Narrow seam over the language model.
 *
 * <p>One method, because that is all this project needs, and because an interface this small means the
 * tests can drive the whole explain path with a two-line stub and no API key. The model is a rendering
 * detail here - everything it is told has already been decided by code.
 */
public interface LlmClient {

  /**
   * Turns a prompt into prose.
   *
   * @return the model's text, or empty if the model is unavailable or declined to answer
   */
  java.util.Optional<String> complete(String systemPrompt, String userPrompt);

  /** Whether this client can actually reach a model. Drives the graceful-degradation path. */
  boolean available();
}
