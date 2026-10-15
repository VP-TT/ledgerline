package com.ledgerline.ledger;

import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerIT extends IntegrationTest {
  @Autowired LedgerService ledgerService;
  @Autowired SystemAccounts system;
  @Autowired TransactionTemplate tx;

  @Test
  void topUpAndPaymentPostBalancedEntries() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    assertThat(api.pay(wallet, "40.00", "k1").status()).isEqualTo(202);

    assertThat(api.balance(wallet)).isEqualByComparingTo("60.00");
    var lines = api.get("/v1/wallets/" + wallet + "/transactions").body();
    assertThat(lines.get(0).path("type").asText()).isEqualTo("BILL_PAYMENT");
    assertThat(lines.get(0).path("amount").decimalValue()).isEqualByComparingTo("-40.00");
    assertThat(lines.get(1).path("type").asText()).isEqualTo("TOPUP");
    assertLedgerInvariants();
  }

  @Test
  void serviceRefusesUnbalancedEntries() {
    UUID cash = system.cashBank("INR"), clearing = system.gatewayClearing("INR");
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledgerService.post("TEST", UUID.randomUUID(),
        List.of(Posting.debit(cash, 100, "INR"), Posting.credit(clearing, 99, "INR")))))
        .isInstanceOf(UnbalancedEntryException.class);
  }

  @Test
  void ledgerWritesNeedAnEnclosingTransaction() {
    assertThatThrownBy(() -> ledgerService.post("TEST", UUID.randomUUID(), List.of()))
        .hasMessageContaining("mandatory");
  }

  @Test
  void databaseRejectsUnbalancedEntryAtCommit() {
    UUID cash = system.cashBank("INR");
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      UUID entry = UUID.randomUUID();
      jdbc.sql("INSERT INTO journal_entries (id, type, reference_id) VALUES (:id, 'HACK', :id)").param("id", entry).update();
      jdbc.sql("INSERT INTO postings (entry_id, account_id, amount, currency) VALUES (:e, :a, 100, 'INR')")
          .param("e", entry).param("a", cash).update();
    })).hasMessageContaining("unbalanced");
    assertThat(ledger.unbalancedEntries()).isZero();
  }

  @Test
  void postingsMustMatchTheAccountCurrency() {
    UUID cash = system.cashBank("INR"), points = system.rewardsExpense();
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledgerService.post("TEST", UUID.randomUUID(),
        List.of(Posting.debit(cash, 100, "PTS"), Posting.credit(points, 100, "PTS")))))
        .hasMessageContaining("posting in PTS");
  }

  @Test
  void ledgerIsAppendOnly() {
    UUID wallet = api.walletWithBalance("INR", "10.00");
    assertThat(wallet).isNotNull();
    assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount = amount * 2").update()).rootCause().hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries").update()).rootCause().hasMessageContaining("append-only");
  }

  @Test
  void walletCannotGoBelowZero() {
    UUID wallet = api.walletWithBalance("INR", "10.00");
    var r = api.pay(wallet, "10.01", "too-much");
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.problemType()).isEqualTo("insufficient-funds");
    assertThat(api.balance(wallet)).isEqualByComparingTo("10.00");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM payments").query(Long.class).single()).isZero();
    assertLedgerInvariants();
  }
}
