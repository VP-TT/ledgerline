package com.ledgerline.fakegateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A payment gateway you can tell to misbehave.
 *
 * <ul>
 *   <li>{@code POST /charges} with an {@code Idempotency-Key} header. The key is the charge's identity:
 *       a repeat returns the original charge and never charges twice.</li>
 *   <li>{@code GET /charges/{key}}: status check (200 or 404).</li>
 *   <li>{@code GET /settlements?date=YYYY-MM-DD}: the day's settlement report (by charge time, UTC).</li>
 *   <li>{@code POST /_control/config}, {@code POST /_control/reset}, {@code GET /_control/stats},
 *       {@code GET /_control/charges}: test controls.</li>
 * </ul>
 *
 * Faults are chosen per {@code POST /charges} call, either from the global {@link Faults} rates or from a
 * {@code ?mode=} query parameter ({@code succeed}, {@code decline}, {@code timeout_then_succeed},
 * {@code timeout_no_charge}, {@code error}, {@code slow}).
 */
public final class FakeGateway implements AutoCloseable {

  public enum Mode { SUCCEED, DECLINE, TIMEOUT_THEN_SUCCEED, TIMEOUT_NO_CHARGE, ERROR, SLOW }

  /**
   * Fault rates for {@code POST /charges}, each in [0, 1] and summing to at most 1. "Timeout" means the
   * gateway sleeps for {@code delayMs} before answering, which should exceed the client's read timeout.
   *
   * @param timeoutRate  charge is recorded, then the response is delayed (the dangerous case)
   * @param dropRate     response is delayed and the charge is never recorded
   * @param errorRate    503 without recording anything
   * @param declineRate  a DECLINED charge is recorded and 402 returned
   * @param statusErrors when true, {@code GET /charges/{key}} answers 503
   */
  public record Faults(double declineRate, double timeoutRate, double dropRate, double errorRate,
                       long delayMs, boolean statusErrors, Long seed) {
    public static Faults none() { return new Faults(0, 0, 0, 0, 10_000, false, null); }
    public static Faults always(Mode mode, long delayMs) {
      return switch (mode) {
        case SUCCEED, SLOW -> new Faults(0, 0, 0, 0, delayMs, false, null);
        case DECLINE -> new Faults(1, 0, 0, 0, delayMs, false, null);
        case TIMEOUT_THEN_SUCCEED -> new Faults(0, 1, 0, 0, delayMs, false, null);
        case TIMEOUT_NO_CHARGE -> new Faults(0, 0, 1, 0, delayMs, false, null);
        case ERROR -> new Faults(0, 0, 0, 1, delayMs, false, null);
      };
    }
    public Faults withStatusErrors(boolean on) {
      return new Faults(declineRate, timeoutRate, dropRate, errorRate, delayMs, on, seed);
    }
  }

  public record ChargeRequest(long amount, String currency, UUID billerId, String accountRef) {}

  public record Charge(String chargeId, String paymentId, String status, long amount, String currency,
                       UUID billerId, String accountRef, String declineReason, Instant chargedAt) {}

  public record Stats(int submitCalls, int charges, int approved, int declined, int duplicateSubmits) {}

  private static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final HttpServer server;
  private final Clock clock;
  private final Map<String, Charge> charges = new ConcurrentHashMap<>();
  private final AtomicInteger submitCalls = new AtomicInteger();
  private final AtomicInteger duplicateSubmits = new AtomicInteger();
  private volatile Faults faults = Faults.none();
  private volatile Random random = new Random();

  private FakeGateway(HttpServer server, Clock clock) {
    this.server = server;
    this.clock = clock;
  }

  public static FakeGateway start(int port) throws IOException {
    return start(port, Clock.systemUTC());
  }

  public static FakeGateway start(int port, Clock clock) throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 512);
    FakeGateway gw = new FakeGateway(http, clock);
    http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    http.createContext("/charges", gw::charges);
    http.createContext("/settlements", gw::settlements);
    http.createContext("/_control", gw::control);
    http.start();
    return gw;
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(Objects.requireNonNullElse(System.getenv("PORT"), "8090"));
    start(port);
    System.out.println("fake-gateway listening on :" + port);
  }

  public int port() { return server.getAddress().getPort(); }
  public String baseUrl() { return "http://localhost:" + port(); }

  // ---- programmatic controls (used in-process by the service's tests) ----

  public void configure(Faults f) {
    this.faults = f;
    this.random = f.seed() == null ? new Random() : new Random(f.seed());
  }

  public void reset() {
    charges.clear();
    submitCalls.set(0);
    duplicateSubmits.set(0);
    configure(Faults.none());
  }

  public List<Charge> charges() {
    return charges.values().stream().sorted(Comparator.comparing(Charge::chargedAt)).toList();
  }

  public Charge charge(String key) { return charges.get(key); }

  /** Records a charge the service never asked for (e.g. to test MISSING_IN_LEDGER). */
  public void injectCharge(Charge c) { charges.put(c.paymentId(), c); }

  public Stats stats() {
    var all = charges.values();
    int approved = (int) all.stream().filter(c -> c.status().equals("APPROVED")).count();
    return new Stats(submitCalls.get(), all.size(), approved, all.size() - approved, duplicateSubmits.get());
  }

  @Override public void close() { server.stop(0); }

  // ---- HTTP handlers ----

  private void charges(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      if (ex.getRequestMethod().equals("POST") && path.equals("/charges")) {
        submit(ex);
      } else if (ex.getRequestMethod().equals("GET") && path.startsWith("/charges/")) {
        if (faults.statusErrors()) { send(ex, 503, Map.of("error", "status unavailable")); return; }
        Charge c = charges.get(path.substring("/charges/".length()));
        if (c == null) send(ex, 404, Map.of("error", "no such charge"));
        else send(ex, 200, c);
      } else {
        send(ex, 404, Map.of("error", "not found"));
      }
    }
  }

  private void submit(HttpExchange ex) throws IOException {
    submitCalls.incrementAndGet();
    String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
    if (key == null || key.isBlank()) { send(ex, 400, Map.of("error", "Idempotency-Key required")); return; }
    ChargeRequest req = JSON.readValue(ex.getRequestBody(), ChargeRequest.class);
    Mode mode = pickMode(query(ex.getRequestURI()).get("mode"));

    switch (mode) {
      case ERROR -> { send(ex, 503, Map.of("error", "upstream unavailable")); return; }
      case TIMEOUT_NO_CHARGE -> { sleep(faults.delayMs()); send(ex, 504, Map.of("error", "dropped")); return; }
      case SLOW -> sleep(faults.delayMs());
      default -> { }
    }

    boolean[] created = {false};
    Charge c = charges.computeIfAbsent(key, k -> {
      created[0] = true;
      boolean decline = mode == Mode.DECLINE;
      return new Charge("ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), k,
          decline ? "DECLINED" : "APPROVED", req.amount(), req.currency(), req.billerId(), req.accountRef(),
          decline ? "card_declined" : null, Instant.now(clock));
    });
    if (!created[0]) {
      duplicateSubmits.incrementAndGet();
      if (c.amount() != req.amount() || !c.currency().equals(req.currency())) {
        send(ex, 422, Map.of("error", "Idempotency-Key reused with a different request"));
        return;
      }
    }
    if (mode == Mode.TIMEOUT_THEN_SUCCEED) sleep(faults.delayMs());   // charged, but the client gives up
    send(ex, c.status().equals("APPROVED") ? 200 : 402, c);
  }

  private void settlements(HttpExchange ex) throws IOException {
    try (ex) {
      String date = query(ex.getRequestURI()).get("date");
      if (date == null) { send(ex, 400, Map.of("error", "date required")); return; }
      LocalDate day = LocalDate.parse(date);
      List<Charge> rows = charges().stream()
          .filter(c -> c.chargedAt().atZone(ZoneOffset.UTC).toLocalDate().equals(day))
          .toList();
      send(ex, 200, rows);
    }
  }

  private void control(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      switch (ex.getRequestMethod() + " " + path) {
        case "POST /_control/config" -> {
          configure(JSON.readValue(ex.getRequestBody(), Faults.class));
          send(ex, 200, faults);
        }
        case "POST /_control/reset" -> { reset(); send(ex, 200, stats()); }
        case "GET /_control/stats" -> send(ex, 200, stats());
        case "GET /_control/charges" -> send(ex, 200, charges());
        default -> send(ex, 404, Map.of("error", "not found"));
      }
    }
  }

  private Mode pickMode(String override) {
    if (override != null) return Mode.valueOf(override.toUpperCase());
    Faults f = faults;
    double r;
    synchronized (this) { r = random.nextDouble(); }
    if ((r -= f.declineRate()) < 0) return Mode.DECLINE;
    if ((r -= f.timeoutRate()) < 0) return Mode.TIMEOUT_THEN_SUCCEED;
    if ((r -= f.dropRate()) < 0) return Mode.TIMEOUT_NO_CHARGE;
    if ((r -= f.errorRate()) < 0) return Mode.ERROR;
    return Mode.SUCCEED;
  }

  private static void send(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    try {
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
    } catch (IOException clientGone) {
      // the client timed out and closed the connection; that's the point of the timeout modes
    }
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> out = new HashMap<>();
    if (uri.getRawQuery() == null) return out;
    for (String pair : uri.getRawQuery().split("&")) {
      int i = pair.indexOf('=');
      if (i > 0) out.put(pair.substring(0, i), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  /** Lists every charge for a set of payment ids; handy in assertions. */
  public List<Charge> chargesFor(java.util.Collection<UUID> paymentIds) {
    List<Charge> out = new ArrayList<>();
    for (UUID id : paymentIds) {
      Charge c = charges.get(id.toString());
      if (c != null) out.add(c);
    }
    return out;
  }
}
