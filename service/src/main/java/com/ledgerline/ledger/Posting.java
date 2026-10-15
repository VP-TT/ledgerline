package com.ledgerline.ledger;

import java.util.UUID;

/** One leg of a journal entry, in minor units: debit = positive, credit = negative. */
public record Posting(UUID accountId, long amount, String currency) {
  public static Posting debit(UUID account, long amount, String currency) { return new Posting(account, amount, currency); }
  public static Posting credit(UUID account, long amount, String currency) { return new Posting(account, -amount, currency); }
}
