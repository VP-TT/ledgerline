package com.ledgerline.reconciliation;

import com.ledgerline.common.Json;
import com.ledgerline.gateway.GatewayCharge;
import com.ledgerline.gateway.GatewayClient;
import com.ledgerline.payment.Payment;
import com.ledgerline.payment.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Compares one UTC day of payments with the gateway's settlement report and records every difference.
 * It reports; it never moves money.
 *
 * <p>Window: our payments created on day D, plus any payment the gateway's report for D mentions (a
 * payment created late on D-1 can be charged on D). Charges for D's payments that landed after
 * midnight are found in the report for D+1.
 */
@Service
public class ReconciliationService {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  private final GatewayClient gateway;
  private final PaymentRepository payments;
  private final LedgerIntegrityChecker integrity;
  private final JdbcClient jdbc;
  private final Json json;
  private final MeterRegistry metrics;
  private final TransactionTemplate tx;

  public ReconciliationService(GatewayClient gateway, PaymentRepository payments, LedgerIntegrityChecker integrity,
                               JdbcClient jdbc, Json json, MeterRegistry metrics, PlatformTransactionManager txm) {
    this.gateway = gateway;
    this.payments = payments;
    this.integrity = integrity;
    this.jdbc = jdbc;
    this.json = json;
    this.metrics = metrics;
    this.tx = new TransactionTemplate(txm);
  }

  public record RunSummary(UUID runId, LocalDate runDate, int paymentsChecked, int gatewayRecords, int mismatches,
                           int high, String status, boolean alreadyRan) {}

  public RunSummary run(LocalDate day) {
    Optional<RunSummary> existing = find(day);
    if (existing.isPresent()) return existing.get();       // one run per day

    Instant started = Instant.now();
    List<GatewayCharge> reportDay = gateway.settlements(day);
    List<GatewayCharge> reportNext = gateway.settlements(day.plusDays(1));

    OffsetDateTime from = day.atStartOfDay().atOffset(ZoneOffset.UTC);
    Map<UUID, Payment> ours = new LinkedHashMap<>();
    payments.findCreatedBetween(from, from.plusDays(1)).forEach(p -> ours.put(p.id(), p));
    Set<UUID> mentioned = new HashSet<>();
    for (GatewayCharge g : reportDay) parse(g.paymentId()).ifPresent(mentioned::add);
    mentioned.removeAll(ours.keySet());
    payments.findAll(mentioned).forEach(p -> ours.put(p.id(), p));

    Map<String, GatewayCharge> theirs = new LinkedHashMap<>();
    reportDay.forEach(g -> theirs.put(g.paymentId(), g));
    reportNext.stream().filter(g -> parse(g.paymentId()).map(ours::containsKey).orElse(false))
        .forEach(g -> theirs.putIfAbsent(g.paymentId(), g));

    List<Mismatch> found = new ArrayList<>(Reconciler.compare(
        ours.values().stream().map(p -> new Reconciler.Ours(p.id(), p.status(), p.amount(), p.currency(),
            p.createdAt().toInstant())).toList(),
        theirs.values(), started));
    found.addAll(integrity.check());

    UUID runId = UUID.randomUUID();
    long high = found.stream().filter(m -> m.severity() == MismatchType.Severity.HIGH).count();
    tx.executeWithoutResult(s -> {
      jdbc.sql("""
          INSERT INTO reconciliation_runs (id, run_date, started_at, finished_at, payments_checked, gateway_records, mismatches, status)
          VALUES (:id, :d, :s, now(), :pc, :gr, :m, :st)""")
          .param("id", runId).param("d", day).param("s", started.atOffset(ZoneOffset.UTC))
          .param("pc", ours.size()).param("gr", reportDay.size()).param("m", found.size())
          .param("st", high > 0 ? "ATTENTION" : "CLEAN").update();
      for (GatewayCharge g : reportDay) {
        jdbc.sql("""
            INSERT INTO gateway_settlements (report_date, charge_id, payment_id, status, amount, currency, charged_at, raw)
            VALUES (:d, :ch, :p, :st, :a, :c, :at, CAST(:raw AS jsonb)) ON CONFLICT DO NOTHING""")
            .param("d", day).param("ch", g.chargeId()).param("p", g.paymentId()).param("st", g.status())
            .param("a", g.amount()).param("c", g.currency()).param("at", g.chargedAt().atOffset(ZoneOffset.UTC))
            .param("raw", json.write(g)).update();
      }
      for (Mismatch m : found) {
        jdbc.sql("""
            INSERT INTO reconciliation_mismatches (run_id, payment_id, type, severity, ledger_status, gateway_status,
                                                   ledger_amount, gateway_amount, notes)
            VALUES (:r, :p, :t, :sev, :ls, :gs, :la, :ga, :n)""")
            .param("r", runId).param("p", m.paymentId()).param("t", m.type().name()).param("sev", m.severity().name())
            .param("ls", m.ledgerStatus()).param("gs", m.gatewayStatus()).param("la", m.ledgerAmount())
            .param("ga", m.gatewayAmount()).param("n", m.notes()).update();
      }
    });

    found.forEach(m -> metrics.counter("ledgerline.reconciliation.mismatches",
        "type", m.type().name(), "severity", m.severity().name()).increment());
    if (high > 0) log.error("reconciliation {}: {} HIGH severity mismatches of {}", day, high, found.size());
    else log.info("reconciliation {}: {} payments, {} gateway records, {} mismatches", day, ours.size(), reportDay.size(), found.size());
    return new RunSummary(runId, day, ours.size(), reportDay.size(), found.size(), (int) high,
        high > 0 ? "ATTENTION" : "CLEAN", false);
  }

  public Optional<RunSummary> find(LocalDate day) {
    return jdbc.sql("""
        SELECT r.id, r.run_date, r.payments_checked, r.gateway_records, r.mismatches, r.status,
               (SELECT COUNT(*) FROM reconciliation_mismatches m WHERE m.run_id = r.id AND m.severity = 'HIGH') AS high
        FROM reconciliation_runs r WHERE r.run_date = :d""")
        .param("d", day)
        .query((rs, i) -> new RunSummary(rs.getObject("id", UUID.class), rs.getObject("run_date", LocalDate.class),
            rs.getInt("payments_checked"), rs.getInt("gateway_records"), rs.getInt("mismatches"), rs.getInt("high"),
            rs.getString("status"), true))
        .optional();
  }

  public List<Mismatch> mismatches(UUID runId) {
    return jdbc.sql("""
        SELECT payment_id, type, severity, ledger_status, gateway_status, ledger_amount, gateway_amount, notes
        FROM reconciliation_mismatches WHERE run_id = :r ORDER BY id""")
        .param("r", runId).query(Mismatch.class).list();
  }

  private static Optional<UUID> parse(String s) {
    try { return Optional.of(UUID.fromString(s)); } catch (IllegalArgumentException e) { return Optional.empty(); }
  }
}
