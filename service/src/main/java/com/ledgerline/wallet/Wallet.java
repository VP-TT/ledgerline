package com.ledgerline.wallet;

import java.util.UUID;

public record Wallet(UUID id, String userId, String currency, UUID accountId, UUID pointsAccountId) {}
