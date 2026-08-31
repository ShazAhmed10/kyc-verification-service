package dev.portfolio.verifyflow.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import dev.portfolio.verifyflow.domain.StateChangeRecordRepository;
import dev.portfolio.verifyflow.domain.VerificationRequest;
import dev.portfolio.verifyflow.domain.VerificationRequestRepository;
import dev.portfolio.verifyflow.domain.VerificationState;
import dev.portfolio.verifyflow.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReadRepairServiceTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
  private static final Duration MAX_AGE = Duration.ofSeconds(30);

  private VerificationRequestRepository requests;
  private VerificationSummaryRepository summaries;
  private MutableClock clock;
  private ReadRepairService service;
  private VerificationRequest request;

  @BeforeEach
  void setUp() {
    requests = mock(VerificationRequestRepository.class);
    summaries = mock(VerificationSummaryRepository.class);
    StateChangeRecordRepository history = mock(StateChangeRecordRepository.class);
    when(history.findByVerificationIdOrderByOccurredAtAscIdAsc(any())).thenReturn(List.of());
    clock = new MutableClock(T0);

    request = VerificationRequest.start("s1", VerificationState.PENDING, T0);
    when(requests.findById(request.getId())).thenReturn(Optional.of(request));
    when(summaries.save(any(VerificationSummary.class))).thenAnswer(i -> i.getArgument(0));

    VerifyFlowProperties props =
        new VerifyFlowProperties(
            null, null, null, null, new VerifyFlowProperties.ReadModel(MAX_AGE), null, null);
    service = new ReadRepairService(requests, summaries, new SummaryProjector(history, clock), props, clock);
  }

  private VerificationSummary summaryRefreshedAt(Instant when) {
    return new VerificationSummary(
        request.getId(), "s1", VerificationState.PENDING, 1, T0, T0, false, when);
  }

  @Test
  @DisplayName("a fresh summary is served without touching the write side")
  void freshSummaryIsServed() {
    when(summaries.findById(request.getId())).thenReturn(Optional.of(summaryRefreshedAt(T0)));
    clock.advance(Duration.ofSeconds(5)); // still inside the 30s window

    VerificationSummary result = service.get(request.getId());

    assertThat(result.getRefreshedAt()).isEqualTo(T0);
    assertThat(service.hitCount()).isEqualTo(1);
    assertThat(service.repairCount()).isZero();
    verify(requests, never()).findById(any()); // no rebuild, so the aggregate was never loaded
    verify(summaries, never()).save(any());
  }

  @Test
  @DisplayName("a stale summary is rebuilt on read and served fresh - nothing is served stale")
  void staleSummaryIsRepaired() {
    when(summaries.findById(request.getId())).thenReturn(Optional.of(summaryRefreshedAt(T0)));
    clock.advance(Duration.ofMinutes(5)); // well past the window

    VerificationSummary result = service.get(request.getId());

    assertThat(result.getRefreshedAt()).isEqualTo(clock.instant());
    assertThat(service.repairCount()).isEqualTo(1);
    verify(summaries).save(any(VerificationSummary.class));
  }

  @Test
  @DisplayName("a missing summary is built on first read, so no seeding step is needed")
  void missingSummaryIsBuilt() {
    when(summaries.findById(request.getId())).thenReturn(Optional.empty());

    VerificationSummary result = service.get(request.getId());

    assertThat(result.getVerificationId()).isEqualTo(request.getId());
    assertThat(result.getState()).isEqualTo(VerificationState.PENDING);
    assertThat(service.repairCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("an invalidated summary is treated as stale immediately")
  void invalidatedSummaryIsRebuiltAtOnce() {
    VerificationSummary stale = summaryRefreshedAt(T0);
    stale.invalidate(); // what the write path does
    when(summaries.findById(request.getId())).thenReturn(Optional.of(stale));

    service.get(request.getId());

    assertThat(service.repairCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a read for a verification that does not exist fails clearly")
  void unknownVerification() {
    UUID ghost = UUID.randomUUID();
    when(summaries.findById(ghost)).thenReturn(Optional.empty());
    when(requests.findById(ghost)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(ghost)).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  @DisplayName("concurrent readers of the same stale row cause one rebuild, not one each")
  void concurrentReadersDoNotStampede() throws Exception {
    // Return a stale row until something saves, then return the saved one - mimicking the DB.
    VerificationSummary stale = summaryRefreshedAt(Instant.EPOCH);
    final VerificationSummary[] stored = {stale};
    when(summaries.findById(request.getId())).thenAnswer(i -> Optional.of(stored[0]));
    when(summaries.save(any(VerificationSummary.class)))
        .thenAnswer(
            i -> {
              stored[0] = i.getArgument(0);
              return stored[0];
            });

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              service.get(request.getId());
            } catch (Exception ignored) {
              // assertion is on the save count below
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    // The guard collapses the herd: one thread rebuilds, the rest see the fresh row inside the lock.
    verify(summaries, times(1)).save(any(VerificationSummary.class));
    assertThat(service.repairCount()).isEqualTo(1);
    assertThat(service.hitCount()).isEqualTo(threads - 1L);
  }
}
