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
 *   <li>{@code POST /refunds} with an {@code Idempotency-Key}: refund part or all of an approved charge
 *       (declined if it would refund more than was charged); {@code GET /refunds/{key}}: status check.</li>
 *   <li>{@code POST /_control/config}, {@code POST /_control/webhooks}, {@code POST /_control/reset},
 *       {@code GET /_control/stats}, {@code GET /_control/charges}: test controls.</li>
 * </ul>
 *
 * When webhooks are configured, every new charge is also pushed to the webhook URL as
 * {@code charge.approved} / {@code charge.declined}, signed Stripe-style:
 * {@code Gateway-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. Duplicate
 * deliveries reuse the same event id, as a real gateway's retries would.
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

  public record Stats(int submitCalls, int charges, int approved, int declined, int duplicateSubmits,
                      int statusCalls, int webhooksSent, int refunds) {}

  public record RefundRequest(String paymentId, long amount, String currency) {}

  public record Refund(String refundId, String key, String paymentId, String status, long amount, String currency,
                       String declineReason, Instant refundedAt) {}

  /**
   * Webhook delivery settings. {@code deliveries} > 1 sends the same event that many times; {@code delayMs}
   * waits before the first delivery. A non-2xx answer is retried up to 3 times.
   */
  public record Webhooks(String url, String secret, long delayMs, int deliveries) {
    public static Webhooks off() { return new Webhooks(null, null, 0, 1); }
  }

  public record WebhookEvent(String eventId, String type, Charge charge) {}

  private static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final HttpServer server;
  private final Clock clock;
  private final Map<String, Charge> charges = new ConcurrentHashMap<>();
  private final Map<String, Refund> refunds = new ConcurrentHashMap<>();
  private final AtomicInteger submitCalls = new AtomicInteger();
  private final AtomicInteger duplicateSubmits = new AtomicInteger();
  private final AtomicInteger statusCalls = new AtomicInteger();
  private final AtomicInteger webhooksSent = new AtomicInteger();
  private final java.net.http.HttpClient webhookClient = java.net.http.HttpClient.newBuilder()
      .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(2)).build();
  private volatile Webhooks webhooks = Webhooks.off();
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
    http.createContext("/refunds", gw::refunds);
    http.createContext("/_control", gw::control);
    http.start();
    return gw;
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(Objects.requireNonNullElse(System.getenv("PORT"), "8090"));
    FakeGateway gw = start(port);
    if (System.getenv("WEBHOOK_URL") != null) {
      gw.configureWebhooks(new Webhooks(System.getenv("WEBHOOK_URL"), System.getenv("WEBHOOK_SECRET"), 0, 1));
    }
    System.out.println("fake-gateway listening on :" + port);
  }

  public int port() { return server.getAddress().getPort(); }
  public String baseUrl() { return "http://localhost:" + port(); }

  // ---- programmatic controls (used in-process by the service's tests) ----

  public void configure(Faults f) {
    this.faults = f;
    this.random = f.seed() == null ? new Random() : new Random(f.seed());
  }

  public void configureWebhooks(Webhooks w) { this.webhooks = w; }

  public void reset() {
    charges.clear();
    refunds.clear();
    submitCalls.set(0);
    duplicateSubmits.set(0);
    statusCalls.set(0);
    webhooksSent.set(0);
    configure(Faults.none());
    configureWebhooks(Webhooks.off());
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
    return new Stats(submitCalls.get(), all.size(), approved, all.size() - approved, duplicateSubmits.get(),
        statusCalls.get(), webhooksSent.get(), refunds.size());
  }

  @Override public void close() { server.stop(0); }

  // ---- HTTP handlers ----

  private void charges(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      if (ex.getRequestMethod().equals("POST") && path.equals("/charges")) {
        submit(ex);
      } else if (ex.getRequestMethod().equals("GET") && path.startsWith("/charges/")) {
        statusCalls.incrementAndGet();
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
    if (created[0]) sendWebhook(c);
    else {
      duplicateSubmits.incrementAndGet();
      if (c.amount() != req.amount() || !c.currency().equals(req.currency())) {
        send(ex, 422, Map.of("error", "Idempotency-Key reused with a different request"));
        return;
      }
    }
    if (mode == Mode.TIMEOUT_THEN_SUCCEED) sleep(faults.delayMs());   // charged, but the client gives up
    send(ex, c.status().equals("APPROVED") ? 200 : 402, c);
  }

  public List<Refund> refunds() {
    return refunds.values().stream().sorted(Comparator.comparing(Refund::refundedAt)).toList();
  }

  public Refund refund(String key) { return refunds.get(key); }

  private void refunds(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      if (ex.getRequestMethod().equals("GET") && path.startsWith("/refunds/")) {
        statusCalls.incrementAndGet();
        if (faults.statusErrors()) { send(ex, 503, Map.of("error", "status unavailable")); return; }
        Refund r = refunds.get(path.substring("/refunds/".length()));
        if (r == null) send(ex, 404, Map.of("error", "no such refund"));
        else send(ex, 200, r);
        return;
      }
      if (!ex.getRequestMethod().equals("POST") || !path.equals("/refunds")) { send(ex, 404, Map.of("error", "not found")); return; }

      String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
      if (key == null || key.isBlank()) { send(ex, 400, Map.of("error", "Idempotency-Key required")); return; }
      RefundRequest req = JSON.readValue(ex.getRequestBody(), RefundRequest.class);
      Mode mode = pickMode(query(ex.getRequestURI()).get("mode"));
      switch (mode) {
        case ERROR -> { send(ex, 503, Map.of("error", "upstream unavailable")); return; }
        case TIMEOUT_NO_CHARGE -> { sleep(faults.delayMs()); send(ex, 504, Map.of("error", "dropped")); return; }
        case SLOW -> sleep(faults.delayMs());
        default -> { }
      }
      Refund r;
      synchronized (refunds) {      // the "never refund more than was charged" check must not race itself
        r = refunds.get(key);
        if (r == null) {
          Charge c = charges.get(req.paymentId());
          long already = refunds.values().stream()
              .filter(x -> x.paymentId().equals(req.paymentId()) && x.status().equals("APPROVED"))
              .mapToLong(Refund::amount).sum();
          String reason = mode == Mode.DECLINE ? "refund_declined"
              : c == null || !c.status().equals("APPROVED") ? "no_approved_charge"
              : already + req.amount() > c.amount() ? "exceeds_charge" : null;
          r = new Refund("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), key, req.paymentId(),
              reason == null ? "APPROVED" : "DECLINED", req.amount(), req.currency(), reason, Instant.now(clock));
          refunds.put(key, r);
        }
      }
      if (mode == Mode.TIMEOUT_THEN_SUCCEED) sleep(faults.delayMs());
      send(ex, r.status().equals("APPROVED") ? 200 : 402, r);
    }
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
        case "POST /_control/webhooks" -> {
          configureWebhooks(JSON.readValue(ex.getRequestBody(), Webhooks.class));
          send(ex, 200, webhooks);
        }
        case "POST /_control/reset" -> { reset(); send(ex, 200, stats()); }
        case "GET /_control/stats" -> send(ex, 200, stats());
        case "GET /_control/charges" -> send(ex, 200, charges());
        default -> send(ex, 404, Map.of("error", "not found"));
      }
    }
  }

  /** Pushes the charge's outcome to the webhook URL on a background virtual thread. */
  private void sendWebhook(Charge c) {
    Webhooks w = webhooks;
    if (w.url() == null) return;
    var event = new WebhookEvent("evt_" + c.chargeId(),
        c.status().equals("APPROVED") ? "charge.approved" : "charge.declined", c);
    Thread.ofVirtual().start(() -> {
      sleep(w.delayMs());
      for (int i = 0; i < Math.max(1, w.deliveries()); i++) deliver(w, event);
    });
  }

  private void deliver(Webhooks w, WebhookEvent event) {
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        String body = JSON.writeValueAsString(event);
        long t = Instant.now(clock).getEpochSecond();
        var res = webhookClient.send(java.net.http.HttpRequest.newBuilder(URI.create(w.url()))
                .header("Content-Type", "application/json")
                .header("Gateway-Signature", "t=" + t + ",v1=" + sign(w.secret(), t + "." + body))
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
            java.net.http.HttpResponse.BodyHandlers.discarding());
        webhooksSent.incrementAndGet();
        if (res.statusCode() / 100 == 2) return;
      } catch (IOException e) {
        // receiver down: retry
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      sleep(100L * attempt);
    }
  }

  /** Hex HMAC-SHA256, the signature scheme receivers verify. */
  public static String sign(String secret, String payload) {
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
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
