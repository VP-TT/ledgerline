package com.ledgerline.reconciliation;

import com.ledgerline.fakegateway.FakeGateway;
import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIT extends IntegrationTest {
  final LocalDate today = LocalDate.now(ZoneOffset.UTC);

  @Test
  void cleanDayHasNoMismatches() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    api.pay(wallet, "10.00", "a");
    api.pay(wallet, "20.00", "b");
    settle();

    var run = api.post("/v1/admin/reconciliation/runs?date=" + today, null, java.util.Map.of());
    assertThat(run.status()).isEqualTo(200);
    assertThat(run.body().path("paymentsChecked").asInt()).isEqualTo(2);
    assertThat(run.body().path("gatewayRecords").asInt()).isEqualTo(2);
    assertThat(run.body().path("mismatches").asInt()).isZero();
    assertThat(run.text("status")).isEqualTo("CLEAN");
  }

  @Test
  void findsAnOrphanChargeAndADoubleChargeRisk() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    UUID declined = api.pay(wallet, "10.00", "a").uuid("id");
    gateway.configure(FakeGateway.Faults.always(FakeGateway.Mode.DECLINE, 0));
    settle();
    // the gateway later flips the declined charge to approved, and also has a charge we never made
    var c = gateway.charge(declined.toString());
    gateway.injectCharge(new FakeGateway.Charge(c.chargeId(), c.paymentId(), "APPROVED", c.amount(), c.currency(),
        c.billerId(), c.accountRef(), null, c.chargedAt()));
    UUID orphan = UUID.randomUUID();
    gateway.injectCharge(new FakeGateway.Charge("ch_orphan", orphan.toString(), "APPROVED", 999, "INR",
        null, "X", null, Instant.now()));

    var run = api.post("/v1/admin/reconciliation/runs?date=" + today, null, java.util.Map.of());
    assertThat(run.text("status")).isEqualTo("ATTENTION");
    assertThat(run.body().path("high").asInt()).isEqualTo(2);

    var report = api.get("/v1/admin/reconciliation/runs/" + today).body().path("mismatches");
    assertThat(report).hasSize(2);
    assertThat(report.findValuesAsText("type")).containsExactlyInAnyOrder("STATUS_MISMATCH", "MISSING_IN_LEDGER");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM gateway_settlements").query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void runningTwiceForTheSameDayIsANoOp() {
    var first = api.post("/v1/admin/reconciliation/runs?date=" + today, null, java.util.Map.of());
    var second = api.post("/v1/admin/reconciliation/runs?date=" + today, null, java.util.Map.of());
    assertThat(second.text("runId")).isEqualTo(first.text("runId"));
    assertThat(second.body().path("alreadyRan").asBoolean()).isTrue();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM reconciliation_runs").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void integrityCheckCatchesBalanceDrift() {
    var wallet = api.walletWithBalance("INR", "100.00");
    jdbc.sql("UPDATE accounts SET balance = balance - 1 WHERE code = :c").param("c", "user_wallet:" + wallet)
        .update();  // simulate a bug
    var run = api.post("/v1/admin/reconciliation/runs?date=" + today, null, java.util.Map.of());
    var report = api.get("/v1/admin/reconciliation/runs/" + today).body().path("mismatches");
    assertThat(run.text("status")).isEqualTo("ATTENTION");
    assertThat(report.findValuesAsText("type")).containsExactly("BALANCE_DRIFT");
  }
}
