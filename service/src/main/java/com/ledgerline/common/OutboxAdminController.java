package com.ledgerline.common;

import com.ledgerline.outbox.OutboxRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OutboxAdminController {
  private final OutboxRepository outbox;

  public OutboxAdminController(OutboxRepository outbox) { this.outbox = outbox; }

  @GetMapping("/v1/admin/outbox")
  public OutboxRepository.Backlog backlog() { return outbox.backlog(); }
}
