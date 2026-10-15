package com.ledgerline.ledger;

/** A programming error: the caller built postings that don't sum to zero. */
public class UnbalancedEntryException extends IllegalArgumentException {
  public UnbalancedEntryException(String message) { super(message); }
}
