package com.ledgerline.payment;

import com.ledgerline.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class PaymentDtos {
  private PaymentDtos() {}

  public record PaymentRequest(@NotNull UUID walletId, @NotNull UUID billerId,
                               @NotBlank @Size(max = 64) String accountRef,
                               @NotNull @Positive BigDecimal amount,
                               @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}

  public record PaymentResponse(UUID id, UUID walletId, UUID billerId, String accountRef, BigDecimal amount,
                                String currency, PaymentStatus status, String gatewayRef, String failureReason,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public static PaymentResponse from(Payment p) {
      return new PaymentResponse(p.id(), p.walletId(), p.billerId(), p.accountRef(),
          Money.toMajor(p.amount(), p.currency()), p.currency(), p.status(), p.gatewayRef(), p.failureReason(),
          p.createdAt(), p.updatedAt());
    }
  }
}
