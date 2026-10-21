package com.ledgerline.ledger;

import com.ledgerline.LedgerlineApplication;
import com.ledgerline.fakegateway.FakeGateway;
import com.ledgerline.fakegateway.FakeGateway.Faults;
import com.ledgerline.fakegateway.FakeGateway.Mode;
import com.ledgerline.outbox.OutboxRelay;
import com.ledgerline.support.Api;
import com.ledgerline.support.DbCleaner;
import com.ledgerline.support.LedgerQueries;
import com.ledgerline.support.TestEnv;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: whatever sequence of top-ups, payments, refunds, gateway outcomes, redemptions and retried requests
 * happens, the ledger balances. jqwik generates the sequences and, on failure, shrinks to the
 * shortest sequence that still breaks an invariant.
 *
 * <p>jqwik doesn't run under Spring's test extension, so the app is started by hand.
 */
class LedgerPropertiesIT {
  static ConfigurableApplicationContext app;
  static Api api;
  static JdbcClient jdbc;
  static OutboxRelay relay;
  static LedgerQueries ledger;
  static final FakeGateway gateway = TestEnv.gateway();
  static final AtomicLong opsApplied = new AtomicLong();
  static final AtomicLong sequences = new AtomicLong();
  static final AtomicLong refundsAccepted = new AtomicLong();

  // shorter gateway timeouts than the other tests: this property makes thousands of gateway calls
  static final long DELAY_MS = 150;

  @BeforeContainer
  static void startApp() {
    var props = new java.util.LinkedHashMap<>(TestEnv.properties());
    props.put("server.port", "0");
    props.put("ledgerline.gateway.read-timeout", "100ms");
    String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    app = new SpringApplicationBuilder(LedgerlineApplication.class).run(args);
    api = new Api(app.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    jdbc = app.getBean(JdbcClient.class);
    relay = app.getBean(OutboxRelay.class);
    ledger = new LedgerQueries(jdbc);
  }

  /** Each try starts from an empty ledger, so a failure shrinks to the sequence that actually caused it. */
  @BeforeTry
  void freshWorld() {
    DbCleaner.clean(jdbc);
    gateway.reset();
  }

  @AfterContainer
  static void stopApp() {
    System.out.printf("[property] sequences=%d operations=%d refunds_accepted=%d%n",
        sequences.get(), opsApplied.get(), refundsAccepted.get());
    gateway.reset();
    if (app != null) app.close();
  }

  /** A user with an INR and a USD wallet (sharing one points account), plus the last write for replay. */
  static final class World {
    final UUID inr, usd;
    String lastPath, lastKey;
    Object lastBody;
    Api.Response lastResponse;
    final List<Api.Response> payments = new java.util.ArrayList<>();

    World() {
      String user = "prop-" + UUID.randomUUID();
      inr = api.createWallet(user, "INR");
      usd = api.createWallet(user, "USD");
    }

    Api.Response write(String path, Object body) {
      lastPath = path;
      lastKey = "op-" + UUID.randomUUID();
      lastBody = body;
      lastResponse = api.post(path, lastKey, body);
      assertThat(lastResponse.status()).as("%s %s", path, lastResponse.body()).isLessThan(500);
      return lastResponse;
    }
  }

  sealed interface Op { void apply(World w); }

  record TopUp(long minor, boolean usd) implements Op {
    public void apply(World w) {
      w.write("/v1/wallets/" + (usd ? w.usd : w.inr) + "/topups", Map.of("amount", BigDecimal.valueOf(minor, 2)));
    }
  }

  record PayBill(long minor, boolean usd) implements Op {
    public void apply(World w) {
      Api.Response r = w.write("/v1/payments", usd
          ? Api.payment(w.usd, Api.ELECTRICITY_USD, BigDecimal.valueOf(minor, 2).toPlainString(), "USD")
          : Api.payment(w.inr, Api.ELECTRICITY_INR, BigDecimal.valueOf(minor, 2).toPlainString(), "INR"));
      if (r.status() == 202) w.payments.add(r);
    }
  }

  /**
   * Refund a share of one earlier payment, after letting the relay run (rejected with 422 unless the
   * payment SUCCEEDED and the amount still fits). The refund itself is resolved by later relay runs.
   */
  record RefundSome(int pick, int percent) implements Op {
    public void apply(World w) {
      if (w.payments.isEmpty()) return;
      relay.drain();         // like a client, refund after the payment has had a chance to complete
      Api.Response p = w.payments.get(pick % w.payments.size());
      BigDecimal amount = p.body().path("amount").decimalValue().multiply(BigDecimal.valueOf(percent))
          .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN).max(new BigDecimal("0.01"));
      if (w.write("/v1/payments/" + p.text("id") + "/refunds", Map.of("amount", amount)).status() == 202)
        refundsAccepted.incrementAndGet();
    }
  }

  /**
   * The gateway behaves one way for every call while the relay runs twice. Deterministic on purpose:
   * jqwik can only shrink a failing sequence if replaying it gives the same outcome.
   */
  record ResolvePayments(Mode mode) implements Op {
    public void apply(World w) {
      gateway.configure(Faults.always(mode, DELAY_MS));
      relay.drain();
      sleep(60);           // past the first status check's due time (20 ms + jitter) and the gateway delay
      relay.drain();
    }
  }

  record Redeem(int points) implements Op {
    public void apply(World w) {
      w.write("/v1/rewards/redeem", Map.of("walletId", w.inr, "points", points));
    }
  }

  record RetrySameIdempotencyKey() implements Op {
    public void apply(World w) {
      if (w.lastKey == null) return;
      Api.Response again = api.post(w.lastPath, w.lastKey, w.lastBody);
      assertThat(again.status()).isLessThan(500);
      if (w.lastResponse.status() < 300) {
        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo(w.lastResponse.status());
        assertThat(again.body()).isEqualTo(w.lastResponse.body());
      }
    }
  }

  @Property(tries = 200)
  void ledgerAlwaysBalances(@ForAll("operations") List<Op> ops) {
    World w = new World();
    for (Op op : ops) {
      op.apply(w);
      opsApplied.incrementAndGet();
    }
    sequences.incrementAndGet();

    assertThat(ledger.unbalancedEntries()).as("every entry balances").isZero();
    assertThat(ledger.currenciesNotSummingToZero()).as("whole ledger sums to zero per currency").isEmpty();
    assertThat(ledger.accountsWhereCachedBalanceDiffersFromPostings()).as("cached balances match postings").isEmpty();
    assertThat(ledger.userAccountsBelowZero()).as("no wallet or points balance below zero").isEmpty();
    assertThat(ledger.clearingDrift()).as("gateway_clearing equals money in flight").isEmpty();
    assertThat(ledger.entriesPerReferenceAboveOne()).as("one entry per business event").isZero();
    assertThat(succeededWithoutApprovedCharge()).as("every SUCCEEDED payment was charged").isEmpty();
    assertThat(reversedButCharged()).as("no money returned for a payment the gateway charged").isEmpty();
    assertThat(overRefunded()).as("refunds never exceed the payment").isEmpty();
    assertThat(refundsDisagreeingWithGateway()).as("SUCCEEDED refund ⇔ gateway approved it").isEmpty();
  }

  @Provide
  Arbitrary<List<Op>> operations() {
    Arbitrary<Long> amounts = Arbitraries.longs().between(1, 1_000_000);
    Arbitrary<Boolean> usd = Arbitraries.of(false, false, true);
    return Arbitraries.<Op>oneOf(List.of(
        net.jqwik.api.Combinators.combine(amounts, usd).as(TopUp::new),
        net.jqwik.api.Combinators.combine(amounts, usd).as(PayBill::new),
        net.jqwik.api.Combinators.combine(amounts, usd).as(PayBill::new),
        Arbitraries.of(Mode.SUCCEED, Mode.DECLINE, Mode.TIMEOUT_THEN_SUCCEED, Mode.TIMEOUT_NO_CHARGE, Mode.ERROR)
            .map(ResolvePayments::new),
        Arbitraries.integers().between(1, 500).map(Redeem::new),
        net.jqwik.api.Combinators.combine(Arbitraries.integers().between(0, 50), Arbitraries.integers().between(1, 100))
            .as(RefundSome::new),
        Arbitraries.just(new RetrySameIdempotencyKey())
    )).list().ofMinSize(1).ofMaxSize(40);
  }

  List<UUID> succeededWithoutApprovedCharge() {
    var ids = jdbc.sql("SELECT id FROM payments WHERE status = 'SUCCEEDED'").query(UUID.class).list();
    return ids.stream().filter(id -> {
      var c = gateway.charge(id.toString());
      return c == null || !c.status().equals("APPROVED");
    }).toList();
  }

  List<UUID> overRefunded() {
    return jdbc.sql("""
        SELECT p.id FROM payments p JOIN refunds r ON r.payment_id = p.id AND r.status = 'SUCCEEDED'
        GROUP BY p.id, p.amount HAVING SUM(r.amount) > p.amount""").query(UUID.class).list();
  }

  /** A SUCCEEDED refund must be APPROVED at the gateway; a FAILED one must not be. */
  List<UUID> refundsDisagreeingWithGateway() {
    var rows = jdbc.sql("SELECT id, status FROM refunds WHERE status IN ('SUCCEEDED','FAILED')")
        .query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).list();
    return rows.stream().filter(e -> {
      var g = gateway.refund(e.getKey().toString());
      boolean approved = g != null && g.status().equals("APPROVED");
      return e.getValue().equals("SUCCEEDED") != approved;
    }).map(Map.Entry::getKey).toList();
  }

  List<UUID> reversedButCharged() {
    var ids = jdbc.sql("SELECT id FROM payments WHERE status = 'REVERSED'").query(UUID.class).list();
    return ids.stream().filter(id -> {
      var c = gateway.charge(id.toString());
      return c != null && c.status().equals("APPROVED");
    }).toList();
  }

  static void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
