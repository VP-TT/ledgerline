package com.ledgerline.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {
  private final JdbcClient jdbc;

  public IdempotencyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public record Row(String requestHash, String status, Integer responseCode, String responseBody) {}

  /**
   * Inserts the key as IN_PROGRESS. If an uncommitted transaction holds the same key, this blocks on
   * the primary key for at most {@code lockTimeout} (then Postgres raises lock_not_available).
   */
  public boolean tryInsert(String clientId, String key, String hash, Duration lockTimeout) {
    jdbc.sql("SELECT set_config('lock_timeout', :t, true)").param("t", lockTimeout.toMillis() + "ms")
        .query(String.class).single();
    int n = jdbc.sql("""
        INSERT INTO idempotency_keys (client_id, key, request_hash, status)
        VALUES (:c, :k, :h, 'IN_PROGRESS') ON CONFLICT DO NOTHING""")
        .param("c", clientId).param("k", key).param("h", hash).update();
    // only the key insert should give up early; the work that follows may wait on hot rows
    jdbc.sql("SELECT set_config('lock_timeout', '0', true)").query(String.class).single();
    return n == 1;
  }

  public Optional<Row> find(String clientId, String key) {
    return jdbc.sql("""
        SELECT request_hash, status, response_code, response_body
        FROM idempotency_keys WHERE client_id = :c AND key = :k""")
        .param("c", clientId).param("k", key).query(Row.class).optional();
  }

  public void complete(String clientId, String key, int code, String body, UUID resourceId) {
    jdbc.sql("""
        UPDATE idempotency_keys SET status = 'COMPLETED', response_code = :code,
               response_body = :body, resource_id = :rid
        WHERE client_id = :c AND key = :k""")
        .param("code", code).param("body", body).param("rid", resourceId)
        .param("c", clientId).param("k", key).update();
  }
}
