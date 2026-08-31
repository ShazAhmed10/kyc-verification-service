package dev.portfolio.verifyflow.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Hand-advanced clock for tests.
 *
 * <p>Exists because time is load-bearing in this service - expiry, stuck-detection and the cohort
 * comparison all read the clock - and asserting on those with real time would mean sleeping, which
 * makes tests slow and flaky. Every production class takes a {@link Clock} for exactly this reason.
 */
public final class MutableClock extends Clock {

  private Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration d) {
    this.now = now.plus(d);
  }

  public void set(Instant t) {
    this.now = t;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
