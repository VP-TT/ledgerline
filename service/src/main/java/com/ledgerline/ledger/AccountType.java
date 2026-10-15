package com.ledgerline.ledger;

public enum AccountType {
  ASSET, LIABILITY, EXPENSE, REVENUE, EQUITY;

  /** Assets and expenses grow with debits (positive); the rest grow with credits (negative). */
  public boolean debitNormal() { return this == ASSET || this == EXPENSE; }
}
