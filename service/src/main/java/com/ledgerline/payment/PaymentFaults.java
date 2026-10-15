package com.ledgerline.payment;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fault-injection seam for tests: lets a test "crash" the relay between the gateway call and the
 * state update, which is the window the outbox and gateway idempotency key have to cover. No-op in production.
 */
@Component
public class PaymentFaults {
  private volatile Consumer<UUID> afterGatewayCall = id -> {};

  public void afterGatewayCall(UUID paymentId) { afterGatewayCall.accept(paymentId); }

  public void onAfterGatewayCall(Consumer<UUID> hook) { this.afterGatewayCall = hook; }

  public void clear() { this.afterGatewayCall = id -> {}; }
}
