package com.ledgerline.payment;

import com.ledgerline.fakegateway.FakeGateway.Faults;
import com.ledgerline.fakegateway.FakeGateway.Mode;
import com.ledgerline.support.Api;
import com.ledgerline.support.IntegrationTest;
import com.ledgerline.support.TestEnv;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end through HTTP, the outbox relay and the fake gateway, one failure mode per test. */
class PaymentFlowIT extends IntegrationTest {

  UUID payAndSettle(UUID wallet, String amount) {
    var r = api.pay(wallet, amount, "k-" + UUID.randomUUID());
    assertThat(r.status()).isEqualTo(202);
    assertThat(r.text("status")).isEqualTo("PENDING");
    settle();
    return r.uuid("id");
  }

  void gatewayMode(Mode mode) {
    gateway.configure(Faults.always(mode, TestEnv.GATEWAY_DELAY_MS));
  }

  @Test
  void approvedPaymentSettlesToTheBillerAndEarnsPoints() {
    UUID wallet = api.walletWithBalance("INR", "1000.00");
    UUID p = payAndSettle(wallet, "500.00");

    assertThat(api.paymentStatus(p)).isEqualTo("SUCCEEDED");
    assertThat(api.get("/v1/payments/" + p).text("gatewayRef")).startsWith("ch_");
    assertThat(api.balance(wallet)).isEqualByComparingTo("500.00");
    assertThat(api.points(wallet)).isEqualTo(10);              // electricity: 2 pts per Rs 100
    assertThat(ledger.entries("PAYMENT_SETTLEMENT", p)).isEqualTo(1);
    assertThat(ledger.entries("REWARD_ACCRUAL", p)).isEqualTo(1);
    assertThat(gateway.stats().charges()).isEqualTo(1);
    assertLedgerInvariants();
  }

  @Test
  void declinedPaymentIsReversedWithoutRetrying() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gatewayMode(Mode.DECLINE);
    UUID p = payAndSettle(wallet, "60.00");

    var body = api.get("/v1/payments/" + p);
    assertThat(body.text("status")).isEqualTo("REVERSED");
    assertThat(body.text("failureReason")).contains("card_declined");
    assertThat(api.balance(wallet)).isEqualByComparingTo("100.00");
    assertThat(api.points(wallet)).isZero();
    assertThat(ledger.entries("PAYMENT_REVERSAL", p)).isEqualTo(1);
    assertThat(gateway.stats().submitCalls()).isEqualTo(1);
    assertLedgerInvariants();
  }

  @Test
  void gatewayChargedButClientTimedOut_statusCheckFindsTheChargeAndSettlesOnce() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gatewayMode(Mode.TIMEOUT_THEN_SUCCEED);
    UUID p = payAndSettle(wallet, "30.00");

    assertThat(api.paymentStatus(p)).isEqualTo("SUCCEEDED");
    assertThat(gateway.stats().charges()).isEqualTo(1);
    assertThat(gateway.stats().submitCalls()).as("status check, not a second submit").isEqualTo(1);
    assertThat(ledger.entries("PAYMENT_SETTLEMENT", p)).isEqualTo(1);
    assertThat(api.balance(wallet)).isEqualByComparingTo("70.00");
    assertLedgerInvariants();
  }

  @Test
  void requestLostBeforeTheGatewaySawIt_isResubmittedWithTheSameKey() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gatewayMode(Mode.TIMEOUT_NO_CHARGE);
    var r = api.pay(wallet, "30.00", "lost");
    relay.drain();                                  // submit → times out → UNKNOWN
    assertThat(api.paymentStatus(r.uuid("id"))).isEqualTo("UNKNOWN");

    gateway.configure(Faults.none());              // gateway recovers
    settle();                                       // status check: 404 → resubmit → approved

    assertThat(api.paymentStatus(r.uuid("id"))).isEqualTo("SUCCEEDED");
    assertThat(gateway.stats().charges()).isEqualTo(1);
    assertThat(gateway.stats().submitCalls()).isEqualTo(2);
    assertLedgerInvariants();
  }

  @Test
  void gateway5xxIsTreatedAsUnknownNotAsFailure() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gatewayMode(Mode.ERROR);
    var r = api.pay(wallet, "30.00", "five-oh-three");
    relay.drain();
    assertThat(api.paymentStatus(r.uuid("id"))).isEqualTo("UNKNOWN");
    assertThat(api.balance(wallet)).as("money stays reserved, never auto-reversed").isEqualByComparingTo("70.00");

    gateway.configure(Faults.none());
    settle();
    assertThat(api.paymentStatus(r.uuid("id"))).isEqualTo("SUCCEEDED");
    assertLedgerInvariants();
  }

  @Test
  void stillUnknownAfterLastStatusCheckWaitsForReconciliation() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gateway.configure(Faults.always(Mode.TIMEOUT_THEN_SUCCEED, TestEnv.GATEWAY_DELAY_MS).withStatusErrors(true));
    var r = api.pay(wallet, "30.00", "stuck");
    settle();

    var p = jdbc.sql("SELECT status, status_checks FROM payments WHERE id = :id").param("id", r.uuid("id"))
        .query((rs, i) -> List.of(rs.getString(1), rs.getInt(2))).single();
    assertThat(p).containsExactly("UNKNOWN", 5);
    assertThat(api.balance(wallet)).as("not reversed: it may have been charged").isEqualByComparingTo("70.00");
    assertThat(gateway.stats().charges()).isEqualTo(1);
    assertLedgerInvariants();
  }

  @Test
  void relayCrashAfterGatewayCall_redeliveryChargesOnceAndSettlesOnce() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    AtomicBoolean crashed = new AtomicBoolean();
    faults.onAfterGatewayCall(id -> {
      if (crashed.compareAndSet(false, true)) throw new IllegalStateException("simulated crash before markDone");
    });
    UUID p = payAndSettle(wallet, "40.00");

    assertThat(crashed).isTrue();
    assertThat(api.paymentStatus(p)).isEqualTo("SUCCEEDED");
    assertThat(gateway.stats().submitCalls()).as("redelivered").isEqualTo(2);
    assertThat(gateway.stats().charges()).as("gateway deduped on our payment id").isEqualTo(1);
    assertThat(ledger.entries("PAYMENT_SETTLEMENT", p)).isEqualTo(1);
    assertLedgerInvariants();
  }

  @Test
  void workerDiesMidEvent_sweeperReturnsItAndItSettlesOnce() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    var r = api.pay(wallet, "40.00", "orphaned");
    // a worker claimed the event and then vanished (no markDone, no reschedule)
    jdbc.sql("UPDATE outbox_events SET status = 'PROCESSING', attempts = 1, claimed_at = now() - interval '10 minutes'")
        .update();
    assertThat(relay.drain()).isZero();
    assertThat(relay.sweepStuck()).isEqualTo(1);
    settle();

    assertThat(api.paymentStatus(r.uuid("id"))).isEqualTo("SUCCEEDED");
    assertThat(gateway.stats().charges()).isEqualTo(1);
  }

  @Test
  void poisonEventGoesDeadAfterMaxAttempts() {
    jdbc.sql("""
        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
        VALUES (gen_random_uuid(), 'PAYMENT', gen_random_uuid(), 'PAYMENT_SUBMIT_REQUESTED', '{}')""").update();
    settle();   // the payment doesn't exist: handler throws every time
    assertThat(jdbc.sql("SELECT status FROM outbox_events").query(String.class).single()).isEqualTo("DEAD");
    assertThat(jdbc.sql("SELECT attempts FROM outbox_events").query(Integer.class).single()).isEqualTo(8);
  }

  @Test
  void currencyMismatchIsRejected() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    var r = api.post("/v1/payments", "usd", Api.payment(wallet, Api.ELECTRICITY_USD, "1.00", "USD"));
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.problemType()).isEqualTo("currency-mismatch");
  }
}
