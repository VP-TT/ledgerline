package com.ledgerline.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** A charge as the gateway reports it (POST /charges, GET /charges/{id}, settlement reports). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayCharge(String chargeId, String paymentId, String status, long amount, String currency,
                            String declineReason, Instant chargedAt) {
  public boolean approved() { return "APPROVED".equals(status); }
}
