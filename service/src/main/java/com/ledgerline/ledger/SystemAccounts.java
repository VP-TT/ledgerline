package com.ledgerline.ledger;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ids of the seeded system accounts (cash_bank:INR, gateway_clearing:USD, ...), cached after first lookup. */
@Component
public class SystemAccounts {
  private final AccountRepository accounts;
  private final Map<String, UUID> cache = new ConcurrentHashMap<>();

  public SystemAccounts(AccountRepository accounts) { this.accounts = accounts; }

  public UUID cashBank(String currency) { return id("cash_bank:" + currency); }
  public UUID gatewayClearing(String currency) { return id("gateway_clearing:" + currency); }
  public UUID rewardsFunding(String currency) { return id("rewards_funding:" + currency); }
  public UUID rewardsExpense() { return id("rewards_expense:PTS"); }
  public UUID rewardsRedeemed() { return id("rewards_redeemed:PTS"); }

  private UUID id(String code) {
    return cache.computeIfAbsent(code, c -> accounts.findByCode(c)
        .orElseThrow(() -> new IllegalStateException("missing system account " + c)).id());
  }
}
