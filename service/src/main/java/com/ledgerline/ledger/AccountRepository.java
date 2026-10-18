package com.ledgerline.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {
  private static final String COLUMNS = "id, code, type, currency, balance, allow_negative, system";
  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** Locks the rows in id order, so any two transactions lock shared accounts in the same order. */
  public Map<UUID, Account> lockAllForUpdate(Collection<UUID> ids) {
    Map<UUID, Account> out = new LinkedHashMap<>();
    jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
        .param("ids", ids).query(Account.class).list()
        .forEach(a -> out.put(a.id(), a));
    return out;
  }

  /** Plain read, no lock. Used for immutable facts (type, currency, allow_negative). */
  public Map<UUID, Account> findAll(Collection<UUID> ids) {
    Map<UUID, Account> out = new LinkedHashMap<>();
    jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids)").param("ids", ids)
        .query(Account.class).list().forEach(a -> out.put(a.id(), a));
    return out;
  }

  public void updateBalance(UUID id, long balance) {
    jdbc.sql("UPDATE accounts SET balance = :b, version = version + 1 WHERE id = :id")
        .param("b", balance).param("id", id).update();
  }

  public Optional<Account> findByCode(String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE code = :code").param("code", code)
        .query(Account.class).optional();
  }

  public Optional<Account> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id").param("id", id)
        .query(Account.class).optional();
  }

  /** Creates the account if its code is new; returns the account either way. */
  public Account getOrCreate(String code, AccountType type, String currency, boolean allowNegative) {
    jdbc.sql("""
        INSERT INTO accounts (id, code, type, currency, allow_negative)
        VALUES (:id, :code, :type, :currency, :allowNegative)
        ON CONFLICT (code) DO NOTHING""")
        .param("id", UUID.randomUUID()).param("code", code).param("type", type.name())
        .param("currency", currency).param("allowNegative", allowNegative).update();
    return findByCode(code).orElseThrow();
  }
}
