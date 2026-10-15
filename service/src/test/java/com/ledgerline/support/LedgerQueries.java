package com.ledgerline.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/** The invariants the whole design exists to protect, as SQL. */
public final class LedgerQueries {
  private final JdbcClient jdbc;

  public LedgerQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

  public long unbalancedEntries() {
    return jdbc.sql("""
        SELECT COUNT(*) FROM (SELECT entry_id FROM postings GROUP BY entry_id, currency HAVING SUM(amount) <> 0) x""")
        .query(Long.class).single();
  }

  public List<String> currenciesNotSummingToZero() {
    return jdbc.sql("SELECT currency FROM postings GROUP BY currency HAVING SUM(amount) <> 0").query(String.class).list();
  }

  public List<String> accountsWhereCachedBalanceDiffersFromPostings() {
    return jdbc.sql("""
        SELECT a.code FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
        GROUP BY a.id HAVING a.balance <> COALESCE(SUM(p.amount), 0)""").query(String.class).list();
  }

  /** User wallets and points are liabilities: a positive (debit) balance means the user went below zero. */
  public List<String> userAccountsBelowZero() {
    return jdbc.sql("""
        SELECT code FROM accounts WHERE (code LIKE 'user_wallet:%' OR code LIKE 'rewards_points:%') AND balance > 0""")
        .query(String.class).list();
  }

  /** Money in gateway_clearing must equal the payments still in flight. */
  public List<String> clearingDrift() {
    return jdbc.sql("""
        SELECT a.code FROM accounts a
        LEFT JOIN (SELECT currency, SUM(amount) t FROM payments
                   WHERE status IN ('PENDING','SUBMITTED','UNKNOWN','FAILED') GROUP BY currency) f
          ON f.currency = a.currency
        WHERE a.code LIKE 'gateway_clearing:%' AND -a.balance <> COALESCE(f.t, 0)""").query(String.class).list();
  }

  public long entries(String type, UUID reference) {
    return jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE type = :t AND reference_id = :r")
        .param("t", type).param("r", reference).query(Long.class).single();
  }

  public long entriesPerReferenceAboveOne() {
    return jdbc.sql("""
        SELECT COUNT(*) FROM (SELECT type, reference_id FROM journal_entries GROUP BY type, reference_id HAVING COUNT(*) > 1) x""")
        .query(Long.class).single();
  }
}
