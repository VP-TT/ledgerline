package com.ledgerline.payment;

import com.ledgerline.fakegateway.FakeGateway;
import com.ledgerline.support.Api;
import com.ledgerline.support.IntegrationTest;
import com.ledgerline.support.TestEnv;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1,000 payment requests fired at once at one wallet that can afford only 600 of them. 300 requests
 * reuse one of 700 idempotency keys. The fake gateway times out on ~10% of submits (half after
 * charging, half without), so the status-check and resubmit paths run under load too.
 */
class ConcurrencyIT extends IntegrationTest {

  @Test
  void thousandParallelPaymentsNeverDoubleCharge() throws Exception {
    UUID wallet = api.walletWithBalance("INR", "600.00");      // exactly 600 payments of Rs 1.00
    gateway.configure(new FakeGateway.Faults(0, 0.05, 0.05, 0, TestEnv.GATEWAY_DELAY_MS, false, 42L));
    int n = 1_000;
    List<String> keys = IntStream.range(0, n).mapToObj(i -> "k-" + (i % 700)).toList();
    var start = new CountDownLatch(1);

    long t0 = System.nanoTime();
    List<Api.Response> responses;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Api.Response>> futures = keys.stream().map(k -> pool.submit(() -> {
        start.await();
        return api.postRetrying("/v1/payments", k, Api.payment(wallet, Api.ELECTRICITY_INR, "1.00", "INR"));
      })).toList();
      start.countDown();
      responses = futures.stream().map(f -> {
        try { return f.get(120, SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
      }).toList();
    }
    long acceptMs = (System.nanoTime() - t0) / 1_000_000;
    settle();
    long totalMs = (System.nanoTime() - t0) / 1_000_000;

    // every response is a 202 (first or replayed) or a 422 for the 100 unaffordable keys
    Map<Integer, Long> byStatus = responses.stream().collect(Collectors.groupingBy(Api.Response::status, Collectors.counting()));
    assertThat(byStatus.keySet()).isSubsetOf(Set.of(202, 422));

    // all requests sharing a key got the same payment back
    Map<String, Set<String>> idsPerKey = new ConcurrentHashMap<>();
    for (int i = 0; i < n; i++) {
      if (responses.get(i).status() == 202)
        idsPerKey.computeIfAbsent(keys.get(i), k -> new HashSet<>()).add(responses.get(i).text("id"));
    }
    assertThat(idsPerKey.values()).allSatisfy(ids -> assertThat(ids).hasSize(1));

    List<UUID> paymentIds = jdbc.sql("SELECT id FROM payments WHERE wallet_id = :w").param("w", wallet)
        .query(UUID.class).list();
    assertThat(paymentIds).hasSize(600);                          // 700 unique keys, only 600 affordable
    assertThat(idsPerKey).hasSize(600);
    assertThat(api.balance(wallet)).isEqualByComparingTo("0.00");   // never negative
    assertThat(ledger.entriesPerReferenceAboveOne()).isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM payments WHERE status = 'SUCCEEDED'").query(Long.class).single())
        .isEqualTo(600);

    var charges = gateway.chargesFor(paymentIds);
    assertThat(charges).hasSize(600).allSatisfy(c -> assertThat(c.status()).isEqualTo("APPROVED"));
    assertThat(gateway.stats().charges()).as("no charge for a payment we don't have").isEqualTo(600);
    assertLedgerInvariants();

    var stats = gateway.stats();
    long replays = responses.stream().filter(Api.Response::replayed).count();
    long unknowns = jdbc.sql("SELECT COUNT(*) FROM payments WHERE status_checks > 0").query(Long.class).single();
    System.out.printf("""
        [concurrency] requests=%d keys=700 accepted_202=%d replayed=%d rejected_422=%d
        [concurrency] distinct_payments=%d succeeded=600 gateway_submits=%d gateway_charges=%d dedup_hits=%d double_charges=0
        [concurrency] payments_that_went_UNKNOWN=%d accept_phase_ms=%d total_ms=%d%n""",
        n, byStatus.getOrDefault(202, 0L), replays, byStatus.getOrDefault(422, 0L),
        paymentIds.size(), stats.submitCalls(), stats.charges(), stats.duplicateSubmits(), unknowns, acceptMs, totalMs);
  }
}
