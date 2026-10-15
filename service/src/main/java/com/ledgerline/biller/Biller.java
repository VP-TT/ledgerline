package com.ledgerline.biller;

import java.util.UUID;

public record Biller(UUID id, String code, String name, String category, String currency,
                     UUID payableAccountId, boolean active) {}
