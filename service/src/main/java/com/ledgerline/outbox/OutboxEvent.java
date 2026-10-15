package com.ledgerline.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload,
                          String status, int attempts, OffsetDateTime availableAt, String lastError,
                          OffsetDateTime createdAt) {}
