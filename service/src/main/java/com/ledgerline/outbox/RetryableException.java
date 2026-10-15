package com.ledgerline.outbox;

/** Transient failure: reschedule the event without counting it towards DEAD. */
public class RetryableException extends RuntimeException {
  public RetryableException(String message, Throwable cause) { super(message, cause); }
}
