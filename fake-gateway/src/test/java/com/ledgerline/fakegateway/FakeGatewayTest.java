package com.ledgerline.fakegateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeGatewayTest {

  static FakeGateway gw;
  static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  static final String BODY = """
      {"amount":5000,"currency":"INR","billerId":"00000000-0000-0000-0000-0000000000b1","accountRef":"A1"}""";

  @BeforeAll static void start() throws Exception { gw = FakeGateway.start(0); }
  @AfterAll static void stop() { gw.close(); }
  @BeforeEach void reset() { gw.reset(); }

  HttpResponse<String> charge(String key, String query, Duration timeout) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(gw.baseUrl() + "/charges" + query))
        .header("Idempotency-Key", key).header("Content-Type", "application/json")
        .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(gw.baseUrl() + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test void sameKeyChargesOnce() throws Exception {
    var first = charge("p1", "", Duration.ofSeconds(2));
    var second = charge("p1", "", Duration.ofSeconds(2));
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(gw.stats()).isEqualTo(new FakeGateway.Stats(2, 1, 1, 0, 1));
  }

  @Test void declineIsRecordedAndSticky() throws Exception {
    assertThat(charge("p2", "?mode=decline", Duration.ofSeconds(2)).statusCode()).isEqualTo(402);
    assertThat(charge("p2", "", Duration.ofSeconds(2)).statusCode()).isEqualTo(402);
    assertThat(gw.charge("p2").status()).isEqualTo("DECLINED");
  }

  @Test void timeoutThenSucceedChargesEvenThoughClientGaveUp() throws Exception {
    gw.configure(FakeGateway.Faults.always(FakeGateway.Mode.TIMEOUT_THEN_SUCCEED, 500));
    assertThatThrownBy(() -> charge("p3", "", Duration.ofMillis(100))).isInstanceOf(HttpTimeoutException.class);
    assertThat(get("/charges/p3").statusCode()).isEqualTo(200);
  }

  @Test void droppedRequestLeavesNoCharge() throws Exception {
    gw.configure(FakeGateway.Faults.always(FakeGateway.Mode.TIMEOUT_NO_CHARGE, 300));
    assertThatThrownBy(() -> charge("p4", "", Duration.ofMillis(100))).isInstanceOf(HttpTimeoutException.class);
    Thread.sleep(400);
    assertThat(get("/charges/p4").statusCode()).isEqualTo(404);
  }

  @Test void settlementReportListsTodaysCharges() throws Exception {
    charge("p5", "", Duration.ofSeconds(2));
    var today = LocalDate.now(ZoneOffset.UTC);
    assertThat(get("/settlements?date=" + today).body()).contains("\"paymentId\":\"p5\"");
    assertThat(get("/settlements?date=" + today.minusDays(1)).body()).isEqualTo("[]");
  }
}
