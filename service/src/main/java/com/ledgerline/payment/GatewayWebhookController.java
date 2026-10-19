package com.ledgerline.payment;

import com.ledgerline.common.ApiException;
import com.ledgerline.common.Json;
import com.ledgerline.gateway.WebhookEvent;
import com.ledgerline.gateway.WebhookVerifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Gateway → us: asynchronous charge outcomes. The signature is checked over the raw bytes before the
 * body is even parsed. Any 2xx tells the gateway to stop retrying, including for duplicates.
 */
@RestController
public class GatewayWebhookController {
  private final WebhookVerifier verifier;
  private final PaymentProcessor processor;
  private final Json json;

  public GatewayWebhookController(WebhookVerifier verifier, PaymentProcessor processor, Json json) {
    this.verifier = verifier;
    this.processor = processor;
    this.json = json;
  }

  public record Ack(String eventId, String outcome) {}

  @PostMapping("/v1/webhooks/gateway")
  public Ack receive(@RequestHeader(value = "Gateway-Signature", required = false) String signature,
                     @RequestBody byte[] body) {
    verifier.verify(signature, body);
    String text = new String(body, StandardCharsets.UTF_8);
    WebhookEvent event;
    try {
      event = json.read(text, WebhookEvent.class);
    } catch (IllegalStateException e) {
      throw new ApiException.BadRequest("bad-webhook", "Unparseable webhook body");
    }
    if (event.eventId() == null || event.charge() == null || event.charge().paymentId() == null)
      throw new ApiException.BadRequest("bad-webhook", "Webhook needs eventId and charge.paymentId");
    return new Ack(event.eventId(), processor.onWebhook(event, text));
  }
}
