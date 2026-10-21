package com.ledgerline.refund;

import com.ledgerline.biller.BillerRepository;
import com.ledgerline.common.Backoff;
import com.ledgerline.common.LedgerlineProperties;
import com.ledgerline.gateway.GatewayClient;
import com.ledgerline.gateway.RefundLookup;
import com.ledgerline.gateway.SubmitOutcome;
import com.ledgerline.ledger.Account;
import com.ledgerline.ledger.AccountRepository;
import com.ledgerline.ledger.EntryRequest;
import com.ledgerline.ledger.LedgerService;
import com.ledgerline.ledger.Posting;
import com.ledgerline.outbox.OutboxRepository;
import com.ledgerline.payment.Payment;
import com.ledgerline.payment.PaymentRepository;
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

import static com.ledgerline.refund.RefundStatus.*;

/**
 * Drives a refund through the gateway with the same rules as a payment: gateway calls outside
 * transactions, our refund id as the gateway's idempotency key, timeouts → UNKNOWN → status checks,
 * and money only moves on a confirmed approval.
 *
 * <p>On success, one transaction locks the payment, the refund and the user's wallet and points
 * accounts (in that order, the same order refund creation uses), then posts the refund
 * (biller_payable → wallet) and the points clawback together.
 */
@Component
public class RefundProcessor {
  private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

  private final RefundRepository refunds;
  private final PaymentRepository payments;
  private final WalletRepository wallets;
  private final BillerRepository billers;
  private final AccountRepository accounts;
  private final LedgerService ledger;
  private final RewardsService rewards;
  private final OutboxRepository outbox;
  private final GatewayClient gateway;
  private final MeterRegistry metrics;
  private final LedgerlineProperties.Payments config;
  private final TransactionTemplate tx;

  public RefundProcessor(RefundRepository refunds, PaymentRepository payments, WalletRepository wallets,
                         BillerRepository billers, AccountRepository accounts, LedgerService ledger,
                         RewardsService rewards, OutboxRepository outbox, GatewayClient gateway,
                         MeterRegistry metrics, LedgerlineProperties props, PlatformTransactionManager txm) {
    this.refunds = refunds;
    this.payments = payments;
    this.wallets = wallets;
    this.billers = billers;
    this.accounts = accounts;
    this.ledger = ledger;
    this.rewards = rewards;
    this.outbox = outbox;
    this.gateway = gateway;
    this.metrics = metrics;
    this.config = props.payments();
    this.tx = new TransactionTemplate(txm);
  }

  public void submit(UUID id) {
    Refund r = tx.execute(s -> {
      Refund cur = refunds.get(id);
      if (cur.status() == PENDING) cur = move(cur, SUBMITTED, null, null);
      else if (cur.status() != SUBMITTED) return null;
      refunds.incrementAttempts(id);
      return cur;
    });
    if (r == null) return;

    SubmitOutcome outcome = gateway.refund(id,
        new GatewayClient.RefundRequest(r.paymentId().toString(), r.amount(), r.currency()));

    tx.executeWithoutResult(s -> {
      Payment payment = payments.findForUpdate(r.paymentId()).orElseThrow();
      Refund cur = refunds.findForUpdate(id).orElseThrow();
      if (cur.status() != SUBMITTED) return;
      switch (outcome) {
        case SubmitOutcome.Approved a -> succeed(payment, cur, a.chargeId());
        case SubmitOutcome.Declined d -> move(cur, FAILED, d.chargeId(), "declined: " + d.reason());
        case SubmitOutcome.Unknown u -> {
          log.info("refund {} outcome unknown ({}); scheduling status check", id, u.reason());
          scheduleStatusCheck(move(cur, UNKNOWN, null, null).id(), cur.statusChecks());
        }
      }
    });
  }

  public void checkStatus(UUID id) {
    Refund r = refunds.get(id);
    if (r.status() != UNKNOWN) return;
    RefundLookup lookup = gateway.refundStatus(id);

    tx.executeWithoutResult(s -> {
      Payment payment = payments.findForUpdate(r.paymentId()).orElseThrow();
      Refund cur = refunds.findForUpdate(id).orElseThrow();
      if (cur.status() != UNKNOWN) return;
      int checks = refunds.incrementStatusChecks(id);
      switch (lookup) {
        case RefundLookup.Found f when f.refund().amount() != cur.amount() ->
            log.error("refund {}: gateway refunded {} but we asked for {}; leaving it for reconciliation",
                id, f.refund().amount(), cur.amount());
        case RefundLookup.Found f when f.refund().approved() -> succeed(payment, cur, f.refund().refundId());
        case RefundLookup.Found f -> move(cur, FAILED, f.refund().refundId(), "declined: " + f.refund().declineReason());
        case RefundLookup.NotFound n -> {
          move(cur, SUBMITTED, null, null);
          outbox.insert("REFUND", id, RefundService.SUBMIT_EVENT, Map.of("refundId", id, "resubmit", true), null);
        }
        case RefundLookup.Unknown u when checks >= config.maxStatusChecks() ->
            log.warn("refund {} still UNKNOWN after {} checks ({}); waiting for reconciliation", id, checks, u.reason());
        case RefundLookup.Unknown u -> scheduleStatusCheck(id, checks);
      }
    });
  }

  private void succeed(Payment payment, Refund r, String gatewayRef) {
    Wallet w = wallets.get(payment.walletId());
    // lock the user's accounts now (id order): the clawback needs the points balance before posting
    Map<UUID, Account> locked = accounts.lockAllForUpdate(
        java.util.stream.Stream.of(w.accountId(), w.pointsAccountId()).sorted().toList());
    long pointsBalance = locked.get(w.pointsAccountId()).naturalBalance();

    RefundRepository.Settled before = refunds.settled(payment.id());
    var claw = rewards.clawbackFor(r.id(), payment.id(), payment.amount(), before.amount() + r.amount(),
        before.points(), w.pointsAccountId(), pointsBalance);

    move(r, SUCCEEDED, gatewayRef, null);
    refunds.recordClawback(r.id(), claw.points(), claw.shortfall());
    List<EntryRequest> entries = new ArrayList<>();
    entries.add(EntryRequest.of("REFUND", r.id(),
        Posting.debit(billers.get(payment.billerId()).payableAccountId(), r.amount(), r.currency()),
        Posting.credit(w.accountId(), r.amount(), r.currency())));
    claw.entry().ifPresent(entries::add);
    ledger.postAll(entries);
    if (claw.shortfall() > 0) {
      log.warn("refund {}: {} points already spent, recorded as shortfall", r.id(), claw.shortfall());
      metrics.counter("ledgerline.refunds.points_shortfall").increment(claw.shortfall());
    }
  }

  private void scheduleStatusCheck(UUID id, int checksDone) {
    var delay = Backoff.exponential(config.statusCheckBase(), checksDone + 1, config.statusCheckMax());
    OffsetDateTime at = OffsetDateTime.now().plus(delay);
    refunds.setNextAttempt(id, at);
    outbox.insert("REFUND", id, RefundService.STATUS_CHECK_EVENT, Map.of("refundId", id), at);
  }

  private Refund move(Refund r, RefundStatus to, String gatewayRef, String reason) {
    Refund next = refunds.transition(r, to, gatewayRef, reason);
    metrics.counter("ledgerline.refunds.transitions", "to", to.name()).increment();
    return next;
  }
}
