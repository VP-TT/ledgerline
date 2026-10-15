package com.ledgerline.payment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static com.ledgerline.payment.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {
  static final Set<String> ALLOWED = Set.of(
      "PENDING>SUBMITTED",
      "SUBMITTED>SUCCEEDED", "SUBMITTED>FAILED", "SUBMITTED>UNKNOWN",
      "UNKNOWN>SUCCEEDED", "UNKNOWN>FAILED", "UNKNOWN>SUBMITTED",
      "FAILED>REVERSED");

  static Stream<Arguments> everyPair() {
    return Stream.of(values()).flatMap(from -> Stream.of(values()).map(to -> Arguments.of(from, to)));
  }

  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("everyPair")
  void onlyListedTransitionsAreAllowed(PaymentStatus from, PaymentStatus to) {
    assertThat(from.canMoveTo(to)).isEqualTo(ALLOWED.contains(from + ">" + to));
  }

  @org.junit.jupiter.api.Test
  void succeededAndReversedAreTerminal() {
    assertThat(Stream.of(values()).filter(PaymentStatus::terminal)).containsExactlyInAnyOrder(SUCCEEDED, REVERSED);
  }
}
