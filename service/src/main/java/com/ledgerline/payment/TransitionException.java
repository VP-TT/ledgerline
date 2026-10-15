package com.ledgerline.payment;

import java.util.UUID;

public class TransitionException extends RuntimeException {
  private TransitionException(String message) { super(message); }

  /** The state machine forbids this move (a bug in the caller). */
  public static TransitionException illegal(UUID id, PaymentStatus from, PaymentStatus to) {
    return new Illegal("payment " + id + ": " + from + " → " + to + " is not allowed");
  }

  /** Someone else changed the payment first; the loser should stop, not retry blindly. */
  public static TransitionException concurrent(UUID id, PaymentStatus from) {
    return new Concurrent("payment " + id + " is no longer " + from + " at the expected version");
  }

  public static final class Illegal extends TransitionException { Illegal(String m) { super(m); } }
  public static final class Concurrent extends TransitionException { Concurrent(String m) { super(m); } }
}
