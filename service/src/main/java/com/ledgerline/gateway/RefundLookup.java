package com.ledgerline.gateway;

/** What we learned from GET /refunds/{refundId}. */
public sealed interface RefundLookup {
  record Found(GatewayRefund refund) implements RefundLookup {}
  record NotFound() implements RefundLookup {}
  record Unknown(String reason) implements RefundLookup {}
}
