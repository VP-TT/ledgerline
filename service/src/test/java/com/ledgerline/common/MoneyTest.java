package com.ledgerline.common;

import com.ledgerline.ledger.EntryRequest;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.UnbalancedEntryException;
import com.ledgerline.rewards.RewardRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
  final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

  @Test void convertsWithoutFloatingPoint() {
    assertThat(Money.toMinor(new BigDecimal("50.00"), "INR")).isEqualTo(5000);
    assertThat(Money.toMinor(new BigDecimal("0.1"), "USD")).isEqualTo(10);
    assertThat(Money.toMajor(5001, "INR")).isEqualByComparingTo("50.01");
  }

  @Test void rejectsFractionsOfAPaisa() {
    assertThatThrownBy(() -> Money.toMinor(new BigDecimal("1.005"), "INR")).hasMessageContaining("decimal places");
    assertThatThrownBy(() -> Money.toMinor(new BigDecimal("-1"), "INR")).hasMessageContaining("positive");
    assertThatThrownBy(() -> Money.toMinor(BigDecimal.ONE, "EUR")).hasMessageContaining("Unsupported");
  }

  @Test void entryMustBalancePerCurrency() {
    EntryRequest.of("X", UUID.randomUUID(), Posting.debit(a, 5, "INR"), Posting.credit(b, 5, "INR")).requireBalanced();
    assertThatThrownBy(() -> EntryRequest.of("X", UUID.randomUUID(),
        Posting.debit(a, 5, "INR"), Posting.credit(b, 4, "INR")).requireBalanced())
        .isInstanceOf(UnbalancedEntryException.class);
    // sums to zero overall, but not per currency
    assertThatThrownBy(() -> EntryRequest.of("X", UUID.randomUUID(),
        Posting.debit(a, 5, "INR"), Posting.credit(b, 5, "PTS")).requireBalanced())
        .isInstanceOf(UnbalancedEntryException.class);
    assertThatThrownBy(() -> EntryRequest.of("X", UUID.randomUUID(), Posting.debit(a, 0, "INR"), Posting.credit(b, 0, "INR"))
        .requireBalanced()).isInstanceOf(UnbalancedEntryException.class);
  }

  @Test void rewardPointsRoundDownAndCap() {
    var rule = new RewardRule(UUID.randomUUID(), "INR", null, 1, 10000, 500, LocalDate.of(2026, 1, 1), null);
    assertThat(rule.pointsFor(50000)).isEqualTo(5);      // Rs 500 → 5 pts
    assertThat(rule.pointsFor(19999)).isEqualTo(1);      // round down
    assertThat(rule.pointsFor(9999)).isZero();
    assertThat(rule.pointsFor(100_000_000)).isEqualTo(500);
  }

  @Test void backoffDoublesWithBoundedJitter() {
    for (int attempt = 1; attempt <= 5; attempt++) {
      long expected = 1000L << (attempt - 1);
      long got = Backoff.exponential(Duration.ofSeconds(1), attempt, Duration.ofMinutes(5)).toMillis();
      assertThat(got).isBetween(expected, expected + expected / 4);
    }
    assertThat(Backoff.exponential(Duration.ofSeconds(1), 40, Duration.ofSeconds(10)).toMillis()).isBetween(10_000L, 12_500L);
  }
}
