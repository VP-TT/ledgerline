package com.ledgerline.reconciliation;

public enum MismatchType {
  /** We say SUCCEEDED, the gateway has no charge. */
  MISSING_AT_GATEWAY(Severity.HIGH),
  /** The gateway charged a payment we have no record of: a possible orphan charge. */
  MISSING_IN_LEDGER(Severity.HIGH),
  /** We say FAILED/REVERSED but the gateway charged (double-charge risk), or we say SUCCEEDED and it declined. */
  STATUS_MISMATCH(Severity.HIGH),
  /** UNKNOWN for more than 24 h; apply the gateway's outcome through the state machine. */
  STUCK_UNKNOWN(Severity.MEDIUM),
  AMOUNT_MISMATCH(Severity.HIGH),
  /** accounts.balance ≠ SUM(postings.amount): a bug, stop and investigate. */
  BALANCE_DRIFT(Severity.HIGH),
  /** A journal entry that doesn't sum to zero (the trigger should make this impossible). */
  UNBALANCED_ENTRY(Severity.HIGH),
  /** gateway_clearing ≠ total of in-flight payments. */
  CLEARING_DRIFT(Severity.HIGH),
  /** An outbox event gave up after max attempts. */
  DEAD_OUTBOX_EVENT(Severity.MEDIUM);

  public final Severity severity;

  MismatchType(Severity severity) { this.severity = severity; }

  public enum Severity { HIGH, MEDIUM, LOW }
}
