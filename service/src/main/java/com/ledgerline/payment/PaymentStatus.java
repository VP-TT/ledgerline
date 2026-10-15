package com.ledgerline.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * PENDING → SUBMITTED → SUCCEEDED | FAILED | UNKNOWN; UNKNOWN → SUCCEEDED | FAILED | SUBMITTED;
 * FAILED → REVERSED. SUCCEEDED and REVERSED are terminal.
 */
public enum PaymentStatus {
  PENDING, SUBMITTED, UNKNOWN, SUCCEEDED, FAILED, REVERSED;

  private static final Map<PaymentStatus, Set<PaymentStatus>> NEXT = Map.of(
      PENDING, EnumSet.of(SUBMITTED),
      SUBMITTED, EnumSet.of(SUCCEEDED, FAILED, UNKNOWN),
      UNKNOWN, EnumSet.of(SUCCEEDED, FAILED, SUBMITTED),
      FAILED, EnumSet.of(REVERSED),
      SUCCEEDED, EnumSet.noneOf(PaymentStatus.class),
      REVERSED, EnumSet.noneOf(PaymentStatus.class));

  /** Statuses whose money still sits in gateway_clearing. */
  public static final Set<PaymentStatus> IN_FLIGHT = EnumSet.of(PENDING, SUBMITTED, UNKNOWN, FAILED);

  public boolean canMoveTo(PaymentStatus to) { return NEXT.get(this).contains(to); }
  public boolean terminal() { return NEXT.get(this).isEmpty(); }
}
