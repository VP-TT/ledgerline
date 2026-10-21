package com.ledgerline.refund;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Refund(UUID id, UUID paymentId, long amount, String currency, RefundStatus status, String gatewayRef,
                     int attempts, int statusChecks, OffsetDateTime nextAttemptAt, String failureReason,
                     long pointsClawedBack, long pointsShortfall, String idempotencyKey, long version,
                     OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
