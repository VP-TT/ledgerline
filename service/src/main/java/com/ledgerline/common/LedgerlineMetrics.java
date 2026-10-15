package com.ledgerline.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges that need a query. They are refreshed on a schedule rather than on every scrape, so a busy
 * Prometheus can't add load to the database.
 */
@Component
public class LedgerlineMetrics {
  private final JdbcClient jdbc;
  private final MeterRegistry registry;
  private final AtomicLong outboxBacklog = new AtomicLong();
  private final AtomicLong outboxOldestSeconds = new AtomicLong();
  private final AtomicLong outboxDead = new AtomicLong();
  private final Map<String, AtomicLong> paymentsByStatus = new ConcurrentHashMap<>();

  public LedgerlineMetrics(JdbcClient jdbc, MeterRegistry registry) {
    this.jdbc = jdbc;
    this.registry = registry;
    Gauge.builder("ledgerline.outbox.backlog", outboxBacklog, AtomicLong::get).register(registry);
    Gauge.builder("ledgerline.outbox.oldest.age.seconds", outboxOldestSeconds, AtomicLong::get).register(registry);
    Gauge.builder("ledgerline.outbox.dead", outboxDead, AtomicLong::get).register(registry);
  }

  public void refresh() {
    jdbc.sql("""
        SELECT COUNT(*) FILTER (WHERE status IN ('NEW','PROCESSING')) AS backlog,
               COUNT(*) FILTER (WHERE status = 'DEAD') AS dead,
               COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at) FILTER (WHERE status IN ('NEW','PROCESSING'))), 0)::bigint AS oldest
        FROM outbox_events""")
        .query((rs, i) -> {
          outboxBacklog.set(rs.getLong("backlog"));
          outboxDead.set(rs.getLong("dead"));
          outboxOldestSeconds.set(rs.getLong("oldest"));
          return null;
        }).list();
    jdbc.sql("SELECT status, COUNT(*) AS n FROM payments GROUP BY status")
        .query((rs, i) -> {
          paymentsByStatus.computeIfAbsent(rs.getString("status"), s -> {
            AtomicLong v = new AtomicLong();
            Gauge.builder("ledgerline.payments", v, AtomicLong::get).tag("status", s).register(registry);
            return v;
          }).set(rs.getLong("n"));
          return null;
        }).list();
  }
}
