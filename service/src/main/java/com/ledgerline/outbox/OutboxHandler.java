package com.ledgerline.outbox;

/**
 * Handles one event type. Delivery is at-least-once, so every handler must be idempotent: running it
 * twice for the same event must have the same effect as running it once.
 */
public interface OutboxHandler {
  String eventType();
  void handle(OutboxEvent event);
}
