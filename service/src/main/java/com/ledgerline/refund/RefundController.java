package com.ledgerline.refund;

import com.ledgerline.idempotency.IdempotencyService;
import com.ledgerline.refund.RefundService.RefundResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
public class RefundController {
  private final RefundService refunds;
  private final IdempotencyService idempotency;

  public RefundController(RefundService refunds, IdempotencyService idempotency) {
    this.refunds = refunds;
    this.idempotency = idempotency;
  }

  public record RefundRequest(@NotNull @Positive BigDecimal amount) {}

  /** 202: queued for the gateway; money and points move when it confirms. */
  @PostMapping("/v1/payments/{id}/refunds")
  public ResponseEntity<?> create(@RequestHeader("X-Client-Id") String clientId,
                                  @RequestHeader("Idempotency-Key") String key,
                                  @PathVariable UUID id, @Valid @RequestBody RefundRequest req) {
    return idempotency.execute(clientId, key, "POST /v1/payments/" + id + "/refunds", req, () -> {
      RefundResponse r = refunds.create(id, req.amount(), key);
      return IdempotencyService.Result.of(HttpStatus.ACCEPTED, r, r.id());
    });
  }

  @GetMapping("/v1/payments/{id}/refunds")
  public List<RefundResponse> list(@PathVariable UUID id) { return refunds.forPayment(id); }

  @GetMapping("/v1/refunds/{id}")
  public RefundResponse get(@PathVariable UUID id) { return refunds.get(id); }
}
