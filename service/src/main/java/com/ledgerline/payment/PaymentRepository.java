package com.ledgerline.payment;

import com.ledgerline.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class PaymentRepository {
  private final JdbcClient jdbc;

  public PaymentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void insert(Payment p) {
    jdbc.sql("""
        INSERT INTO payments (id, wallet_id, biller_id, account_ref, amount, currency, status, idempotency_key)
        VALUES (:id, :w, :b, :ref, :amt, :cur, :status, :key)""")
        .param("id", p.id()).param("w", p.walletId()).param("b", p.billerId()).param("ref", p.accountRef())
        .param("amt", p.amount()).param("cur", p.currency()).param("status", p.status().name())
        .param("key", p.idempotencyKey()).update();
  }

  public Payment get(UUID id) {
    return jdbc.sql("SELECT * FROM payments WHERE id = :id").param("id", id).query(Payment.class).optional()
        .orElseThrow(() -> new ApiException.NotFound("Payment", id));
  }

  /** Locks the payment row, so no other transition can interleave until this transaction ends. */
  public java.util.Optional<Payment> findForUpdate(UUID id) {
    return jdbc.sql("SELECT * FROM payments WHERE id = :id FOR UPDATE").param("id", id).query(Payment.class).optional();
  }

  public List<Payment> findAll(Collection<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.sql("SELECT * FROM payments WHERE id IN (:ids)").param("ids", ids).query(Payment.class).list();
  }

  public List<Payment> findCreatedBetween(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.sql("SELECT * FROM payments WHERE created_at >= :f AND created_at < :t ORDER BY created_at")
        .param("f", from).param("t", to).query(Payment.class).list();
  }

  /**
   * Compare-and-set on (status, version): of two writers racing the same transition, exactly one
   * updates a row and the other gets {@link TransitionException.Concurrent}.
   */
  public Payment transition(Payment p, PaymentStatus to, String gatewayRef, String failureReason) {
    if (!p.status().canMoveTo(to)) throw TransitionException.illegal(p.id(), p.status(), to);
    int rows = jdbc.sql("""
        UPDATE payments SET status = :to, version = version + 1, updated_at = now(),
               gateway_ref = COALESCE(CAST(:gr AS text), gateway_ref),
               failure_reason = COALESCE(CAST(:fr AS text), failure_reason),
               next_attempt_at = NULL
        WHERE id = :id AND status = :from AND version = :version""")
        .param("to", to.name()).param("gr", gatewayRef).param("fr", failureReason)
        .param("id", p.id()).param("from", p.status().name()).param("version", p.version()).update();
    if (rows == 0) throw TransitionException.concurrent(p.id(), p.status());
    return get(p.id());
  }

  public void incrementAttempts(UUID id) {
    jdbc.sql("UPDATE payments SET attempts = attempts + 1, updated_at = now() WHERE id = :id").param("id", id).update();
  }

  public int incrementStatusChecks(UUID id) {
    return jdbc.sql("UPDATE payments SET status_checks = status_checks + 1 WHERE id = :id RETURNING status_checks")
        .param("id", id).query(Integer.class).single();
  }

  public void setNextAttempt(UUID id, OffsetDateTime at) {
    jdbc.sql("UPDATE payments SET next_attempt_at = CAST(:at AS timestamptz) WHERE id = :id")
        .param("at", at).param("id", id).update();
  }
}
