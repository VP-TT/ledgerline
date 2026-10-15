package com.ledgerline.payment;

import com.ledgerline.idempotency.IdempotencyService;
import com.ledgerline.payment.PaymentDtos.PaymentRequest;
import com.ledgerline.payment.PaymentDtos.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentController {
  private final PaymentService payments;
  private final IdempotencyService idempotency;

  public PaymentController(PaymentService payments, IdempotencyService idempotency) {
    this.payments = payments;
    this.idempotency = idempotency;
  }

  /** 202: funds are reserved and the payment is queued for the gateway. Poll GET /v1/payments/{id}. */
  @PostMapping("/v1/payments")
  public ResponseEntity<?> pay(@RequestHeader("X-Client-Id") String clientId,
                               @RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody PaymentRequest req) {
    return idempotency.execute(clientId, key, "POST /v1/payments", req, () -> {
      PaymentResponse p = payments.create(req, key);
      return IdempotencyService.Result.of(HttpStatus.ACCEPTED, p, p.id());
    });
  }

  @GetMapping("/v1/payments/{id}")
  public PaymentResponse get(@PathVariable UUID id) {
    return payments.get(id);
  }
}
