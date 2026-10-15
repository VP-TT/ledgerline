package com.ledgerline.gateway;

/** What we learned from GET /charges/{paymentId}. */
public sealed interface StatusOutcome {
  record Found(GatewayCharge charge) implements StatusOutcome {}
  record NotFound() implements StatusOutcome {}
  record Unknown(String reason) implements StatusOutcome {}
}
