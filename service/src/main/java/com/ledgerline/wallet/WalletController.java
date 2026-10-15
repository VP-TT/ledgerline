package com.ledgerline.wallet;

import com.ledgerline.idempotency.IdempotencyService;
import com.ledgerline.wallet.WalletDtos.BalanceResponse;
import com.ledgerline.wallet.WalletDtos.CreateWalletRequest;
import com.ledgerline.wallet.WalletDtos.TopUpRequest;
import com.ledgerline.wallet.WalletDtos.TransactionLine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class WalletController {
  private final WalletService wallets;
  private final IdempotencyService idempotency;

  public WalletController(WalletService wallets, IdempotencyService idempotency) {
    this.wallets = wallets;
    this.idempotency = idempotency;
  }

  @PostMapping("/v1/wallets")
  public ResponseEntity<?> create(@RequestHeader("X-Client-Id") String clientId,
                                  @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody CreateWalletRequest req) {
    return idempotency.execute(clientId, key, "POST /v1/wallets", req, () -> {
      var w = wallets.create(req.userId(), req.currency());
      return IdempotencyService.Result.of(HttpStatus.CREATED, w, w.walletId());
    });
  }

  @PostMapping("/v1/wallets/{id}/topups")
  public ResponseEntity<?> topUp(@RequestHeader("X-Client-Id") String clientId,
                                 @RequestHeader("Idempotency-Key") String key,
                                 @PathVariable UUID id, @Valid @RequestBody TopUpRequest req) {
    return idempotency.execute(clientId, key, "POST /v1/wallets/" + id + "/topups", req, () -> {
      var t = wallets.topUp(id, req.amount(), key);
      return IdempotencyService.Result.of(HttpStatus.CREATED, t, t.topupId());
    });
  }

  @GetMapping("/v1/wallets/{id}/balance")
  public BalanceResponse balance(@PathVariable UUID id) {
    return wallets.balance(id);
  }

  @GetMapping("/v1/wallets/{id}/transactions")
  public List<TransactionLine> transactions(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
    return wallets.transactions(id, limit);
  }
}
