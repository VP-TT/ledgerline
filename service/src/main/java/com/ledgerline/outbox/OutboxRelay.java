package com.ledgerline.outbox;

import com.ledgerline.common.Backoff;
import com.ledgerline.common.LedgerlineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drains the outbox: claims ready events, runs their handlers, then marks them DONE, reschedules them
 * with backoff, or marks them DEAD after {@code maxAttempts}. Events in a batch run in parallel on
 * virtual threads (at most {@code concurrency} at a time), since each one is independent and most of
 * the time is spent waiting on the gateway.
 */
@Component
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository repo;
  private final Map<String, OutboxHandler> handlers;
  private final LedgerlineProperties.Outbox config;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  /** Caps events in flight so the relay can't take the whole connection pool from the API. */
  private final Semaphore permits;

  public OutboxRelay(OutboxRepository repo, List<OutboxHandler> handlers, LedgerlineProperties props) {
    this.repo = repo;
    this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::eventType, Function.identity()));
    this.config = props.outbox();
    this.permits = new Semaphore(config.concurrency());
  }

  /** One pass: claim a batch and process it. Returns the number of events claimed. */
  public int drain() {
    List<OutboxEvent> batch = repo.claim(config.batchSize());
    if (batch.isEmpty()) return 0;
    List<Callable<Void>> tasks = batch.stream().<Callable<Void>>map(e -> () -> {
      permits.acquire();
      try {
        process(e);
      } finally {
        permits.release();
      }
      return null;
    }).toList();
    try {
      for (var f : workers.invokeAll(tasks)) f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      log.error("outbox worker crashed", e.getCause());
    }
    return batch.size();
  }

  /**
   * Drains back-to-back while batches come back full, so a backlog is worked off at full speed instead
   * of one batch per poll interval. Returns once a batch is smaller than batchSize (the queue is caught up).
   */
  public int drainWhileBusy() {
    int total = 0, n;
    do {
      n = drain();
      total += n;
    } while (n == config.batchSize() && !Thread.currentThread().isInterrupted());
    return total;
  }

  void process(OutboxEvent e) {
    OutboxHandler handler = handlers.get(e.eventType());
    if (handler == null) {
      repo.markDead(e.id(), "no handler for " + e.eventType());
      return;
    }
    try {
      handler.handle(e);
      repo.markDone(e.id());
    } catch (RetryableException ex) {
      repo.reschedule(e.id(), Backoff.exponential(config.retryBase(), e.attempts(), config.retryMax()), ex.getMessage());
    } catch (RuntimeException ex) {
      if (e.attempts() >= config.maxAttempts()) {
        log.error("outbox event {} ({}) is DEAD after {} attempts", e.id(), e.eventType(), e.attempts(), ex);
        repo.markDead(e.id(), ex.toString());
      } else {
        log.warn("outbox event {} ({}) failed, attempt {}: {}", e.id(), e.eventType(), e.attempts(), ex.toString());
        repo.reschedule(e.id(), Backoff.exponential(config.retryBase(), e.attempts(), config.retryMax()), ex.toString());
      }
    }
  }

  public int sweepStuck() {
    int n = repo.resetStuck(config.stuckAfter());
    if (n > 0) log.warn("reset {} outbox events stuck in PROCESSING", n);
    return n;
  }

  /**
   * Drains until nothing is NEW or PROCESSING, waiting for scheduled events to come due. Used by tests
   * and the admin endpoint; returns false if work remains after {@code timeout}.
   */
  public boolean drainUntilIdle(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (drain() > 0) continue;
      var backlog = repo.backlog();
      if (backlog.pending() == 0) return true;
      double wait = backlog.nextDueInSeconds() == null ? 0.02 : backlog.nextDueInSeconds();
      sleep(Duration.ofMillis(Math.min(200, Math.max(5, (long) (wait * 1000)))));
    }
    return repo.backlog().pending() == 0;
  }

  private static void sleep(Duration d) {
    try { Thread.sleep(d); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
