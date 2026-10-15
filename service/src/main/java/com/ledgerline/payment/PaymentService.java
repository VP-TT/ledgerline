package com.ledgerline.payment;

import com.ledgerline.biller.Biller;
import com.ledgerline.biller.BillerRepository;
import com.ledgerline.common.ApiException;
import com.ledgerline.common.Money;
import com.ledgerline.ledger.LedgerService;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.SystemAccounts;
import com.ledgerline.outbox.OutboxRepository;
import com.ledgerline.payment.PaymentDtos.PaymentRequest;
import com.ledgerline.payment.PaymentDtos.PaymentResponse;
import com.ledgerline.wallet.Wallet;
import com.ledgerline.wallet.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accepting a payment touches only Postgres: payment row, balanced ledger entry (wallet → clearing) and
 * outbox event, all in the caller's transaction. Nothing talks to the gateway until that commits.
 */
@Service
public class PaymentService {
  public static final String SUBMIT_EVENT = "PAYMENT_SUBMIT_REQUESTED";
  public static final String STATUS_CHECK_EVENT = "PAYMENT_STATUS_CHECK";

  private final PaymentRepository payments;
  private final WalletRepository wallets;
  private final BillerRepository billers;
  private final LedgerService ledger;
  private final SystemAccounts system;
  private final OutboxRepository outbox;

  public PaymentService(PaymentRepository payments, WalletRepository wallets, BillerRepository billers,
                        LedgerService ledger, SystemAccounts system, OutboxRepository outbox) {
    this.payments = payments;
    this.wallets = wallets;
    this.billers = billers;
    this.ledger = ledger;
    this.system = system;
    this.outbox = outbox;
  }

  @Transactional
  public PaymentResponse create(PaymentRequest req, String idempotencyKey) {
    Wallet w = wallets.get(req.walletId());
    Biller b = billers.get(req.billerId());
    if (!b.active()) throw new ApiException.Unprocessable("biller-inactive", "Biller " + b.code() + " is not accepting payments");
    if (!req.currency().equals(w.currency()) || !req.currency().equals(b.currency()))
      throw new ApiException.Unprocessable("currency-mismatch",
          "Payment is " + req.currency() + ", wallet is " + w.currency() + ", biller is " + b.currency());
    long amount = Money.toMinor(req.amount(), req.currency());

    UUID id = UUID.randomUUID();
    payments.insert(new Payment(id, w.id(), b.id(), req.accountRef(), amount, req.currency(), PaymentStatus.PENDING,
        null, 0, 0, null, null, idempotencyKey, 0, null, null));
    // locks the wallet row FOR UPDATE: concurrent payments on one wallet serialize here, and the
    // balance check throws InsufficientFunds (rolling everything back) if this one can't be afforded
    ledger.post("BILL_PAYMENT", id, List.of(
        Posting.debit(w.accountId(), amount, req.currency()),
        Posting.credit(system.gatewayClearing(req.currency()), amount, req.currency())));
    outbox.insert("PAYMENT", id, SUBMIT_EVENT, Map.of("paymentId", id), null);
    return PaymentResponse.from(payments.get(id));
  }

  public PaymentResponse get(UUID id) {
    return PaymentResponse.from(payments.get(id));
  }
}
