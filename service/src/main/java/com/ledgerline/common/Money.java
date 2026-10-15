package com.ledgerline.common;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Amounts are BigDecimal at the API edge and signed long minor units everywhere else. */
public final class Money {
  public static final String POINTS = "PTS";
  public static final Set<String> WALLET_CURRENCIES = Set.of("INR", "USD");
  private static final Map<String, Integer> SCALE = Map.of("INR", 2, "USD", 2, POINTS, 0);

  private Money() {}

  public static int scale(String currency) {
    Integer s = SCALE.get(currency);
    if (s == null) throw new ApiException.BadRequest("unsupported-currency", "Unsupported currency " + currency);
    return s;
  }

  public static long toMinor(BigDecimal amount, String currency) {
    int scale = scale(currency);
    if (amount.signum() <= 0) throw new ApiException.BadRequest("invalid-amount", "Amount must be positive");
    if (amount.stripTrailingZeros().scale() > scale)
      throw new ApiException.BadRequest("invalid-amount", currency + " amounts have at most " + scale + " decimal places");
    try {
      return amount.movePointRight(scale).longValueExact();
    } catch (ArithmeticException e) {
      throw new ApiException.BadRequest("invalid-amount", "Amount out of range");
    }
  }

  public static BigDecimal toMajor(long minor, String currency) {
    return BigDecimal.valueOf(minor, scale(currency));
  }
}
