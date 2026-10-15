package com.ledgerline.reconciliation;

import java.util.UUID;

public record Mismatch(UUID paymentId, MismatchType type, MismatchType.Severity severity, String ledgerStatus,
                       String gatewayStatus, Long ledgerAmount, Long gatewayAmount, String notes) {

  public static Mismatch of(MismatchType type, UUID paymentId, String ledgerStatus, String gatewayStatus,
                            Long ledgerAmount, Long gatewayAmount, String notes) {
    return new Mismatch(paymentId, type, type.severity, ledgerStatus, gatewayStatus, ledgerAmount, gatewayAmount, notes);
  }

  public static Mismatch integrity(MismatchType type, String notes) {
    return of(type, null, null, null, null, null, notes);
  }
}
