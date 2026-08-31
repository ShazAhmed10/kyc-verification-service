package dev.portfolio.verifyflow.insight;

import java.util.List;

/**
 * What the /explain endpoint returns.
 *
 * <p>{@code signals} and {@code deterministicSummary} are always populated; {@code narrative} is present
 * only when a model produced one. Returning both means the endpoint is useful with no API key at all,
 * and that a reader can always check the prose against the numbers it was generated from.
 */
public record ExplanationReport(
    StuckSignals signals,
    String deterministicSummary,
    List<String> timeline,
    String narrative,
    boolean narrativeAvailable) {}
