package com.ledgerline.payment;

import com.ledgerline.fakegateway.FakeGateway;
import com.ledgerline.fakegateway.FakeGateway.Faults;
import com.ledgerline.fakegateway.FakeGateway.Mode;
import com.ledgerline.support.IntegrationTest;
import com.ledgerline.support.TestEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookIT extends IntegrationTest {

  long webhookRows() {
    return jdbc.sql("SELECT COUNT(*) FROM gateway_webhook_events").query(Long.class).single();
  }

  String outcome(String eventId) {
    return jdbc.sql("SELECT outcome FROM gateway_webhook_events WHERE event_id = :id").param("id", eventId)
        .query(String.class).single();
  }

  Map<String, Object> event(String eventId, String type, UUID payment, String status, long amount) {
    return Map.of("eventId", eventId, "type", type, "charge", Map.of(
        "chargeId", "ch_" + eventId, "paymentId", payment.toString(), "status", status, "amount", amount,
        "currency", "INR", "chargedAt", Instant.now().toString()));
  }

  com.ledgerline.support.Api.Response sendSigned(Object event, String secret, long t) throws Exception {
    String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);
    return api.postRaw("/v1/webhooks/gateway", body,
        Map.of("Gateway-Signature", "t=" + t + ",v1=" + FakeGateway.sign(secret, t + "." + body)));
  }

  @Test
  void chargedButClientTimedOut_webhookResolvesItWithoutPolling() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    enableWebhooks(0, 1);
    gateway.configure(Faults.always(Mode.TIMEOUT_THEN_SUCCEED, TestEnv.GATEWAY_DELAY_MS));
    UUID p = api.pay(wallet, "30.00", "wh-1").uuid("id");

    relay.drain();      // our call times out, but the gateway's webhook got here first
    await("payment SUCCEEDED", () -> api.paymentStatus(p).equals("SUCCEEDED"));
    settle();           // the scheduled status check, if any, finds nothing left to do

    assertThat(gateway.stats().statusCalls()).as("no polling needed").isZero();
    assertThat(gateway.stats().charges()).isEqualTo(1);
    assertThat(ledger.entries("PAYMENT_SETTLEMENT", p)).isEqualTo(1);
    assertThat(outcome("evt_" + gateway.charge(p.toString()).chargeId())).isEqualTo("APPLIED_SUCCEEDED");
    assertLedgerInvariants();
  }

  @Test
  void redeliveredAndRacingWebhooksApplyExactlyOnce() {
    UUID wallet = api.walletWithBalance("INR", "1000.00");
    enableWebhooks(0, 3);                       // every event delivered three times, racing the relay
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) ids.add(api.pay(wallet, "10.00", "wh-race-" + i).uuid("id"));

    settle();
    await("all webhooks delivered", () -> gateway.stats().webhooksSent() == 60);
    await("all events recorded", () -> webhookRows() == 20);

    for (UUID id : ids) {
      assertThat(api.paymentStatus(id)).isEqualTo("SUCCEEDED");
      assertThat(ledger.entries("PAYMENT_SETTLEMENT", id)).isEqualTo(1);
      assertThat(ledger.entries("REWARD_ACCRUAL", id)).isZero();   // Rs 10 earns no points; no double accrual either
    }
    assertThat(webhookRows()).as("one row per event id, however many deliveries").isEqualTo(20);
    assertThat(api.balance(wallet)).isEqualByComparingTo("800.00");
    assertLedgerInvariants();
  }

  @Test
  void declineWebhookReversesAnUnknownPayment() throws Exception {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gateway.configure(Faults.always(Mode.ERROR, 0));
    UUID p = api.pay(wallet, "40.00", "wh-2").uuid("id");
    relay.drain();
    assertThat(api.paymentStatus(p)).isEqualTo("UNKNOWN");

    var r = sendSigned(event("evt_decl", "charge.declined", p, "DECLINED", 4000), TestEnv.WEBHOOK_SECRET,
        Instant.now().getEpochSecond());
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("outcome")).isEqualTo("APPLIED_REVERSED");
    assertThat(api.paymentStatus(p)).isEqualTo("REVERSED");
    assertThat(api.balance(wallet)).isEqualByComparingTo("100.00");

    var again = sendSigned(event("evt_decl", "charge.declined", p, "DECLINED", 4000), TestEnv.WEBHOOK_SECRET,
        Instant.now().getEpochSecond());
    assertThat(again.text("outcome")).isEqualTo("DUPLICATE");
    assertThat(ledger.entries("PAYMENT_REVERSAL", p)).isEqualTo(1);
    assertLedgerInvariants();
  }

  @Test
  void webhookWithWrongAmountIsNotApplied() throws Exception {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gateway.configure(Faults.always(Mode.ERROR, 0));
    UUID p = api.pay(wallet, "40.00", "wh-3").uuid("id");
    relay.drain();
    var r = sendSigned(event("evt_amt", "charge.approved", p, "APPROVED", 9999), TestEnv.WEBHOOK_SECRET,
        Instant.now().getEpochSecond());
    assertThat(r.text("outcome")).isEqualTo("AMOUNT_MISMATCH");
    assertThat(api.paymentStatus(p)).isEqualTo("UNKNOWN");
  }

  @Test
  void forgedOrReplayedWebhooksAreRejected() throws Exception {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    gateway.configure(Faults.always(Mode.ERROR, 0));
    UUID p = api.pay(wallet, "40.00", "wh-4").uuid("id");
    relay.drain();
    var forged = event("evt_forged", "charge.approved", p, "APPROVED", 4000);
    long now = Instant.now().getEpochSecond();

    assertThat(sendSigned(forged, "not-the-secret", now).status()).isEqualTo(401);
    assertThat(sendSigned(forged, TestEnv.WEBHOOK_SECRET, now - 3600).status()).as("replay").isEqualTo(401);
    assertThat(api.postRaw("/v1/webhooks/gateway", "{}", Map.of()).status()).as("unsigned").isEqualTo(401);

    assertThat(webhookRows()).isZero();
    assertThat(api.paymentStatus(p)).isEqualTo("UNKNOWN");
  }

  @Test
  void webhookForAPaymentWeDontHaveIsAcknowledgedAndRecorded() throws Exception {
    var r = sendSigned(event("evt_orphan", "charge.approved", UUID.randomUUID(), "APPROVED", 100),
        TestEnv.WEBHOOK_SECRET, Instant.now().getEpochSecond());
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("outcome")).isEqualTo("UNKNOWN_PAYMENT");
    assertThat(webhookRows()).isEqualTo(1);
  }
}
