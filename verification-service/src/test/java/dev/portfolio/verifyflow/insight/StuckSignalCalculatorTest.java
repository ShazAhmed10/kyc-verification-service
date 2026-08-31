package dev.portfolio.verifyflow.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.domain.VerificationState;
import dev.portfolio.verifyflow.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These matter because the AI feature's credibility rests on them: the model narrates whatever these
 * signals say, so if the detection is wrong the explanation is confidently wrong.
 */
class StuckSignalCalculatorTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

  private VerificationRequestRepository requests;
  private StateChangeRecordRepository history;
  private MutableClock clock;
  private StuckSignalCalculator calculator;

  @BeforeEach
  void setUp() {
    requests = mock(VerificationRequestRepository.class);
    history = mock(StateChangeRecordRepository.class);
    when(history.findByVerificationIdOrderByOccurredAtAscIdAsc(any())).thenReturn(List.of());
    when(history.existsByVerificationIdAndEvent(any(), any())).thenReturn(false);
    clock = new MutableClock(T0);

    VerifyFlowProperties props =
        new VerifyFlowProperties(
            null, null, null, null, null,
            new VerifyFlowProperties.Insight(3.0, Duration.ofMinutes(5)),
            null);
    calculator = new StuckSignalCalculator(requests, history, props, clock);
  }

  private VerificationRequest pending() {
    VerificationRequest r = VerificationRequest.start("s1", VerificationState.PENDING, T0);
    r.attachVendorSession("vp_1", T0.plusSeconds(900), T0);
    return r;
  }

  private void cohort(Instant... entryTimes) {
    when(requests.findEntryTimesInState(VerificationState.PENDING)).thenReturn(List.of(entryTimes));
  }

  @Test
  @DisplayName("a young request is not stuck")
  void youngRequestNotStuck() {
    VerificationRequest r = pending();
    cohort(T0);
    clock.advance(Duration.ofMinutes(1));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isFalse();
    assertThat(s.likelyCause()).contains("within expected time");
  }

  @Test
  @DisplayName("the threshold is the configured expectation, not derived from the cohort")
  void thresholdIsAbsolute() {
    VerificationRequest r = pending();
    // However slow the cohort is, the gate stays at the configured 5 minutes.
    cohort(T0.plus(Duration.ofHours(6)));
    clock.advance(Duration.ofMinutes(4));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isFalse();
    assertThat(s.stuckThreshold()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("a systemic stall is still detected - the regression that motivated the absolute gate")
  void systemicStallStillDetected() {
    VerificationRequest r = pending();
    // Every peer is just as old, so the relative ratio is ~1.0 and a relative-only detector would
    // report nothing. The absolute gate must still fire.
    cohort(T0.plus(Duration.ofSeconds(1)), T0.plus(Duration.ofSeconds(2)));
    clock.advance(Duration.ofHours(2));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isTrue();
    assertThat(s.cohortOutlier()).isFalse();
    assertThat(s.ratioToCohortMedian()).isPresent();
    assertThat(s.ratioToCohortMedian().getAsDouble()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.05));
  }

  @Test
  @DisplayName("a lone slow request among fast peers is flagged as an outlier too")
  void loneSlowRequestIsAnOutlier() {
    VerificationRequest r = pending();
    cohort(T0.plus(Duration.ofMinutes(119)), T0.plus(Duration.ofMinutes(118)));
    clock.advance(Duration.ofHours(2));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isTrue();
    assertThat(s.cohortOutlier()).isTrue();
  }

  @Test
  @DisplayName("an empty cohort falls back to the floor rather than dividing by zero")
  void emptyCohortUsesFloor() {
    VerificationRequest r = pending();
    cohort(); // nothing else in this state
    clock.advance(Duration.ofMinutes(4));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.cohortSize()).isZero();
    assertThat(s.cohortMedianInState()).isEqualTo(Duration.ZERO);
    assertThat(s.stuckThreshold()).isEqualTo(Duration.ofMinutes(5));
    assertThat(s.ratioToCohortMedian()).isEmpty(); // no division attempted
    assertThat(s.cohortOutlier()).isFalse();
    assertThat(s.stuck()).isFalse();
  }

  @Test
  @DisplayName("a request far past the threshold is flagged, with the lost-callback cause identified")
  void oldRequestFlaggedWithCause() {
    VerificationRequest r = pending();
    cohort(T0);
    clock.advance(Duration.ofHours(2));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isTrue();
    assertThat(s.callbackEverReceived()).isFalse();
    assertThat(s.reconcileAttempts()).isZero();
    assertThat(s.likelyCause()).contains("callback never arrived");
    assertThat(s.sessionExpired()).isTrue();
  }

  @Test
  @DisplayName("the cause distinguishes 'never reconciled' from 'reconciled but unresolved'")
  void causeDistinguishesReconciliationHistory() {
    VerificationRequest r = pending();
    cohort(T0);
    clock.advance(Duration.ofHours(2));
    r.recordReconcileAttempt(clock.instant());

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isTrue();
    assertThat(s.likelyCause()).contains("did not resolve");
  }

  @Test
  @DisplayName("a request the vendor accepted but never decided reports that specifically")
  void inReviewWithAttemptsReportsVendorSilence() {
    VerificationRequest r = VerificationRequest.start("s1", VerificationState.PENDING, T0);
    r.attachVendorSession("vp_1", T0.plusSeconds(900), T0);
    r.applyState(VerificationState.IN_REVIEW, T0);
    r.recordReconcileAttempt(T0);
    when(requests.findEntryTimesInState(VerificationState.IN_REVIEW)).thenReturn(List.of(T0));
    when(history.existsByVerificationIdAndEvent(r.getId(), VerificationEvent.CALLBACK_RECEIVED))
        .thenReturn(true);
    clock.advance(Duration.ofHours(3));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.stuck()).isTrue();
    assertThat(s.likelyCause()).contains("has not returned a final verdict");
  }

  @Test
  @DisplayName("a settled request is never flagged, however old it is")
  void settledIsNeverStuck() {
    VerificationRequest r = pending();
    r.applyState(VerificationState.VERIFIED, T0);
    when(requests.findEntryTimesInState(VerificationState.VERIFIED)).thenReturn(List.of(T0));
    clock.advance(Duration.ofDays(30));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.settled()).isTrue();
    assertThat(s.stuck()).isFalse();
    assertThat(s.likelyCause()).contains("settled");
  }

  @Test
  @DisplayName("the median is the middle of the cohort, not the mean")
  void medianNotMean() {
    VerificationRequest r = pending();
    // Peers entered at +1m, +30m, +59m -> ages at T0+60m are 59m, 30m, 1m. Median = 30m; the mean
    // would be 30m too, so use an asymmetric set: +1m, +2m, +59m -> ages 59m, 58m, 1m, median 58m.
    cohort(T0.plus(Duration.ofMinutes(1)), T0.plus(Duration.ofMinutes(2)), T0.plus(Duration.ofMinutes(59)));
    clock.advance(Duration.ofMinutes(60));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.cohortSize()).isEqualTo(3);
    assertThat(s.cohortMedianInState()).isEqualTo(Duration.ofMinutes(58));
  }

  @Test
  @DisplayName("the request does not appear in its own cohort")
  void selfExcludedFromCohort() {
    VerificationRequest r = pending(); // entered its state at T0
    cohort(T0, T0.plus(Duration.ofMinutes(10)));
    clock.advance(Duration.ofMinutes(20));

    StuckSignals s = calculator.calculate(r);

    assertThat(s.cohortSize()).isEqualTo(1);
  }
}
