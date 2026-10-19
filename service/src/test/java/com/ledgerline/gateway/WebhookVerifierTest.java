package com.ledgerline.gateway;

import com.ledgerline.fakegateway.FakeGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookVerifierTest {
  static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
  static final String SECRET = "s3cret";
  static final String BODY = "{\"eventId\":\"evt_1\",\"type\":\"charge.approved\"}";
  final WebhookVerifier verifier = new WebhookVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

  static String header(String secret, long t, String body) {
    return "t=" + t + ",v1=" + FakeGateway.sign(secret, t + "." + body);
  }

  void verify(String header, String body) {
    verifier.verify(header, body.getBytes(StandardCharsets.UTF_8));
  }

  @Test void acceptsAValidSignature() {
    assertThatCode(() -> verify(header(SECRET, NOW.getEpochSecond(), BODY), BODY)).doesNotThrowAnyException();
    assertThatCode(() -> verify(header(SECRET, NOW.getEpochSecond() - 299, BODY), BODY)).doesNotThrowAnyException();
  }

  @Test void rejectsATamperedBody() {
    String h = header(SECRET, NOW.getEpochSecond(), BODY);
    assertThatThrownBy(() -> verify(h, BODY.replace("approved", "declined"))).hasMessageContaining("mismatch");
  }

  @Test void rejectsTheWrongSecret() {
    assertThatThrownBy(() -> verify(header("other", NOW.getEpochSecond(), BODY), BODY)).hasMessageContaining("mismatch");
  }

  @Test void rejectsReplaysOfOldSignatures() {
    assertThatThrownBy(() -> verify(header(SECRET, NOW.getEpochSecond() - 301, BODY), BODY)).hasMessageContaining("tolerance");
    assertThatThrownBy(() -> verify(header(SECRET, NOW.getEpochSecond() + 301, BODY), BODY)).hasMessageContaining("tolerance");
  }

  @Test void rejectsATimestampSwappedIntoAValidSignature() {
    // the timestamp is part of what's signed, so it can't be refreshed to dodge the tolerance check
    long old = NOW.getEpochSecond() - 3600;
    String refreshed = header(SECRET, old, BODY).replace("t=" + old, "t=" + NOW.getEpochSecond());
    assertThatThrownBy(() -> verify(refreshed, BODY)).hasMessageContaining("mismatch");
  }

  @Test void rejectsMissingOrMalformedHeaders() {
    assertThatThrownBy(() -> verify(null, BODY)).hasMessageContaining("missing");
    assertThatThrownBy(() -> verify("garbage", BODY)).hasMessageContaining("malformed");
    assertThatThrownBy(() -> verify("t=abc,v1=00", BODY)).hasMessageContaining("malformed");
    assertThatThrownBy(() -> verify("t=" + NOW.getEpochSecond() + ",v1=zz", BODY)).hasMessageContaining("malformed");
  }
}
