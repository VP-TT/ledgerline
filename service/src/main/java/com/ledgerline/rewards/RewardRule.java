package com.ledgerline.rewards;

import java.time.LocalDate;
import java.util.UUID;

public record RewardRule(UUID id, String currency, String billerCategory, int pointsPerUnit, long unitMinor,
                         Integer maxPoints, LocalDate activeFrom, LocalDate activeTo) {

  /** Whole points only, rounded down, capped at maxPoints. */
  public long pointsFor(long amountMinor) {
    long points = (amountMinor / unitMinor) * pointsPerUnit;
    return maxPoints == null ? points : Math.min(points, maxPoints);
  }
}
