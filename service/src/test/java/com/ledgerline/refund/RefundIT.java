package com.ledgerline.refund;

import com.ledgerline.fakegateway.FakeGateway.Faults;
import com.ledgerline.fakegateway.FakeGateway.Mode;
import com.ledgerline.support.Api;
import com.ledgerline.support.IntegrationTest;
import com.ledgerline.support.TestEnv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RefundIT extends IntegrationTest {

  /** A wallet that paid Rs 1,000 for electricity (2 pts / Rs 100 → 20 points). */
  record Paid(UUID wallet, UUID payment) {}

  Paid paid() {
    UUID wallet = api.walletWithBalance("INR", "1000.00");
    UUID p = api.pay(wallet, "1000.00", "pay-" + UUID.randomUUID()).uuid("id");
    settle();
    assertThat(api.paymentStatus(p)).isEqualTo("SUCCEEDED");
    assertThat(api.points(wallet)).isEqualTo(20);
    return new Paid(wallet, p);
  }

  Api.Response refund(UUID payment, String amount, String key) {
    return api.post("/v1/payments/" + payment + "/refunds", key, Map.of("amount", new BigDecimal(amount)));
  }

  String refundStatus(UUID refund) { return api.get("/v1/refunds/" + refund).text("status"); }

  @Test
  void fullRefundReturnsTheMoneyAndTakesBackThePoints() {
    Paid p = paid();
    var r = refund(p.payment(), "1000.00", "rf-1");
    assertThat(r.status()).isEqualTo(202);
    assertThat(r.text("status")).isEqualTo("PENDING");
    settle();

    var body = api.get("/v1/refunds/" + r.uuid("id")).body();
    assertThat(body.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(body.path("pointsClawedBack").asLong()).isEqualTo(20);
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("1000.00");
    assertThat(api.points(p.wallet())).isZero();
    assertThat(ledger.entries("REFUND", r.uuid("id"))).isEqualTo(1);
    assertThat(gateway.stats().refunds()).isEqualTo(1);
    assertLedgerInvariants();
  }

  @Test
  void partialRefundsAreProportionalAndCannotExceedThePayment() {
    Paid p = paid();
    refund(p.payment(), "250.00", "rf-a");
    settle();
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("250.00");
    assertThat(api.points(p.wallet())).as("keeps 75% of 20").isEqualTo(15);

    var tooMuch = refund(p.payment(), "750.01", "rf-b");
    assertThat(tooMuch.status()).isEqualTo(422);
    assertThat(tooMuch.problemType()).isEqualTo("refund-exceeds-payment");

    refund(p.payment(), "750.00", "rf-c");
    settle();
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("1000.00");
    assertThat(api.points(p.wallet())).isZero();
    assertThat(api.get("/v1/payments/" + p.payment() + "/refunds").body()).hasSize(2);
    assertLedgerInvariants();
  }

  @Test
  void spentPointsAreRecordedAsShortfallAndTheBalanceNeverGoesNegative() {
    Paid p = paid();
    api.post("/v1/rewards/redeem", "spend", Map.of("walletId", p.wallet(), "points", 18));   // 2 points left
    var r = refund(p.payment(), "1000.00", "rf-2");
    settle();

    var body = api.get("/v1/refunds/" + r.uuid("id")).body();
    assertThat(body.path("pointsClawedBack").asLong()).isEqualTo(2);
    assertThat(body.path("pointsShortfall").asLong()).isEqualTo(18);
    assertThat(api.points(p.wallet())).isZero();
    assertLedgerInvariants();
  }

  @Test
  void onlySucceededPaymentsCanBeRefunded() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    UUID pending = api.pay(wallet, "10.00", "not-yet").uuid("id");
    var r = refund(pending, "10.00", "rf-3");
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.problemType()).isEqualTo("not-refundable");
  }

  @Test
  void refundRequestsAreIdempotent() {
    Paid p = paid();
    var a = refund(p.payment(), "100.00", "rf-same");
    var b = refund(p.payment(), "100.00", "rf-same");
    assertThat(b.replayed()).isTrue();
    assertThat(b.text("id")).isEqualTo(a.text("id"));
    settle();
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("100.00");
    assertThat(gateway.stats().refunds()).isEqualTo(1);
  }

  @Test
  void refundedButClientTimedOut_statusCheckSettlesItOnce() {
    Paid p = paid();
    gateway.configure(Faults.always(Mode.TIMEOUT_THEN_SUCCEED, TestEnv.GATEWAY_DELAY_MS));
    var r = refund(p.payment(), "400.00", "rf-4");
    relay.drain();
    assertThat(refundStatus(r.uuid("id"))).isEqualTo("UNKNOWN");
    settle();

    assertThat(refundStatus(r.uuid("id"))).isEqualTo("SUCCEEDED");
    assertThat(gateway.stats().refunds()).isEqualTo(1);
    assertThat(ledger.entries("REFUND", r.uuid("id"))).isEqualTo(1);
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("400.00");
    assertLedgerInvariants();
  }

  @Test
  void declinedRefundMovesNothingAndFreesTheAmountAgain() {
    Paid p = paid();
    gateway.configure(Faults.always(Mode.DECLINE, 0));
    var r = refund(p.payment(), "1000.00", "rf-5");
    settle();
    assertThat(refundStatus(r.uuid("id"))).isEqualTo("FAILED");
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("0.00");
    assertThat(api.points(p.wallet())).isEqualTo(20);

    gateway.configure(Faults.none());
    assertThat(refund(p.payment(), "1000.00", "rf-6").status()).as("FAILED doesn't count").isEqualTo(202);
    settle();
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("1000.00");
    assertLedgerInvariants();
  }

  @Test
  void concurrentRefundsCannotOverRefund() throws Exception {
    Paid p = paid();
    int n = 10;                                     // ten requests for 60% each: only one fits
    var start = new CountDownLatch(1);
    List<Future<Api.Response>> results;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      results = java.util.stream.IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
        start.await();
        return refund(p.payment(), "600.00", "rf-par-" + i);
      })).toList();
      start.countDown();
    }
    long accepted = results.stream().filter(f -> {
      try { return f.get().status() == 202; } catch (Exception e) { throw new RuntimeException(e); }
    }).count();
    assertThat(accepted).isEqualTo(1);
    settle();
    assertThat(api.balance(p.wallet())).isEqualByComparingTo("600.00");
    assertLedgerInvariants();
  }
}
