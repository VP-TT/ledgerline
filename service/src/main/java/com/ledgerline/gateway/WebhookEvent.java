package com.ledgerline.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code charge.approved} or {@code charge.declined}, pushed by the gateway. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(String eventId, String type, GatewayCharge charge) {}
