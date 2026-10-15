package com.ledgerline.outbox;

import com.ledgerline.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {
  private final JdbcClient jdbc;
  private final Json json;

  public OutboxRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Must share the caller's transaction: the event exists if and only if the business change commits. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID insert(String aggregateType, UUID aggregateId, String eventType, Map<String, ?> payload,
                     OffsetDateTime availableAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, available_at)
        VALUES (:id, :at, :aid, :et, CAST(:p AS jsonb), COALESCE(CAST(:avail AS timestamptz), now()))""")
        .param("id", id).param("at", aggregateType).param("aid", aggregateId).param("et", eventType)
        .param("p", json.write(payload)).param("avail", availableAt).update();
    return id;
  }

  /** Claims up to {@code limit} ready events. SKIP LOCKED lets several relays drain concurrently without overlap. */
  public List<OutboxEvent> claim(int limit) {
    return jdbc.sql("""
        UPDATE outbox_events SET status = 'PROCESSING', attempts = attempts + 1, claimed_at = now()
        WHERE id IN (
          SELECT id FROM outbox_events
          WHERE status = 'NEW' AND available_at <= now()
          ORDER BY available_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED)
        RETURNING id, aggregate_type, aggregate_id, event_type, payload::text AS payload, status, attempts,
                  available_at, last_error, created_at""")
        .param("limit", limit).query(OutboxEvent.class).list();
  }

  public void markDone(UUID id) {
    jdbc.sql("UPDATE outbox_events SET status = 'DONE', processed_at = now() WHERE id = :id AND status = 'PROCESSING'")
        .param("id", id).update();
  }

  public void reschedule(UUID id, Duration delay, String error) {
    jdbc.sql("""
        UPDATE outbox_events SET status = 'NEW', available_at = now() + make_interval(secs => :s), last_error = :e
        WHERE id = :id AND status = 'PROCESSING'""")
        .param("s", delay.toMillis() / 1000.0).param("e", truncate(error)).param("id", id).update();
  }

  public void markDead(UUID id, String error) {
    jdbc.sql("""
        UPDATE outbox_events SET status = 'DEAD', processed_at = now(), last_error = :e
        WHERE id = :id AND status = 'PROCESSING'""")
        .param("e", truncate(error)).param("id", id).update();
  }

  /** Returns events a crashed worker left in PROCESSING to NEW. */
  public int resetStuck(Duration olderThan) {
    return jdbc.sql("""
        UPDATE outbox_events SET status = 'NEW', available_at = now(), last_error = 'reset after stuck in PROCESSING'
        WHERE status = 'PROCESSING' AND claimed_at < now() - make_interval(secs => :s)""")
        .param("s", olderThan.toMillis() / 1000.0).update();
  }

  public record Backlog(long pending, long dead, double oldestAgeSeconds, Double nextDueInSeconds) {}

  public Backlog backlog() {
    return jdbc.sql("""
        SELECT COUNT(*) FILTER (WHERE status IN ('NEW','PROCESSING')) AS pending,
               COUNT(*) FILTER (WHERE status = 'DEAD') AS dead,
               COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at) FILTER (WHERE status IN ('NEW','PROCESSING'))), 0)::float8
                 AS oldest_age_seconds,
               EXTRACT(EPOCH FROM MIN(available_at) FILTER (WHERE status = 'NEW') - now())::float8 AS next_due_in_seconds
        FROM outbox_events""").query(Backlog.class).single();
  }

  private static String truncate(String s) {
    return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
  }
}
