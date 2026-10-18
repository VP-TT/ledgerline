package com.ledgerline.common;

import com.ledgerline.outbox.OutboxRelay;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Background work: the outbox relay (safe on every instance thanks to SKIP LOCKED), the stuck-event
 * sweeper, metric gauges, and the nightly reconciliation (one instance, via ShedLock). Tests turn this
 * off with {@code ledgerline.scheduling.enabled=false} and drive the relay by hand.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@ConditionalOnProperty(name = "ledgerline.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig implements SchedulingConfigurer {
  private final OutboxRelay relay;
  private final LedgerlineMetrics metrics;
  private final LedgerlineProperties props;

  public SchedulingConfig(OutboxRelay relay, LedgerlineMetrics metrics, LedgerlineProperties props) {
    this.relay = relay;
    this.metrics = metrics;
    this.props = props;
  }

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
        .usingDbTime()
        .build());
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar tasks) {
    tasks.addFixedDelayTask(relay::drainWhileBusy, props.outbox().pollInterval());
    tasks.addFixedDelayTask(relay::sweepStuck, Duration.ofMinutes(1));
    tasks.addFixedDelayTask(metrics::refresh, Duration.ofSeconds(15));
  }
}
