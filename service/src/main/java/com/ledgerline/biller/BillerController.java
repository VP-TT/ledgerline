package com.ledgerline.biller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class BillerController {
  private final BillerRepository billers;

  public BillerController(BillerRepository billers) { this.billers = billers; }

  public record BillerView(UUID id, String code, String name, String category, String currency) {}

  @GetMapping("/v1/billers")
  public List<BillerView> list() {
    return billers.findActive().stream()
        .map(b -> new BillerView(b.id(), b.code(), b.name(), b.category(), b.currency())).toList();
  }
}
