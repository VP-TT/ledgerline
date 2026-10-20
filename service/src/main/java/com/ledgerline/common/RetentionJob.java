package com.ledgerline.common;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deletes bookkeeping rows once they can no longer matter, in small batches so no statement holds
 * locks or builds a large transaction:
 * <ul>
 *   <li>idempotency keys after {@code idempotencyKeys} (24 h): a later retry with the same key is a new request;</li>
 *   <li>DONE outbox events after {@code outboxDone} (7 d); DEAD events are kept until someone handles them;</li>
 *   <li>webhook event ids after {@code webhookEvents} (30 d), which must exceed the gateway's redelivery window.</li>
 * </ul>
 * The ledger itself is never pruned: it is the record.
 */
@Component
public class RetentionJob {
  private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
  static final int BATCH = 5_000;

  private final JdbcClient jdbc;
  private final MeterRegistry metrics;
  private final LedgerlineProperties.Retention config;

  public RetentionJob(JdbcClient jdbc, MeterRegistry metrics, LedgerlineProperties props) {
    this.jdbc = jdbc;
    this.metrics = metrics;
    this.config = props.retention();
  }

  @Scheduled(cron = "0 15 * * * *", zone = "UTC")
  @SchedulerLock(name = "retention", lockAtMostFor = "PT30M")
  public void hourly() {
    run();
  }

  /** Returns rows deleted per table. */
  public Map<String, Integer> run() {
    Map<String, Integer> deleted = new LinkedHashMap<>();
    deleted.put("idempotency_keys", purge("idempotency_keys", "created_at < now() - make_interval(secs => :age)",
        config.idempotencyKeys()));
    deleted.put("outbox_events", purge("outbox_events",
        "status = 'DONE' AND processed_at < now() - make_interval(secs => :age)", config.outboxDone()));
    deleted.put("gateway_webhook_events", purge("gateway_webhook_events",
        "received_at < now() - make_interval(secs => :age)", config.webhookEvents()));
    deleted.forEach((table, n) -> {
      if (n > 0) metrics.counter("ledgerline.retention.deleted", "table", table).increment(n);
    });
    if (deleted.values().stream().anyMatch(n -> n > 0)) log.info("retention deleted {}", deleted);
    return deleted;
  }

  private int purge(String table, String where, Duration age) {
    int total = 0, n;
    do {
      n = jdbc.sql("DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table + " WHERE " + where
              + " LIMIT " + BATCH + ")")
          .param("age", (double) age.toSeconds()).update();
      total += n;
    } while (n == BATCH);
    return total;
  }
}
