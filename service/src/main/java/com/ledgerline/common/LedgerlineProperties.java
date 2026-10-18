package com.ledgerline.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("ledgerline")
public record LedgerlineProperties(Gateway gateway, Outbox outbox, Payments payments, Rewards rewards,
                                   Idempotency idempotency, Scheduling scheduling) {

  public record Gateway(String baseUrl, Duration connectTimeout, Duration readTimeout) {}

  /** Relay settings. {@code concurrency} caps events processed at once (each holds a DB connection at times). Failed handlers are retried with exponential backoff from retryBase up to retryMax. */
  public record Outbox(Duration pollInterval, int batchSize, int concurrency, int maxAttempts, Duration stuckAfter,
                       Duration retryBase, Duration retryMax) {}

  /** Status checks for UNKNOWN payments: statusCheckBase × 2^n plus jitter, at most maxStatusChecks times. */
  public record Payments(int maxStatusChecks, Duration statusCheckBase, Duration statusCheckMax) {}

  /** Redemption rate per wallet currency, in minor units per point (INR 25 = Rs 0.25 per point). */
  public record Rewards(Map<String, Long> minorPerPoint) {}

  /** How long a request waits for a concurrent duplicate before answering 409. */
  public record Idempotency(Duration lockTimeout) {}

  public record Scheduling(boolean enabled) {}
}
