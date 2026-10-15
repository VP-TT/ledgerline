package com.ledgerline.common;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter: base × 2^(attempt-1), capped, plus up to 25% random extra. */
public final class Backoff {
  private Backoff() {}

  public static Duration exponential(Duration base, int attempt, Duration max) {
    long baseMs = Math.max(1, base.toMillis());
    int shift = Math.min(Math.max(attempt - 1, 0), 30);
    long delay = Math.min(max.toMillis(), baseMs << shift);
    long jitter = ThreadLocalRandom.current().nextLong(delay / 4 + 1);
    return Duration.ofMillis(delay + jitter);
  }
}
