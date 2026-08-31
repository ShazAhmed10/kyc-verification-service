package dev.portfolio.verifyflow.statemachine;

import dev.portfolio.verifyflow.domain.StateTransition;
import dev.portfolio.verifyflow.domain.VerificationEvent;
import dev.portfolio.verifyflow.domain.VerificationState;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Indexes {@link TransitionTable} into a lookup map and validates it once at startup.
 *
 * <p>The validation is the point of this class. A transition table is the kind of thing that grows a
 * duplicate row during a merge, and the failure mode of a duplicate is not an exception - it is the
 * machine quietly picking one of two answers. Checking at boot converts that into a startup crash.
 */
@Component
public class TransitionRegistry {

  private static final Logger log = LoggerFactory.getLogger(TransitionRegistry.class);

  private final Map<StateTransition.Key, StateTransition> byKey = new HashMap<>();
  private final List<StateTransition> declared;

  public TransitionRegistry() {
    this(TransitionTable.TRANSITIONS);
  }

  /** Test seam: lets a test hand in a deliberately broken table. */
  public TransitionRegistry(List<StateTransition> declared) {
    this.declared = List.copyOf(declared);
  }

  @PostConstruct
  public void index() {
    Map<StateTransition.Key, List<StateTransition>> grouped =
        declared.stream().collect(Collectors.groupingBy(StateTransition::key, LinkedHashMap::new, Collectors.toList()));

    List<String> duplicates =
        grouped.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(
                e ->
                    e.getKey().event()
                        + " from "
                        + e.getKey().from()
                        + " -> "
                        + e.getValue().stream()
                            .map(t -> t.to().name())
                            .collect(Collectors.joining(" / ")))
            .toList();

    if (!duplicates.isEmpty()) {
      throw new AmbiguousTransitionTableException(
          "Transition table declares the same (event, from) key more than once: "
              + String.join("; ", duplicates));
    }

    grouped.forEach((key, list) -> byKey.put(key, list.get(0)));
    log.info(
        "Transition table indexed: {} edges over {} events and {} states",
        byKey.size(),
        byKey.keySet().stream().map(StateTransition.Key::event).collect(Collectors.toSet()).size(),
        VerificationState.values().length);
  }

  /** The edge for this event from this state, if one is declared. */
  public Optional<StateTransition> find(VerificationEvent event, VerificationState from) {
    return Optional.ofNullable(byKey.get(new StateTransition.Key(event, from)));
  }

  /** The state this event leads to, or throw. {@code to} is derived here - callers never supply it. */
  public VerificationState resolveTarget(VerificationEvent event, VerificationState from) {
    return find(event, from)
        .map(StateTransition::to)
        .orElseThrow(() -> new IllegalTransitionException(event, from));
  }

  public boolean isAllowed(VerificationEvent event, VerificationState from) {
    return byKey.containsKey(new StateTransition.Key(event, from));
  }

  /** Every event that could legally be applied right now. Useful in API responses and for the LLM. */
  public Set<VerificationEvent> allowedEventsFrom(VerificationState from) {
    return byKey.keySet().stream()
        .filter(k -> k.from() == from)
        .map(StateTransition.Key::event)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  public List<StateTransition> allEdges() {
    return List.copyOf(byKey.values());
  }
}
