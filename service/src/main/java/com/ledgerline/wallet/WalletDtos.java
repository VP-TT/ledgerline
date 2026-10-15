package com.ledgerline.wallet;

import com.ledgerline.ledger.LedgerService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class WalletDtos {
  private WalletDtos() {}

  public record CreateWalletRequest(@NotBlank @Size(max = 128) String userId,
                                    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}

  public record WalletResponse(UUID walletId, String userId, String currency, BigDecimal balance, long points) {}

  public record TopUpRequest(@NotNull @Positive BigDecimal amount) {}

  public record TopUpResponse(UUID topupId, UUID walletId, BigDecimal amount, String currency, BigDecimal balance) {}

  public record BalanceResponse(UUID walletId, String currency, BigDecimal balance, long points) {}

  /** One ledger line from the wallet holder's point of view: positive = money in. */
  public record TransactionLine(UUID entryId, String type, UUID referenceId, BigDecimal amount,
                                OffsetDateTime createdAt) {
    static TransactionLine from(LedgerService.HistoryLine h, String currency) {
      return new TransactionLine(h.entryId(), h.type(), h.referenceId(),
          com.ledgerline.common.Money.toMajor(-h.amount(), currency), h.createdAt());
    }
  }
}
