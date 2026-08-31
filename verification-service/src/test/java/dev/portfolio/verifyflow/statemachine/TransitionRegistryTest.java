package dev.portfolio.verifyflow.statemachine;

import static dev.portfolio.verifyflow.domain.VerificationEvent.*;
import static dev.portfolio.verifyflow.domain.VerificationState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.portfolio.verifyflow.domain.StateTransition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransitionRegistryTest {

  private TransitionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new TransitionRegistry();
    registry.index();
  }

  @Test
  @DisplayName("the declared table is unambiguous")
  void tableIsUnambiguous() {
    // The real guarantee: indexing the production table must not throw. If someone adds a duplicate
    // (event, from) row, this fails here rather than in production.
    assertThat(registry.allEdges()).hasSize(TransitionTable.TRANSITIONS.size());
  }

  @Test
  @DisplayName("a duplicate (event, from) key is rejected at startup, not resolved arbitrarily")
  void duplicateKeyRejected() {
    List<StateTransition> broken =
        List.of(
            new StateTransition(RESULT_APPROVED, IN_REVIEW, VERIFIED),
            new StateTransition(RESULT_APPROVED, IN_REVIEW, REJECTED)); // same key, different target

    TransitionRegistry bad = new TransitionRegistry(broken);

    assertThatThrownBy(bad::index)
        .isInstanceOf(AmbiguousTransitionTableException.class)
        .hasMessageContaining("RESULT_APPROVED")
        .hasMessageContaining("VERIFIED")
        .hasMessageContaining("REJECTED");
  }

  @Test
  @DisplayName("the same event resolves to different targets depending on current state")
  void sameEventDifferentSourceStates() {
    // This is the property that makes (event, from) the right key. RESULT_APPROVED means "verified"
    // from either transient state, and both edges must exist independently.
    assertThat(registry.resolveTarget(RESULT_APPROVED, IN_REVIEW)).isEqualTo(VERIFIED);
    assertThat(registry.resolveTarget(RESULT_APPROVED, PENDING)).isEqualTo(VERIFIED);
    assertThat(registry.resolveTarget(CALLBACK_RECEIVED, PENDING)).isEqualTo(IN_REVIEW);
  }

  @Test
  @DisplayName("approval straight from PENDING is legal - the missed-callback path")
  void approvalFromPendingIsLegal() {
    // If the vendor's callback never arrives, reconciliation still finds the verdict and must be able
    // to settle the request without a fabricated intermediate transition.
    assertThat(registry.isAllowed(RESULT_APPROVED, PENDING)).isTrue();
    assertThat(registry.isAllowed(RESULT_DECLINED, PENDING)).isTrue();
  }

  @Test
  @DisplayName("undeclared moves are refused")
  void undeclaredMovesRefused() {
    // A settled request cannot be re-decided by the vendor path...
    assertThat(registry.isAllowed(RESULT_APPROVED, VERIFIED)).isFalse();
    assertThat(registry.isAllowed(RESULT_DECLINED, REJECTED)).isFalse();
    // ...nor can a callback arrive for something already settled.
    assertThat(registry.isAllowed(CALLBACK_RECEIVED, VERIFIED)).isFalse();
    // ...and expiry only applies to transient states.
    assertThat(registry.isAllowed(EXPIRE, VERIFIED)).isFalse();

    assertThatThrownBy(() -> registry.resolveTarget(RESULT_APPROVED, VERIFIED))
        .isInstanceOf(IllegalTransitionException.class);
  }

  @Test
  @DisplayName("an operator can still move a settled request")
  void operatorCanOverrideSettled() {
    assertThat(registry.resolveTarget(OVERRIDE_REJECT, VERIFIED)).isEqualTo(REJECTED);
    assertThat(registry.resolveTarget(OVERRIDE_APPROVE, REJECTED)).isEqualTo(VERIFIED);
    assertThat(registry.resolveTarget(OVERRIDE_APPROVE, EXPIRED)).isEqualTo(VERIFIED);
  }

  @Test
  @DisplayName("INCONCLUSIVE keeps the request waiting rather than settling it")
  void inconclusiveIsASelfLoop() {
    assertThat(registry.resolveTarget(RESULT_INCONCLUSIVE, IN_REVIEW)).isEqualTo(IN_REVIEW);
    // And it is not a way to escape PENDING - there is no such edge.
    assertThat(registry.isAllowed(RESULT_INCONCLUSIVE, PENDING)).isFalse();
  }

  @Test
  @DisplayName("allowedEventsFrom reports what a caller could legally do next")
  void allowedEventsFrom() {
    assertThat(registry.allowedEventsFrom(PENDING))
        .containsExactlyInAnyOrder(SESSION_ISSUED, CALLBACK_RECEIVED, RESULT_APPROVED, RESULT_DECLINED, EXPIRE);
    assertThat(registry.allowedEventsFrom(VERIFIED)).containsExactly(OVERRIDE_REJECT);
  }

  @Test
  @DisplayName("every state except the initial one is reachable, and both settled states are terminal-ish")
  void reachability() {
    // Guards against a row being deleted and a state becoming orphaned.
    var targets = registry.allEdges().stream().map(StateTransition::to).distinct().toList();
    assertThat(targets).contains(IN_REVIEW, VERIFIED, REJECTED, EXPIRED);
    assertThat(VERIFIED.isSettled()).isTrue();
    assertThat(REJECTED.isSettled()).isTrue();
    assertThat(EXPIRED.isSettled()).isFalse(); // expired is not a decision
    assertThat(PENDING.isTransient()).isTrue();
  }
}
