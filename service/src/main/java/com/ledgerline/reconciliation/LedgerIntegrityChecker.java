package com.ledgerline.reconciliation;

import com.ledgerline.payment.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Internal checks that need no gateway: the ledger must agree with itself and with the payments table. */
@Component
public class LedgerIntegrityChecker {
  private final JdbcClient jdbc;

  public LedgerIntegrityChecker(JdbcClient jdbc) { this.jdbc = jdbc; }

  public List<Mismatch> check() {
    List<Mismatch> out = new ArrayList<>();

    jdbc.sql("""
        SELECT entry_id || ' ' || currency || ' sums to ' || SUM(amount)
        FROM postings GROUP BY entry_id, currency HAVING SUM(amount) <> 0""")
        .query(String.class).list()
        .forEach(n -> out.add(Mismatch.integrity(MismatchType.UNBALANCED_ENTRY, n)));

    jdbc.sql("""
        SELECT a.code || ': cached ' || a.balance || ', postings ' || COALESCE(SUM(p.amount), 0)
        FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
        WHERE NOT a.allow_negative                 -- only these keep a cached balance
        GROUP BY a.id HAVING a.balance <> COALESCE(SUM(p.amount), 0)""")
        .query(String.class).list()
        .forEach(n -> out.add(Mismatch.integrity(MismatchType.BALANCE_DRIFT, n)));

    // clearing is a liability (credit-normal), so money in flight shows as a negative sum of postings
    jdbc.sql("""
        SELECT a.code || ': holds ' || COALESCE(-c.total, 0) || ', in-flight payments ' || COALESCE(f.total, 0)
        FROM accounts a
        LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM postings GROUP BY account_id) c ON c.account_id = a.id
        LEFT JOIN (SELECT currency, SUM(amount) AS total FROM payments
                   WHERE status IN (:inflight) GROUP BY currency) f ON f.currency = a.currency
        WHERE a.code LIKE 'gateway_clearing:%' AND COALESCE(-c.total, 0) <> COALESCE(f.total, 0)""")
        .param("inflight", PaymentStatus.IN_FLIGHT.stream().map(Enum::name).toList())
        .query(String.class).list()
        .forEach(n -> out.add(Mismatch.integrity(MismatchType.CLEARING_DRIFT, n)));

    jdbc.sql("""
        SELECT id || ' ' || event_type || ' for ' || aggregate_id || ': ' || COALESCE(last_error, '')
        FROM outbox_events WHERE status = 'DEAD'""")
        .query(String.class).list()
        .forEach(n -> out.add(Mismatch.integrity(MismatchType.DEAD_OUTBOX_EVENT, n)));

    return out;
  }
}
