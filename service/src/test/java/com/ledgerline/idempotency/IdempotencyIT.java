package com.ledgerline.idempotency;

import com.ledgerline.support.Api;
import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends IntegrationTest {
  @Autowired DataSource dataSource;

  @Test
  void sameKeySameBodyReplaysTheOriginalResponse() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    var first = api.pay(wallet, "25.00", "pay-1");
    var second = api.pay(wallet, "25.00", "pay-1");

    assertThat(first.status()).isEqualTo(202);
    assertThat(first.replayed()).isFalse();
    assertThat(second.status()).isEqualTo(202);
    assertThat(second.replayed()).isTrue();
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(jdbc.sql("SELECT COUNT(*) FROM payments").query(Long.class).single()).isEqualTo(1);
    assertThat(api.balance(wallet)).isEqualByComparingTo("75.00");
  }

  @Test
  void sameKeyDifferentBodyIs422() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    api.pay(wallet, "25.00", "pay-2");
    var r = api.pay(wallet, "26.00", "pay-2");
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.problemType()).isEqualTo("idempotency-key-reused");
  }

  @Test
  void sameKeyOnADifferentEndpointIs422() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    api.pay(wallet, "25.00", "shared");
    var r = api.post("/v1/wallets/" + wallet + "/topups", "shared", java.util.Map.of("amount", 1));
    assertThat(r.status()).isEqualTo(422);
  }

  @Test
  void sameKeyWhileTheFirstIsStillRunningIs409WithRetryAfter() throws Exception {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    // hold the key row in an open transaction, as a slow in-flight request would
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (var st = c.prepareStatement("""
          INSERT INTO idempotency_keys (client_id, key, request_hash, status) VALUES (?, 'busy', 'x', 'IN_PROGRESS')""")) {
        st.setString(1, Api.CLIENT);
        st.executeUpdate();
      }
      long start = System.nanoTime();
      var r = api.pay(wallet, "25.00", "busy");
      assertThat(r.status()).isEqualTo(409);
      assertThat(r.problemType()).isEqualTo("idempotency-in-progress");
      assertThat(r.retryAfter()).isEqualTo("1");
      assertThat((System.nanoTime() - start) / 1_000_000).as("waited for the lock timeout").isGreaterThanOrEqualTo(1900);
      c.rollback();
    }
    // the holder rolled back, so the key is free again
    assertThat(api.pay(wallet, "25.00", "busy").status()).isEqualTo(202);
  }

  @Test
  void businessFailuresAreNotCachedSoARetryCanSucceedLater() {
    UUID wallet = api.walletWithBalance("INR", "10.00");
    assertThat(api.pay(wallet, "50.00", "retry-me").status()).isEqualTo(422);
    api.topUp(wallet, "100.00");
    var r = api.pay(wallet, "50.00", "retry-me");
    assertThat(r.status()).isEqualTo(202);
    assertThat(r.replayed()).isFalse();
  }

  @Test
  void missingKeyIs400() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    assertThat(api.post("/v1/payments", null, Api.payment(wallet, Api.ELECTRICITY_INR, "1.00", "INR")).status())
        .isEqualTo(400);
  }
}
