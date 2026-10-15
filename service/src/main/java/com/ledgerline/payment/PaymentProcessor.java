package com.ledgerline.payment;

import com.ledgerline.biller.Biller;
import com.ledgerline.biller.BillerRepository;
import com.ledgerline.common.Backoff;
import com.ledgerline.common.LedgerlineProperties;
import com.ledgerline.gateway.GatewayClient;
import com.ledgerline.gateway.StatusOutcome;
import com.ledgerline.gateway.SubmitOutcome;
import com.ledgerline.ledger.EntryRequest;
import com.ledgerline.ledger.LedgerService;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.SystemAccounts;
import com.ledgerline.outbox.OutboxRepository;
import com.ledgerline.rewards.RewardsService;
import com.ledgerline.wallet.Wallet;
import com.ledgerline.wallet.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ledgerline.payment.PaymentStatus.*;

/**
 * Drives a payment from PENDING to a terminal state. Gateway calls happen outside any transaction;
 * every state change happens inside one, together with its ledger entries and follow-up outbox events.
 *
 * <p>A timeout never means "failed": it moves the payment to UNKNOWN and schedules status checks.
 * Only a confirmed decline reverses money.
 */
@Component
public class PaymentProcessor {
  private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

  private final PaymentRepository payments;
  private final WalletRepository wallets;
  private final BillerRepository billers;
  private final LedgerService ledger;
  private final SystemAccounts system;
  private final RewardsService rewards;
  private final OutboxRepository outbox;
  private final GatewayClient gateway;
  private final PaymentFaults faults;
  private final MeterRegistry metrics;
  private final LedgerlineProperties.Payments config;
  private final TransactionTemplate tx;

  public PaymentProcessor(PaymentRepository payments, WalletRepository wallets, BillerRepository billers,
                          LedgerService ledger, SystemAccounts system, RewardsService rewards,
                          OutboxRepository outbox, GatewayClient gateway, PaymentFaults faults,
                          MeterRegistry metrics, LedgerlineProperties props, PlatformTransactionManager txm) {
    this.payments = payments;
    this.wallets = wallets;
    this.billers = billers;
    this.ledger = ledger;
    this.system = system;
    this.rewards = rewards;
    this.outbox = outbox;
    this.gateway = gateway;
    this.faults = faults;
    this.metrics = metrics;
    this.config = props.payments();
    this.tx = new TransactionTemplate(txm);
  }

  /** Handles PAYMENT_SUBMIT_REQUESTED. Safe to run again: a resubmit reuses the payment id as the gateway key. */
  public void submit(UUID id) {
    Payment p = tx.execute(s -> {
      Payment cur = payments.get(id);
      if (cur.status() == PENDING) cur = move(cur, SUBMITTED, null, null);
      else if (cur.status() != SUBMITTED) return null;   // resolved already, or UNKNOWN (status checks own it)
      payments.incrementAttempts(id);
      return cur;
    });
    if (p == null) return;

    SubmitOutcome outcome = gateway.submit(id,
        new GatewayClient.ChargeRequest(p.amount(), p.currency(), p.billerId(), p.accountRef()));
    faults.afterGatewayCall(id);

    tx.executeWithoutResult(s -> {
      Payment cur = payments.get(id);
      if (cur.status() != SUBMITTED) return;
      switch (outcome) {
        case SubmitOutcome.Approved a -> succeed(cur, a.chargeId());
        case SubmitOutcome.Declined d -> failAndReverse(cur, d.chargeId(), "declined: " + d.reason());
        case SubmitOutcome.Unknown u -> {
          log.info("payment {} outcome unknown after submit ({}); scheduling status check", id, u.reason());
          Payment unknown = move(cur, UNKNOWN, null, null);
          scheduleStatusCheck(unknown.id(), unknown.statusChecks());
        }
      }
    });
  }

  /** Handles PAYMENT_STATUS_CHECK: asks the gateway what happened instead of charging again. */
  public void checkStatus(UUID id) {
    if (payments.get(id).status() != UNKNOWN) return;
    StatusOutcome outcome = gateway.status(id);

    tx.executeWithoutResult(s -> {
      Payment cur = payments.get(id);
      if (cur.status() != UNKNOWN) return;
      int checks = payments.incrementStatusChecks(id);
      switch (outcome) {
        case StatusOutcome.Found f when f.charge().amount() != cur.amount() -> {
          log.error("payment {}: gateway charged {} but we sent {}; leaving UNKNOWN for reconciliation",
              id, f.charge().amount(), cur.amount());
          metrics.counter("ledgerline.payments.awaiting_reconciliation").increment();
        }
        case StatusOutcome.Found f when f.charge().approved() -> succeed(cur, f.charge().chargeId());
        case StatusOutcome.Found f -> failAndReverse(cur, f.charge().chargeId(), "declined: " + f.charge().declineReason());
        case StatusOutcome.NotFound n -> {
          // the gateway never saw it: resubmit with the same key (a late original is deduped by the gateway)
          move(cur, SUBMITTED, null, null);
          outbox.insert("PAYMENT", id, PaymentService.SUBMIT_EVENT, Map.of("paymentId", id, "resubmit", true), null);
        }
        case StatusOutcome.Unknown u when checks >= config.maxStatusChecks() -> {
          log.warn("payment {} still UNKNOWN after {} status checks ({}); waiting for reconciliation", id, checks, u.reason());
          metrics.counter("ledgerline.payments.awaiting_reconciliation").increment();
        }
        case StatusOutcome.Unknown u -> scheduleStatusCheck(id, checks);
      }
    });
  }

  private void succeed(Payment p, String chargeId) {
    Payment done = move(p, SUCCEEDED, chargeId, null);
    Biller b = billers.get(p.billerId());
    Wallet w = wallets.get(p.walletId());
    List<EntryRequest> entries = new ArrayList<>();
    entries.add(EntryRequest.of("PAYMENT_SETTLEMENT", p.id(),
        Posting.debit(system.gatewayClearing(p.currency()), p.amount(), p.currency()),
        Posting.credit(b.payableAccountId(), p.amount(), p.currency())));
    rewards.accrualFor(done, b, w).ifPresent(entries::add);
    ledger.postAll(entries);
  }

  private void failAndReverse(Payment p, String chargeId, String reason) {
    Payment failed = move(p, FAILED, chargeId, reason);
    move(failed, REVERSED, null, null);
    Wallet w = wallets.get(p.walletId());
    ledger.post("PAYMENT_REVERSAL", p.id(), List.of(
        Posting.debit(system.gatewayClearing(p.currency()), p.amount(), p.currency()),
        Posting.credit(w.accountId(), p.amount(), p.currency())));
  }

  private void scheduleStatusCheck(UUID id, int checksDone) {
    var delay = Backoff.exponential(config.statusCheckBase(), checksDone + 1, config.statusCheckMax());
    OffsetDateTime at = OffsetDateTime.now().plus(delay);
    payments.setNextAttempt(id, at);
    outbox.insert("PAYMENT", id, PaymentService.STATUS_CHECK_EVENT, Map.of("paymentId", id), at);
  }

  private Payment move(Payment p, PaymentStatus to, String gatewayRef, String reason) {
    Payment next = payments.transition(p, to, gatewayRef, reason);
    metrics.counter("ledgerline.payments.transitions", "to", to.name()).increment();
    return next;
  }
}
