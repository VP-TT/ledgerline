package com.ledgerline.payment;

import com.ledgerline.outbox.OutboxEvent;
import com.ledgerline.outbox.OutboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;
import java.util.UUID;

@Configuration
public class PaymentHandlers {
  private static final Logger log = LoggerFactory.getLogger(PaymentHandlers.class);

  @Bean OutboxHandler paymentSubmitHandler(PaymentProcessor processor) {
    return handler(PaymentService.SUBMIT_EVENT, processor::submit);
  }

  @Bean OutboxHandler paymentStatusCheckHandler(PaymentProcessor processor) {
    return handler(PaymentService.STATUS_CHECK_EVENT, processor::checkStatus);
  }

  private static OutboxHandler handler(String type, Consumer<UUID> action) {
    return new OutboxHandler() {
      @Override public String eventType() { return type; }
      @Override public void handle(OutboxEvent e) {
        try {
          action.accept(e.aggregateId());
        } catch (TransitionException.Concurrent lostRace) {
          // another worker moved this payment first; its outcome stands
          log.info("{} for {}: {}", type, e.aggregateId(), lostRace.getMessage());
        }
      }
    };
  }
}
