package com.ledgerline.ledger;

import java.util.UUID;

public record Account(UUID id, String code, AccountType type, String currency, long balance,
                      boolean allowNegative, boolean system) {

  /** True when {@code balance} sits on the wrong side of this account's normal side. */
  public boolean wrongSide(long balance) {
    return type.debitNormal() ? balance < 0 : balance > 0;
  }

  /** Balance as the account holder sees it: positive on the normal side. */
  public long naturalBalance() { return type.debitNormal() ? balance : -balance; }
}
