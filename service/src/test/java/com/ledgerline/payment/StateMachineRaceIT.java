package com.ledgerline.payment;

import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineRaceIT extends IntegrationTest {
  @Autowired PaymentRepository payments;
  @Autowired TransactionTemplate tx;

  UUID pendingPayment() {
    UUID wallet = api.walletWithBalance("INR", "100.00");
    return api.pay(wallet, "10.00", "race-" + UUID.randomUUID()).uuid("id");
  }

  @RepeatedTest(5)
  void twoWritersRacingTheSameTransition_exactlyOneWins() throws Exception {
    UUID id = pendingPayment();
    Payment snapshot = payments.get(id);
    int threads = 8;
    var start = new CountDownLatch(1);
    var won = new AtomicInteger();
    var lost = new AtomicInteger();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
          start.await();
          try {
            tx.executeWithoutResult(s -> payments.transition(snapshot, PaymentStatus.SUBMITTED, null, null));
            won.incrementAndGet();
          } catch (TransitionException.Concurrent e) {
            lost.incrementAndGet();
          }
          return null;
        });
      }
      start.countDown();
    }
    assertThat(won.get()).isEqualTo(1);
    assertThat(lost.get()).isEqualTo(threads - 1);
    assertThat(payments.get(id).version()).isEqualTo(snapshot.version() + 1);
  }

  @Test
  void illegalTransitionsAreRejectedBeforeTouchingTheDatabase() {
    Payment p = payments.get(pendingPayment());
    assertThatThrownBy(() -> payments.transition(p, PaymentStatus.SUCCEEDED, "ch_x", null))
        .isInstanceOf(TransitionException.Illegal.class);
    assertThatThrownBy(() -> payments.transition(p, PaymentStatus.REVERSED, null, null))
        .isInstanceOf(TransitionException.Illegal.class);
    assertThat(payments.get(p.id()).status()).isEqualTo(PaymentStatus.PENDING);
  }
}
