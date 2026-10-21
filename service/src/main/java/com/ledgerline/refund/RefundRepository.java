package com.ledgerline.refund;

import com.ledgerline.common.ApiException;
import com.ledgerline.payment.TransitionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefundRepository {
  private final JdbcClient jdbc;

  public RefundRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void insert(UUID id, UUID paymentId, long amount, String currency, String key) {
    jdbc.sql("""
        INSERT INTO refunds (id, payment_id, amount, currency, status, idempotency_key)
        VALUES (:id, :p, :a, :c, 'PENDING', :k)""")
        .param("id", id).param("p", paymentId).param("a", amount).param("c", currency).param("k", key).update();
  }

  public Refund get(UUID id) {
    return jdbc.sql("SELECT * FROM refunds WHERE id = :id").param("id", id).query(Refund.class).optional()
        .orElseThrow(() -> new ApiException.NotFound("Refund", id));
  }

  public Optional<Refund> findForUpdate(UUID id) {
    return jdbc.sql("SELECT * FROM refunds WHERE id = :id FOR UPDATE").param("id", id).query(Refund.class).optional();
  }

  public List<Refund> forPayment(UUID paymentId) {
    return jdbc.sql("SELECT * FROM refunds WHERE payment_id = :p ORDER BY created_at").param("p", paymentId)
        .query(Refund.class).list();
  }

  /** Refunds that have happened or still might (anything but FAILED). */
  public long committedAmount(UUID paymentId) {
    return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE payment_id = :p AND status <> 'FAILED'")
        .param("p", paymentId).query(Long.class).single();
  }

  public record Settled(long amount, long points) {}

  /** Amount refunded and points already accounted for (clawed back or recorded as shortfall). */
  public Settled settled(UUID paymentId) {
    return jdbc.sql("""
        SELECT COALESCE(SUM(amount), 0) AS amount, COALESCE(SUM(points_clawed_back + points_shortfall), 0) AS points
        FROM refunds WHERE payment_id = :p AND status = 'SUCCEEDED'""")
        .param("p", paymentId).query(Settled.class).single();
  }

  /** Compare-and-set on (status, version), as for payments. */
  public Refund transition(Refund r, RefundStatus to, String gatewayRef, String failureReason) {
    if (!r.status().canMoveTo(to)) throw TransitionException.illegal(r.id(), r.status(), to);
    int rows = jdbc.sql("""
        UPDATE refunds SET status = :to, version = version + 1, updated_at = now(),
               gateway_ref = COALESCE(CAST(:gr AS text), gateway_ref),
               failure_reason = COALESCE(CAST(:fr AS text), failure_reason),
               next_attempt_at = NULL
        WHERE id = :id AND status = :from AND version = :version""")
        .param("to", to.name()).param("gr", gatewayRef).param("fr", failureReason)
        .param("id", r.id()).param("from", r.status().name()).param("version", r.version()).update();
    if (rows == 0) throw TransitionException.concurrent(r.id(), r.status());
    return get(r.id());
  }

  public void recordClawback(UUID id, long clawed, long shortfall) {
    jdbc.sql("UPDATE refunds SET points_clawed_back = :c, points_shortfall = :s WHERE id = :id")
        .param("c", clawed).param("s", shortfall).param("id", id).update();
  }

  public void incrementAttempts(UUID id) {
    jdbc.sql("UPDATE refunds SET attempts = attempts + 1, updated_at = now() WHERE id = :id").param("id", id).update();
  }

  public int incrementStatusChecks(UUID id) {
    return jdbc.sql("UPDATE refunds SET status_checks = status_checks + 1 WHERE id = :id RETURNING status_checks")
        .param("id", id).query(Integer.class).single();
  }

  public void setNextAttempt(UUID id, OffsetDateTime at) {
    jdbc.sql("UPDATE refunds SET next_attempt_at = CAST(:at AS timestamptz) WHERE id = :id")
        .param("at", at).param("id", id).update();
  }
}
