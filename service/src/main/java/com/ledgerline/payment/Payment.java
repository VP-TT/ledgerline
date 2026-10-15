package com.ledgerline.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Payment(UUID id, UUID walletId, UUID billerId, String accountRef, long amount, String currency,
                      PaymentStatus status, String gatewayRef, int attempts, int statusChecks,
                      OffsetDateTime nextAttemptAt, String failureReason, String idempotencyKey, long version,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
