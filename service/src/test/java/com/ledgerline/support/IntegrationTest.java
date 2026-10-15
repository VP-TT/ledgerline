package com.ledgerline.support;

import com.ledgerline.fakegateway.FakeGateway;
import com.ledgerline.outbox.OutboxRelay;
import com.ledgerline.payment.PaymentFaults;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    TestEnv.properties().forEach((k, v) -> registry.add(k, () -> v));
  }

  @LocalServerPort protected int port;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected OutboxRelay relay;
  @Autowired protected PaymentFaults faults;

  protected Api api;
  protected LedgerQueries ledger;
  protected final FakeGateway gateway = TestEnv.gateway();

  @BeforeEach
  void resetWorld() {
    DbCleaner.clean(jdbc);
    gateway.reset();
    faults.clear();
    api = new Api(port);
    ledger = new LedgerQueries(jdbc);
  }

  protected void settle() {
    assertThat(relay.drainUntilIdle(Duration.ofSeconds(60))).as("outbox drained").isTrue();
  }

  protected void assertLedgerInvariants() {
    assertThat(ledger.unbalancedEntries()).as("unbalanced entries").isZero();
    assertThat(ledger.currenciesNotSummingToZero()).as("currencies not summing to zero").isEmpty();
    assertThat(ledger.accountsWhereCachedBalanceDiffersFromPostings()).as("cached balance drift").isEmpty();
    assertThat(ledger.userAccountsBelowZero()).as("user accounts below zero").isEmpty();
    assertThat(ledger.clearingDrift()).as("gateway_clearing drift").isEmpty();
  }
}
