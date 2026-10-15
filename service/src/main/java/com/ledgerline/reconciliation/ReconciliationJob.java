package com.ledgerline.reconciliation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
@ConditionalOnProperty(name = "ledgerline.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {
  private final ReconciliationService service;

  public ReconciliationJob(ReconciliationService service) { this.service = service; }

  /** 02:30 UTC: reconcile yesterday. ShedLock keeps it to one instance; the unique run_date keeps it to once. */
  @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
  @SchedulerLock(name = "reconciliation", lockAtMostFor = "PT1H")
  public void nightly() {
    service.run(LocalDate.now(ZoneOffset.UTC).minusDays(1));
  }
}
