package com.ledgerline.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only code that moves money. Every call runs inside the caller's transaction, so the ledger
 * write commits or rolls back together with the payment row and the outbox event.
 */
@Service
public class LedgerService {
  private final AccountRepository accounts;
  private final JdbcClient jdbc;

  public LedgerService(AccountRepository accounts, JdbcClient jdbc) {
    this.accounts = accounts;
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID post(String type, UUID referenceId, List<Posting> postings) {
    return postAll(List.of(new EntryRequest(type, referenceId, null, postings))).getFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID post(EntryRequest entry) {
    return postAll(List.of(entry)).getFirst();
  }

  /**
   * Posts several entries atomically. All accounts touched by any entry are locked up front, in id
   * order: locking per entry would let two transactions grab overlapping accounts in different orders.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> postAll(List<EntryRequest> entries) {
    entries.forEach(EntryRequest::requireBalanced);

    List<UUID> ids = entries.stream().flatMap(e -> e.postings().stream()).map(Posting::accountId)
        .distinct().sorted().toList();
    Map<UUID, Account> locked = accounts.lockAllForUpdate(ids);
    if (locked.size() != ids.size()) throw new IllegalArgumentException("unknown account in " + ids);

    Map<UUID, Long> balances = new HashMap<>();
    for (EntryRequest e : entries) {
      for (Posting p : e.postings()) {
        Account a = locked.get(p.accountId());
        if (!a.currency().equals(p.currency()))
          throw new IllegalArgumentException("posting in " + p.currency() + " to " + a.code());
        balances.merge(a.id(), a.balance() + p.amount(), (old, ignored) -> old + p.amount());
      }
    }
    for (var b : balances.entrySet()) {
      Account a = locked.get(b.getKey());
      if (!a.allowNegative() && a.wrongSide(b.getValue())) throw new InsufficientFundsException(a);
    }
    balances.forEach(accounts::updateBalance);

    List<UUID> entryIds = new ArrayList<>();
    for (EntryRequest e : entries) {
      UUID entryId = UUID.randomUUID();
      jdbc.sql("INSERT INTO journal_entries (id, type, reference_id, description) VALUES (:id, :type, :ref, :d)")
          .param("id", entryId).param("type", e.type()).param("ref", e.referenceId()).param("d", e.description())
          .update();
      for (Posting p : e.postings()) {
        jdbc.sql("INSERT INTO postings (entry_id, account_id, amount, currency) VALUES (:e, :a, :amt, :c)")
            .param("e", entryId).param("a", p.accountId()).param("amt", p.amount()).param("c", p.currency())
            .update();
      }
      entryIds.add(entryId);
    }
    return entryIds;
  }

  public record HistoryLine(UUID entryId, String type, UUID referenceId, long amount,
                            java.time.OffsetDateTime createdAt) {}

  /** Postings on one account, newest first. */
  public List<HistoryLine> history(UUID accountId, int limit) {
    return jdbc.sql("""
        SELECT e.id AS entry_id, e.type, e.reference_id, p.amount, e.created_at
        FROM postings p JOIN journal_entries e ON e.id = p.entry_id
        WHERE p.account_id = :a ORDER BY p.id DESC LIMIT :limit""")
        .param("a", accountId).param("limit", limit).query(HistoryLine.class).list();
  }
}
