package com.ledgerline.rewards;

import com.ledgerline.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class RewardsController {
  private final RewardsService rewards;
  private final IdempotencyService idempotency;

  public RewardsController(RewardsService rewards, IdempotencyService idempotency) {
    this.rewards = rewards;
    this.idempotency = idempotency;
  }

  public record RedeemRequest(@NotNull UUID walletId, @NotNull @Positive Long points) {}

  @PostMapping("/v1/rewards/redeem")
  public ResponseEntity<?> redeem(@RequestHeader("X-Client-Id") String clientId,
                                  @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody RedeemRequest req) {
    return idempotency.execute(clientId, key, "POST /v1/rewards/redeem", req, () -> {
      var r = rewards.redeem(req.walletId(), req.points(), key);
      return IdempotencyService.Result.of(HttpStatus.CREATED, r, r.redemptionId());
    });
  }
}
