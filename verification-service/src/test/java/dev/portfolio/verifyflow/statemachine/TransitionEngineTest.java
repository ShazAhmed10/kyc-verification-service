package dev.portfolio.verifyflow.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.portfolio.verifyflow.domain.StateChangeRecord;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationState;
import java.time.Duration;
import dev.portfolio.verifyflow.testsupport.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TransitionEngineTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

  private StateChangeRecordRepository history;
  private MutableClock clock;
  private TransitionEngine engine;

  @BeforeEach
  void setUp() {
    history = mock(StateChangeRecordRepository.class);
    when(history.save(any(StateChangeRecord.class))).thenAnswer(i -> i.getArgument(0));
    clock = new MutableClock(T0);
    TransitionRegistry registry = new TransitionRegistry();
    registry.index();
    engine = new TransitionEngine(registry, history, clock);
  }

  private VerificationRequest pendingRequest() {
    return VerificationRequest.start("subject-1", VerificationState.PENDING, T0);
  }

  @Test
  @DisplayName("a legal transition moves the aggregate and appends exactly one audit row")
  void legalTransition() {
    VerificationRequest r = pendingRequest();
    clock.advance(Duration.ofMinutes(2));

    VerificationState result = engine.apply(r, VerificationEvent.CALLBACK_RECEIVED, "vendor said so");

    assertThat(result).isEqualTo(VerificationState.IN_REVIEW);
    assertThat(r.getState()).isEqualTo(VerificationState.IN_REVIEW);

    ArgumentCaptor<StateChangeRecord> captor = ArgumentCaptor.forClass(StateChangeRecord.class);
    verify(history).save(captor.capture());
    StateChangeRecord rec = captor.getValue();
    assertThat(rec.getEvent()).isEqualTo(VerificationEvent.CALLBACK_RECEIVED);
    assertThat(rec.getFromState()).isEqualTo(VerificationState.PENDING);
    assertThat(rec.getToState()).isEqualTo(VerificationState.IN_REVIEW);
    assertThat(rec.getDetail()).isEqualTo("vendor said so");
    assertThat(rec.getOccurredAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
  }

  @Test
  @DisplayName("stateEnteredAt advances on a real move but not on a self-loop")
  void stateEnteredAtOnlyMovesOnRealTransition() {
    VerificationRequest r = pendingRequest();

    clock.advance(Duration.ofMinutes(5));
    engine.apply(r, VerificationEvent.SESSION_ISSUED, "self-loop");
    // A self-loop records history but must not reset the clock on how long we have been in PENDING -
    // otherwise stuck-detection could be defeated by any repeated no-op event.
    assertThat(r.getStateEnteredAt()).isEqualTo(T0);

    clock.advance(Duration.ofMinutes(5));
    engine.apply(r, VerificationEvent.CALLBACK_RECEIVED, "real move");
    assertThat(r.getStateEnteredAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
  }

  @Test
  @DisplayName("an illegal transition throws and changes nothing")
  void illegalTransitionThrows() {
    VerificationRequest r = pendingRequest();
    engine.apply(r, VerificationEvent.CALLBACK_RECEIVED, null);
    engine.apply(r, VerificationEvent.RESULT_APPROVED, null); // now VERIFIED

    assertThatThrownBy(() -> engine.apply(r, VerificationEvent.CALLBACK_RECEIVED, null))
        .isInstanceOf(IllegalTransitionException.class);

    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);
  }

  @Test
  @DisplayName("applyIfAllowed is a no-op on an already-settled request and writes no audit row")
  void applyIfAllowedIsIdempotentOnSettled() {
    VerificationRequest r = pendingRequest();
    engine.apply(r, VerificationEvent.RESULT_APPROVED, "first result");
    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);

    org.mockito.Mockito.reset(history);

    // This is the redelivery case: the queue hands us the same task again after we already decided.
    boolean applied = engine.applyIfAllowed(r, VerificationEvent.RESULT_APPROVED, "redelivery");

    assertThat(applied).isFalse();
    assertThat(r.getState()).isEqualTo(VerificationState.VERIFIED);
    verify(history, never()).save(any());
  }

  @Test
  @DisplayName("INCONCLUSIVE is recorded but leaves the request unsettled")
  void inconclusiveRecordsWithoutSettling() {
    VerificationRequest r = pendingRequest();
    engine.apply(r, VerificationEvent.CALLBACK_RECEIVED, null);

    engine.apply(r, VerificationEvent.RESULT_INCONCLUSIVE, "vendor undecided");

    assertThat(r.getState()).isEqualTo(VerificationState.IN_REVIEW);
    assertThat(r.getState().isSettled()).isFalse();
  }

}
