package com.ledgerline.refund;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** PENDING → SUBMITTED → SUCCEEDED | FAILED | UNKNOWN; UNKNOWN → SUCCEEDED | FAILED | SUBMITTED. */
public enum RefundStatus {
  PENDING, SUBMITTED, UNKNOWN, SUCCEEDED, FAILED;

  private static final Map<RefundStatus, Set<RefundStatus>> NEXT = Map.of(
      PENDING, EnumSet.of(SUBMITTED),
      SUBMITTED, EnumSet.of(SUCCEEDED, FAILED, UNKNOWN),
      UNKNOWN, EnumSet.of(SUCCEEDED, FAILED, SUBMITTED),
      SUCCEEDED, EnumSet.noneOf(RefundStatus.class),
      FAILED, EnumSet.noneOf(RefundStatus.class));

  public boolean canMoveTo(RefundStatus to) { return NEXT.get(this).contains(to); }
}
