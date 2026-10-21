package com.ledgerline.refund;

import com.ledgerline.common.ApiException;
import com.ledgerline.common.Money;
import com.ledgerline.outbox.OutboxRepository;
import com.ledgerline.payment.Payment;
import com.ledgerline.payment.PaymentRepository;
import com.ledgerline.payment.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RefundService {
  public static final String SUBMIT_EVENT = "REFUND_SUBMIT_REQUESTED";
  public static final String STATUS_CHECK_EVENT = "REFUND_STATUS_CHECK";

  private final RefundRepository refunds;
  private final PaymentRepository payments;
  private final OutboxRepository outbox;

  public RefundService(RefundRepository refunds, PaymentRepository payments, OutboxRepository outbox) {
    this.refunds = refunds;
    this.payments = payments;
    this.outbox = outbox;
  }

  public record RefundResponse(UUID id, UUID paymentId, BigDecimal amount, String currency, RefundStatus status,
                               String gatewayRef, String failureReason, long pointsClawedBack, long pointsShortfall,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    static RefundResponse from(Refund r) {
      return new RefundResponse(r.id(), r.paymentId(), Money.toMajor(r.amount(), r.currency()), r.currency(),
          r.status(), r.gatewayRef(), r.failureReason(), r.pointsClawedBack(), r.pointsShortfall(),
          r.createdAt(), r.updatedAt());
    }
  }

  /**
   * Accepts a refund request. The payment row is locked, so two concurrent requests can't both pass the
   * "no more than was paid" check. In-flight and UNKNOWN refunds count against it: they may have happened.
   */
  @Transactional
  public RefundResponse create(UUID paymentId, BigDecimal amount, String key) {
    Payment p = payments.findForUpdate(paymentId).orElseThrow(() -> new ApiException.NotFound("Payment", paymentId));
    if (p.status() != PaymentStatus.SUCCEEDED)
      throw new ApiException.Unprocessable("not-refundable", "Only SUCCEEDED payments can be refunded; this one is " + p.status());
    long minor = Money.toMinor(amount, p.currency());
    long committed = refunds.committedAmount(paymentId);
    if (committed + minor > p.amount())
      throw new ApiException.Unprocessable("refund-exceeds-payment", "Refundable: "
          + Money.toMajor(p.amount() - committed, p.currency()) + " " + p.currency());
    UUID id = UUID.randomUUID();
    refunds.insert(id, paymentId, minor, p.currency(), key);
    outbox.insert("REFUND", id, SUBMIT_EVENT, Map.of("refundId", id, "paymentId", paymentId), null);
    return RefundResponse.from(refunds.get(id));
  }

  public RefundResponse get(UUID id) { return RefundResponse.from(refunds.get(id)); }

  public List<RefundResponse> forPayment(UUID paymentId) {
    payments.get(paymentId);
    return refunds.forPayment(paymentId).stream().map(RefundResponse::from).toList();
  }
}
