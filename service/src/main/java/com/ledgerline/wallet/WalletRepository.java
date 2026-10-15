package com.ledgerline.wallet;

import com.ledgerline.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class WalletRepository {
  private final JdbcClient jdbc;

  public WalletRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public Wallet get(UUID id) {
    return jdbc.sql("SELECT id, user_id, currency, account_id, points_account_id FROM wallets WHERE id = :id")
        .param("id", id).query(Wallet.class).optional()
        .orElseThrow(() -> new ApiException.NotFound("Wallet", id));
  }

  /** Returns false if the user already has a wallet in this currency. */
  public boolean insert(Wallet w) {
    return jdbc.sql("""
        INSERT INTO wallets (id, user_id, currency, account_id, points_account_id)
        VALUES (:id, :user, :cur, :acct, :pts) ON CONFLICT (user_id, currency) DO NOTHING""")
        .param("id", w.id()).param("user", w.userId()).param("cur", w.currency())
        .param("acct", w.accountId()).param("pts", w.pointsAccountId()).update() == 1;
  }

  public void insertTopUp(UUID id, UUID walletId, long amount, String currency, String key) {
    jdbc.sql("INSERT INTO topups (id, wallet_id, amount, currency, idempotency_key) VALUES (:id, :w, :a, :c, :k)")
        .param("id", id).param("w", walletId).param("a", amount).param("c", currency).param("k", key).update();
  }
}
