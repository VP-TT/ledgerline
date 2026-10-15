package com.ledgerline.reconciliation;

import com.ledgerline.common.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Admin endpoints for operators. In production these would sit behind separate auth. */
@RestController
public class ReconciliationController {
  private final ReconciliationService service;

  public ReconciliationController(ReconciliationService service) { this.service = service; }

  public record RunReport(ReconciliationService.RunSummary run, List<Mismatch> mismatches) {}

  @PostMapping("/v1/admin/reconciliation/runs")
  public ReconciliationService.RunSummary run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return service.run(date);
  }

  @GetMapping("/v1/admin/reconciliation/runs/{date}")
  public RunReport get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    var run = service.find(date).orElseThrow(() -> new ApiException.NotFound("Reconciliation run", date));
    return new RunReport(run, service.mismatches(run.runId()));
  }
}
