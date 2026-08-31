package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.insight.ExplanationReport;
import dev.portfolio.verifyflow.insight.HistoryExplainer;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/verifications")
public class ExplanationController {

  private final HistoryExplainer explainer;

  public ExplanationController(HistoryExplainer explainer) {
    this.explainer = explainer;
  }

  /**
   * Explains one request's history.
   *
   * <p>Always returns the computed signals and a deterministic summary. The {@code narrative} field is
   * null when no model is configured or the call failed - the endpoint degrades rather than erroring,
   * because an operator looking at a stuck request needs the numbers more than they need the prose.
   */
  @GetMapping("/{id}/explain")
  public ExplanationReport explain(@PathVariable UUID id) {
    return explainer.explain(id);
  }
}
