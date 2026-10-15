package com.ledgerline.wallet;

import com.ledgerline.common.ApiException;
import com.ledgerline.common.Money;
import com.ledgerline.ledger.Account;
import com.ledgerline.ledger.AccountRepository;
import com.ledgerline.ledger.AccountType;
import com.ledgerline.ledger.LedgerService;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.SystemAccounts;
import com.ledgerline.wallet.WalletDtos.BalanceResponse;
import com.ledgerline.wallet.WalletDtos.TopUpResponse;
import com.ledgerline.wallet.WalletDtos.TransactionLine;
import com.ledgerline.wallet.WalletDtos.WalletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class WalletService {
  private final WalletRepository wallets;
  private final AccountRepository accounts;
  private final SystemAccounts system;
  private final LedgerService ledger;

  public WalletService(WalletRepository wallets, AccountRepository accounts, SystemAccounts system, LedgerService ledger) {
    this.wallets = wallets;
    this.accounts = accounts;
    this.system = system;
    this.ledger = ledger;
  }

  @Transactional
  public WalletResponse create(String userId, String currency) {
    if (!Money.WALLET_CURRENCIES.contains(currency))
      throw new ApiException.BadRequest("unsupported-currency", "Wallets support " + Money.WALLET_CURRENCIES);
    UUID walletId = UUID.randomUUID();
    // the wallet is a liability: money we owe the user, so it must never go to the debit side
    Account money = accounts.getOrCreate("user_wallet:" + walletId, AccountType.LIABILITY, currency, false);
    Account points = accounts.getOrCreate("rewards_points:" + userId, AccountType.LIABILITY, Money.POINTS, false);
    if (!wallets.insert(new Wallet(walletId, userId, currency, money.id(), points.id())))
      throw new ApiException.Conflict("wallet-exists", "User " + userId + " already has a " + currency + " wallet");
    return new WalletResponse(walletId, userId, currency, Money.toMajor(0, currency), points.naturalBalance());
  }

  @Transactional
  public TopUpResponse topUp(UUID walletId, BigDecimal amount, String idempotencyKey) {
    Wallet w = wallets.get(walletId);
    long minor = Money.toMinor(amount, w.currency());
    UUID topupId = UUID.randomUUID();
    wallets.insertTopUp(topupId, walletId, minor, w.currency(), idempotencyKey);
    ledger.post("TOPUP", topupId, List.of(
        Posting.debit(system.cashBank(w.currency()), minor, w.currency()),
        Posting.credit(w.accountId(), minor, w.currency())));
    long balance = accounts.find(w.accountId()).orElseThrow().naturalBalance();
    return new TopUpResponse(topupId, walletId, Money.toMajor(minor, w.currency()), w.currency(),
        Money.toMajor(balance, w.currency()));
  }

  public BalanceResponse balance(UUID walletId) {
    Wallet w = wallets.get(walletId);
    long money = accounts.find(w.accountId()).orElseThrow().naturalBalance();
    long points = accounts.find(w.pointsAccountId()).orElseThrow().naturalBalance();
    return new BalanceResponse(walletId, w.currency(), Money.toMajor(money, w.currency()), points);
  }

  public List<TransactionLine> transactions(UUID walletId, int limit) {
    Wallet w = wallets.get(walletId);
    return ledger.history(w.accountId(), Math.min(Math.max(limit, 1), 500)).stream()
        .map(h -> TransactionLine.from(h, w.currency())).toList();
  }
}
