package com.ledgerline.reconciliation;

import com.ledgerline.gateway.GatewayCharge;
import com.ledgerline.payment.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.ledgerline.payment.PaymentStatus.*;
import static com.ledgerline.reconciliation.MismatchType.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {
  static final Instant NOW = Instant.parse("2026-10-16T02:30:00Z");
  static final Instant RECENT = NOW.minus(Duration.ofHours(3));
  static final Instant OLD = NOW.minus(Duration.ofHours(30));
  static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  record Case(String name, PaymentStatus ours, Long ourAmount, Instant createdAt, String theirs, Long theirAmount,
              List<MismatchType> expected) {
    @Override public String toString() { return name; }
  }

  static Stream<Arguments> cases() {
    return Stream.of(
        new Case("agree: succeeded + approved", SUCCEEDED, 5000L, RECENT, "APPROVED", 5000L, List.of()),
        new Case("agree: reversed + declined", REVERSED, 5000L, RECENT, "DECLINED", 5000L, List.of()),
        new Case("agree: reversed, gateway never saw it", REVERSED, 5000L, RECENT, null, null, List.of()),
        new Case("in flight: submitted + approved", SUBMITTED, 5000L, RECENT, "APPROVED", 5000L, List.of()),
        new Case("in flight: unknown, recent", UNKNOWN, 5000L, RECENT, "APPROVED", 5000L, List.of()),
        new Case("ignore declined orphan", null, null, null, "DECLINED", 5000L, List.of()),
        new Case("succeeded but gateway has nothing", SUCCEEDED, 5000L, RECENT, null, null, List.of(MISSING_AT_GATEWAY)),
        new Case("gateway charged, we have nothing", null, null, null, "APPROVED", 5000L, List.of(MISSING_IN_LEDGER)),
        new Case("reversed but gateway charged", REVERSED, 5000L, RECENT, "APPROVED", 5000L, List.of(STATUS_MISMATCH)),
        new Case("failed but gateway charged", FAILED, 5000L, RECENT, "APPROVED", 5000L, List.of(STATUS_MISMATCH)),
        new Case("succeeded but gateway declined", SUCCEEDED, 5000L, RECENT, "DECLINED", 5000L, List.of(STATUS_MISMATCH)),
        new Case("unknown > 24h, gateway charged", UNKNOWN, 5000L, OLD, "APPROVED", 5000L, List.of(STUCK_UNKNOWN)),
        new Case("unknown > 24h, no gateway record", UNKNOWN, 5000L, OLD, null, null, List.of(STUCK_UNKNOWN)),
        new Case("amount differs", SUCCEEDED, 5000L, RECENT, "APPROVED", 4000L, List.of(AMOUNT_MISMATCH)),
        new Case("amount differs and reversed vs charged", REVERSED, 5000L, RECENT, "APPROVED", 4000L,
            List.of(AMOUNT_MISMATCH, STATUS_MISMATCH))
    ).map(Arguments::of);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void classifies(Case c) {
    var ours = c.ours() == null ? List.<Reconciler.Ours>of()
        : List.of(new Reconciler.Ours(ID, c.ours(), c.ourAmount(), "INR", c.createdAt()));
    var theirs = c.theirs() == null ? List.<GatewayCharge>of()
        : List.of(new GatewayCharge("ch_1", ID.toString(), c.theirs(), c.theirAmount(), "INR", null, RECENT));

    var found = Reconciler.compare(ours, theirs, NOW);

    assertThat(found).extracting(Mismatch::type).containsExactlyElementsOf(c.expected());
    found.forEach(m -> {
      assertThat(m.paymentId()).isEqualTo(ID);
      assertThat(m.severity()).isEqualTo(m.type().severity);
    });
  }

  @org.junit.jupiter.api.Test
  void comparesManyPaymentsIndependently() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    var ours = List.of(new Reconciler.Ours(a, SUCCEEDED, 100, "INR", RECENT),
                       new Reconciler.Ours(b, SUCCEEDED, 200, "INR", RECENT));
    var theirs = List.of(new GatewayCharge("ch_a", a.toString(), "APPROVED", 100, "INR", null, RECENT),
                         new GatewayCharge("ch_c", c.toString(), "APPROVED", 300, "INR", null, RECENT));
    assertThat(Reconciler.compare(ours, theirs, NOW))
        .extracting(Mismatch::paymentId, Mismatch::type)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(b, MISSING_AT_GATEWAY),
            org.assertj.core.groups.Tuple.tuple(c, MISSING_IN_LEDGER));
  }
}
