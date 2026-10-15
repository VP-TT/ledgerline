package com.ledgerline.rewards;

import com.ledgerline.biller.Biller;
import com.ledgerline.common.ApiException;
import com.ledgerline.common.LedgerlineProperties;
import com.ledgerline.common.Money;
import com.ledgerline.ledger.AccountRepository;
import com.ledgerline.ledger.EntryRequest;
import com.ledgerline.ledger.LedgerService;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.SystemAccounts;
import com.ledgerline.payment.Payment;
import com.ledgerline.wallet.Wallet;
import com.ledgerline.wallet.WalletRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Points are a currency (PTS) in the same ledger, so they get the same balance guarantees as money.
 * Accrual is posted in the transaction that marks a payment SUCCEEDED and is keyed by
 * (REWARD_ACCRUAL, payment id), so a duplicate success can't award points twice.
 */
@Service
public class RewardsService {
  private final JdbcClient jdbc;
  private final LedgerService ledger;
  private final SystemAccounts system;
  private final AccountRepository accounts;
  private final WalletRepository wallets;
  private final LedgerlineProperties.Rewards config;

  public RewardsService(JdbcClient jdbc, LedgerService ledger, SystemAccounts system, AccountRepository accounts,
                        WalletRepository wallets, LedgerlineProperties props) {
    this.jdbc = jdbc;
    this.ledger = ledger;
    this.system = system;
    this.accounts = accounts;
    this.wallets = wallets;
    this.config = props.rewards();
  }

  /** The most specific active rule: one for the biller's category beats a catch-all one. */
  public Optional<RewardRule> ruleFor(String currency, String category, LocalDate day) {
    return jdbc.sql("""
        SELECT * FROM reward_rules
        WHERE currency = :cur AND (biller_category = :cat OR biller_category IS NULL)
          AND active_from <= :d AND (active_to IS NULL OR active_to >= :d)
        ORDER BY (biller_category IS NULL), active_from DESC
        LIMIT 1""")
        .param("cur", currency).param("cat", category).param("d", day).query(RewardRule.class).optional();
  }

  public Optional<EntryRequest> accrualFor(Payment p, Biller b, Wallet w) {
    LocalDate day = p.createdAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    return ruleFor(p.currency(), b.category(), day).flatMap(rule -> {
      long points = rule.pointsFor(p.amount());
      if (points == 0) return Optional.empty();
      return Optional.of(new EntryRequest("REWARD_ACCRUAL", p.id(), "rule=" + rule.id(), List.of(
          Posting.debit(system.rewardsExpense(), points, Money.POINTS),
          Posting.credit(w.pointsAccountId(), points, Money.POINTS))));
    });
  }

  public record RedeemResponse(UUID redemptionId, UUID walletId, long points, java.math.BigDecimal credited,
                               String currency, long pointsBalance, java.math.BigDecimal balance) {}

  /**
   * Turns points into wallet money: one PTS entry (points → rewards_redeemed) and one money entry
   * (rewards_funding → wallet), posted together so the points and the money move or neither does.
   */
  @Transactional
  public RedeemResponse redeem(UUID walletId, long points, String idempotencyKey) {
    if (points <= 0) throw new ApiException.BadRequest("invalid-points", "points must be positive");
    Wallet w = wallets.get(walletId);
    Long rate = config.minorPerPoint().get(w.currency());
    if (rate == null) throw new ApiException.Unprocessable("no-redemption-rate", "No redemption rate for " + w.currency());
    long credit = Math.multiplyExact(points, rate);
    UUID id = UUID.randomUUID();

    jdbc.sql("""
        INSERT INTO redemptions (id, wallet_id, points, credited, currency, idempotency_key)
        VALUES (:id, :w, :p, :c, :cur, :k)""")
        .param("id", id).param("w", walletId).param("p", points).param("c", credit).param("cur", w.currency())
        .param("k", idempotencyKey).update();
    ledger.postAll(List.of(
        EntryRequest.of("REWARD_REDEEM", id,
            Posting.debit(w.pointsAccountId(), points, Money.POINTS),
            Posting.credit(system.rewardsRedeemed(), points, Money.POINTS)),
        EntryRequest.of("REWARD_REDEEM_CASH", id,
            Posting.debit(system.rewardsFunding(w.currency()), credit, w.currency()),
            Posting.credit(w.accountId(), credit, w.currency()))));

    long pointsLeft = accounts.find(w.pointsAccountId()).orElseThrow().naturalBalance();
    long balance = accounts.find(w.accountId()).orElseThrow().naturalBalance();
    return new RedeemResponse(id, walletId, points, Money.toMajor(credit, w.currency()), w.currency(),
        pointsLeft, Money.toMajor(balance, w.currency()));
  }
}
