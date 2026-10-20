package com.ledgerline.common;

import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionIT extends IntegrationTest {
  @Autowired RetentionJob retention;

  long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

  @Test
  void prunesOnlyExpiredBookkeepingAndNeverTheLedger() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    api.pay(wallet, "10.00", "old-key");
    api.pay(wallet, "10.00", "fresh-key");
    settle();
    long ledgerRows = count("SELECT COUNT(*) FROM postings");

    // age some rows past their windows
    jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '25 hours' WHERE key <> 'fresh-key'").update();
    jdbc.sql("UPDATE outbox_events SET processed_at = now() - interval '8 days'").update();
    jdbc.sql("""
        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, created_at, processed_at)
        VALUES (gen_random_uuid(), 'PAYMENT', gen_random_uuid(), 'X', '{}', 'DEAD', now() - interval '90 days',
                now() - interval '90 days')""").update();
    jdbc.sql("""
        INSERT INTO gateway_webhook_events (event_id, type, payment_id, outcome, payload, received_at) VALUES
          ('evt_old', 'charge.approved', 'p', 'APPLIED_SUCCEEDED', '{}', now() - interval '31 days'),
          ('evt_new', 'charge.approved', 'p', 'APPLIED_SUCCEEDED', '{}', now() - interval '29 days')""").update();

    var deleted = retention.run();

    assertThat(deleted).containsEntry("idempotency_keys", 3)          // 2 wallet/top-up keys + old-key
        .containsEntry("outbox_events", 2)                               // both DONE submit events
        .containsEntry("gateway_webhook_events", 1);
    assertThat(jdbc.sql("SELECT key FROM idempotency_keys").query(String.class).list()).containsExactly("fresh-key");
    assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'DEAD'")).as("DEAD kept").isEqualTo(1);
    assertThat(jdbc.sql("SELECT event_id FROM gateway_webhook_events").query(String.class).list()).containsExactly("evt_new");
    assertThat(count("SELECT COUNT(*) FROM postings")).as("ledger untouched").isEqualTo(ledgerRows);
    assertThat(retention.run().values()).as("second run finds nothing").containsOnly(0);
  }

  @Test
  void deletesInBatchesUntilDone() {
    jdbc.sql("""
        INSERT INTO idempotency_keys (client_id, key, request_hash, status, created_at)
        SELECT 'bulk', 'k' || g, 'h', 'COMPLETED', now() - interval '2 days' FROM generate_series(1, :n) g""")
        .param("n", RetentionJob.BATCH * 2 + 17).update();
    assertThat(retention.run()).containsEntry("idempotency_keys", RetentionJob.BATCH * 2 + 17);
    assertThat(count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
  }
}
