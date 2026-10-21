package com.ledgerline.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayRefund(String refundId, String key, String paymentId, String status, long amount, String currency,
                            String declineReason, Instant refundedAt) {
  public boolean approved() { return "APPROVED".equals(status); }
}
