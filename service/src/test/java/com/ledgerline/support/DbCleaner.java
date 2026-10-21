package com.ledgerline.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties every table except the seeded system accounts, billers and reward rules (whose balances are zeroed). */
public final class DbCleaner {
  private DbCleaner() {}

  public static void clean(JdbcClient jdbc) {
    jdbc.sql("""
        TRUNCATE postings, journal_entries, outbox_events, idempotency_keys, payments, topups, redemptions, gateway_webhook_events, refunds,
                 reconciliation_mismatches, reconciliation_runs, gateway_settlements, shedlock, wallets""").update();
    jdbc.sql("DELETE FROM accounts WHERE NOT system").update();
    jdbc.sql("UPDATE accounts SET balance = 0, version = 0").update();
  }
}
