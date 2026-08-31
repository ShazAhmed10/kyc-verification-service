package dev.portfolio.verifyflow.insight;

import dev.portfolio.verifyflow.domain.StateChangeRecord;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.statemachine.TransitionRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explains what happened to one verification, in plain English.
 *
 * <p>The division of labour is the design: {@link StuckSignalCalculator} decides <em>whether</em>
 * anything is wrong and <em>what</em> the numbers are; this class turns that into a prompt; the model
 * only writes the prose. Every number in the output came from SQL, so the explanation cannot invent a
 * percentile or misread a duration.
 *
 * <p>Practical consequence: the endpoint still works with no API key. It returns the signals and a
 * deterministic one-line summary, and simply omits the narrative.
 */
@Service
public class HistoryExplainer {

  private static final String SYSTEM_PROMPT =
      """
      You are helping an on-call engineer understand one identity-verification request in a backend \
      system.

      You will be given: the request's current state, a timeline of its state transitions, and a set of \
      signals that have already been computed from the database.

      Write two short paragraphs. The first says plainly what happened to this request, in sequence. The \
      second says whether anything looks wrong and what the operator should do next.

      Rules:
      - Use only the facts given. Do not invent timings, counts, or causes.
      - The signals are authoritative. If a signal says the request is not stuck, do not call it stuck.
      - Do not restate the raw numbers; interpret them.
      - No bullet points, no headings, no preamble. Just the two paragraphs.
      - If nothing is wrong, say so briefly rather than manufacturing concern.
      """;

  private final VerificationRequestRepository requests;
  private final StateChangeRecordRepository history;
  private final StuckSignalCalculator calculator;
  private final TransitionRegistry registry;
  private final LlmClient llm;

  public HistoryExplainer(
      VerificationRequestRepository requests,
      StateChangeRecordRepository history,
      StuckSignalCalculator calculator,
      TransitionRegistry registry,
      LlmClient llm) {
    this.requests = requests;
    this.history = history;
    this.calculator = calculator;
    this.registry = registry;
    this.llm = llm;
  }

  @Transactional(readOnly = true)
  public ExplanationReport explain(UUID verificationId) {
    VerificationRequest request =
        requests
            .findById(verificationId)
            .orElseThrow(() -> new NoSuchElementException("No verification " + verificationId));

    StuckSignals signals = calculator.calculate(request);
    List<String> timeline = renderTimeline(verificationId);
    String summary = deterministicSummary(signals);

    String narrative = llm.complete(SYSTEM_PROMPT, buildPrompt(signals, timeline)).orElse(null);

    return new ExplanationReport(signals, summary, timeline, narrative, narrative != null);
  }

  private List<String> renderTimeline(UUID id) {
    List<StateChangeRecord> records = history.findByVerificationIdOrderByOccurredAtAscIdAsc(id);
    List<String> lines = new ArrayList<>(records.size());
    for (StateChangeRecord r : records) {
      String move = r.getFromState() == r.getToState() ? "stayed " + r.getToState() : r.getFromState() + " -> " + r.getToState();
      lines.add(r.getOccurredAt() + "  " + r.getEvent() + "  (" + move + ")"
          + (r.getDetail() == null ? "" : "  [" + r.getDetail() + "]"));
    }
    return lines;
  }

  /** The answer we can give with no model at all. Also the fallback when the model is unavailable. */
  private String deterministicSummary(StuckSignals s) {
    StringBuilder sb = new StringBuilder();
    sb.append("State ").append(s.state()).append(" for ").append(humanise(s.timeInCurrentState()));
    sb.append(s.settled() ? "; settled." : "; not settled.");
    if (s.stuck()) {
      sb.append(" Flagged as stuck (expected within ").append(humanise(s.stuckThreshold())).append(")");
      if (s.cohortOutlier()) {
        s.ratioToCohortMedian()
            .ifPresent(r -> sb.append(String.format(" and %.1fx slower than its peers", r)));
      } else if (s.cohortSize() > 0) {
        sb.append(", though its peers are similarly slow");
      }
      sb.append(". Likely cause: ").append(s.likelyCause()).append(".");
    } else if (!s.settled()) {
      sb.append(" Within expected time for its state.");
    }
    return sb.toString();
  }

  private String buildPrompt(StuckSignals s, List<String> timeline) {
    StringBuilder sb = new StringBuilder();
    sb.append("Request: ").append(s.verificationId()).append('\n');
    sb.append("Current state: ").append(s.state()).append(s.settled() ? " (settled)" : " (still in progress)").append('\n');
    sb.append("Time in current state: ").append(humanise(s.timeInCurrentState())).append('\n');
    sb.append("Transitions recorded: ").append(s.transitionCount()).append('\n');
    sb.append("Legal next events from here: ").append(registry.allowedEventsFrom(s.state())).append("\n\n");

    sb.append("Computed signals (authoritative):\n");
    sb.append("- flagged stuck (absolute, vs service expectation): ").append(s.stuck()).append('\n');
    sb.append("- expectation for this state: ").append(humanise(s.stuckThreshold())).append('\n');
    sb.append("- unusually slow vs peers (relative): ").append(s.cohortOutlier()).append('\n');
    sb.append("- cohort median time in this state: ").append(humanise(s.cohortMedianInState()))
        .append(" across ").append(s.cohortSize()).append(" request(s)\n");
    s.ratioToCohortMedian()
        .ifPresent(r -> sb.append(String.format("- ratio to cohort median: %.2fx%n", r)));
    sb.append("- vendor callback ever received: ").append(s.callbackEverReceived()).append('\n');
    sb.append("- reconciliation attempts: ").append(s.reconcileAttempts()).append('\n');
    sb.append("- vendor session expired: ").append(s.sessionExpired()).append('\n');
    sb.append("- deterministic hypothesis: ").append(s.likelyCause()).append("\n\n");

    sb.append("Timeline:\n");
    if (timeline.isEmpty()) {
      sb.append("(no transitions recorded)\n");
    } else {
      timeline.forEach(l -> sb.append(l).append('\n'));
    }
    return sb.toString();
  }

  private String humanise(Duration d) {
    if (d.isZero()) {
      return "0s";
    }
    long h = d.toHours();
    long m = d.toMinutesPart();
    long sec = d.toSecondsPart();
    if (h > 0) {
      return h + "h " + m + "m";
    }
    return m > 0 ? m + "m " + sec + "s" : sec + "s";
  }
}
