package com.ledgerline.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** A plain HTTP client for the running app, so tests exercise the real controllers, filters and JSON. */
public final class Api {
  public static final UUID ELECTRICITY_INR = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  public static final UUID WATER_INR = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  public static final UUID ELECTRICITY_USD = UUID.fromString("00000000-0000-0000-0000-0000000000b5");
  public static final String CLIENT = "test-client";

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final String base;

  public Api(int port) { this.base = "http://localhost:" + port; }

  public record Response(int status, JsonNode body, boolean replayed, String retryAfter) {
    public String text(String field) { return body.path(field).asText(); }
    public UUID uuid(String field) { return UUID.fromString(text(field)); }
    public String problemType() {
      String t = text("type");
      return t.substring(t.lastIndexOf('/') + 1);
    }
  }

  public Response post(String path, String key, Object body) {
    try {
      var req = HttpRequest.newBuilder(URI.create(base + path))
          .header("Content-Type", "application/json")
          .header("X-Client-Id", CLIENT)
          .timeout(Duration.ofSeconds(60))
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      if (key != null) req.header("Idempotency-Key", key);
      return send(req.build());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** POST that retries 409 "in progress" the way a well-behaved client would. */
  public Response postRetrying(String path, String key, Object body) {
    for (int i = 0; ; i++) {
      Response r = post(path, key, body);
      if (r.status() != 409 || !"idempotency-in-progress".equals(r.problemType()) || i > 50) return r;
      sleep(20 + ThreadLocalRandom.current().nextInt(50));
    }
  }

  public Response get(String path) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build());
  }

  private Response send(HttpRequest req) {
    try {
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      JsonNode body = res.body().isEmpty() ? JSON.nullNode() : JSON.readTree(res.body());
      return new Response(res.statusCode(), body,
          res.headers().firstValue("Idempotent-Replayed").isPresent(),
          res.headers().firstValue("Retry-After").orElse(null));
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  // ---- domain helpers ----

  public UUID createWallet(String userId, String currency) {
    Response r = post("/v1/wallets", "w-" + UUID.randomUUID(), Map.of("userId", userId, "currency", currency));
    if (r.status() != 201) throw new AssertionError("create wallet: " + r);
    return r.uuid("walletId");
  }

  public Response topUp(UUID wallet, String amount) {
    return post("/v1/wallets/" + wallet + "/topups", "t-" + UUID.randomUUID(), Map.of("amount", new BigDecimal(amount)));
  }

  public UUID walletWithBalance(String currency, String amount) {
    UUID w = createWallet("user-" + UUID.randomUUID(), currency);
    if (topUp(w, amount).status() != 201) throw new AssertionError("top up failed");
    return w;
  }

  public static Map<String, Object> payment(UUID wallet, UUID biller, String amount, String currency) {
    return Map.of("walletId", wallet, "billerId", biller, "accountRef", "ACC-1001",
        "amount", new BigDecimal(amount), "currency", currency);
  }

  public Response pay(UUID wallet, String amount, String key) {
    return post("/v1/payments", key, payment(wallet, ELECTRICITY_INR, amount, "INR"));
  }

  public BigDecimal balance(UUID wallet) {
    return new BigDecimal(get("/v1/wallets/" + wallet + "/balance").text("balance"));
  }

  public long points(UUID wallet) {
    return get("/v1/wallets/" + wallet + "/balance").body().path("points").asLong();
  }

  public String paymentStatus(UUID payment) {
    return get("/v1/payments/" + payment).text("status");
  }

  private static void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
