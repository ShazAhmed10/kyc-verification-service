package dev.portfolio.verifyflow.readmodel;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the read model, repairing it on the way past if it is stale.
 *
 * <p>The idea is that a projection does not need a background job to be correct - it needs a read path
 * that is willing to fix what it finds. A read either hits a fresh summary and is cheap, or hits a
 * stale one, rebuilds it, and pays once. Nothing is ever served stale, and a projection that nobody
 * reads is never rebuilt, which is the right amount of work to do on it.
 *
 * <p>What this buys over a scheduled refresher: no fixed lag, no wasted rebuilds of rows nobody wants,
 * and no separate failure mode where the refresher dies quietly and everything silently goes stale.
 * What it costs: the first reader after an invalidation pays the rebuild, so latency is uneven. That is
 * a deliberate trade - see the README.
 *
 * <p>The per-id guard matters. Without it, N concurrent readers of the same stale row all decide to
 * rebuild and all do the same work; with it, one rebuilds and the rest wait on that rebuild. It is an
 * in-process lock, so it collapses stampedes within an instance but not across them - a distributed
 * lock would be needed for that, and for this project's shape it is not worth the dependency.
 */
@Service
public class ReadRepairService {

  private static final Logger log = LoggerFactory.getLogger(ReadRepairService.class);

  private final VerificationRequestRepository requests;
  private final VerificationSummaryRepository summaries;
  private final SummaryProjector projector;
  private final VerifyFlowProperties.ReadModel cfg;
  private final Clock clock;

  /** One lock per in-flight rebuild. Entries are removed as soon as the rebuild finishes. */
  private final ConcurrentHashMap<UUID, Object> rebuildLocks = new ConcurrentHashMap<>();

  // Cheap counters so the README's claims about hit/repair behaviour are observable.
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong repairs = new AtomicLong();

  public ReadRepairService(
      VerificationRequestRepository requests,
      VerificationSummaryRepository summaries,
      SummaryProjector projector,
      VerifyFlowProperties props,
      Clock clock) {
    this.requests = requests;
    this.summaries = summaries;
    this.projector = projector;
    this.cfg = props.readmodel();
    this.clock = clock;
  }

  /**
   * Returns a summary that is fresh as of now, rebuilding it if necessary.
   *
   * @throws NoSuchElementException if there is no such verification at all
   */
  public VerificationSummary get(UUID verificationId) {
    VerificationSummary existing = summaries.findById(verificationId).orElse(null);
    if (existing != null && !existing.isStaleAt(clock.instant(), cfg.maxAge())) {
      hits.incrementAndGet();
      return existing;
    }

    // Serialise rebuilds of this one id. computeIfAbsent gives every caller the same lock object.
    Object lock = rebuildLocks.computeIfAbsent(verificationId, k -> new Object());
    try {
      synchronized (lock) {
        // Re-check inside the lock: whoever held it before us may already have rebuilt this.
        VerificationSummary current = summaries.findById(verificationId).orElse(null);
        if (current != null && !current.isStaleAt(clock.instant(), cfg.maxAge())) {
          hits.incrementAndGet();
          return current;
        }
        repairs.incrementAndGet();
        return rebuild(verificationId);
      }
    } finally {
      rebuildLocks.remove(verificationId, lock);
    }
  }

  @Transactional
  protected VerificationSummary rebuild(UUID verificationId) {
    VerificationRequest request =
        requests
            .findById(verificationId)
            .orElseThrow(() -> new NoSuchElementException("No verification " + verificationId));
    VerificationSummary fresh = projector.project(request);
    VerificationSummary saved = summaries.save(fresh);
    log.debug("Repaired summary for {} (state={})", verificationId, saved.getState());
    return saved;
  }

  public long hitCount() {
    return hits.get();
  }

  public long repairCount() {
    return repairs.get();
  }
}
