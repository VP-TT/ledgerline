package com.ledgerline.gateway;

import com.ledgerline.common.ApiException;
import com.ledgerline.common.LedgerlineProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Checks {@code Gateway-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * The timestamp is signed too, and must be recent, so a captured request can't be replayed later;
 * the comparison is constant-time so the signature can't be guessed byte by byte.
 */
@Component
public class WebhookVerifier {
  private final byte[] secret;
  private final Duration tolerance;
  private final Clock clock;

  @Autowired
  public WebhookVerifier(LedgerlineProperties props) {
    this(props.gateway().webhookSecret(), props.gateway().webhookTolerance(), Clock.systemUTC());
  }

  WebhookVerifier(String secret, Duration tolerance, Clock clock) {
    if (secret == null || secret.isBlank()) throw new IllegalStateException("ledgerline.gateway.webhook-secret is required");
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.tolerance = tolerance;
    this.clock = clock;
  }

  public static class InvalidSignature extends ApiException {
    InvalidSignature(String detail) { super(HttpStatus.UNAUTHORIZED, "invalid-signature", detail); }
  }

  public void verify(String header, byte[] body) {
    if (header == null) throw new InvalidSignature("missing Gateway-Signature header");
    Long t = null;
    String v1 = null;
    for (String part : header.split(",")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length != 2) continue;
      if (kv[0].equals("t")) {
        try { t = Long.parseLong(kv[1]); } catch (NumberFormatException ignored) { }
      } else if (kv[0].equals("v1")) {
        v1 = kv[1];
      }
    }
    if (t == null || v1 == null) throw new InvalidSignature("malformed Gateway-Signature header");
    long age = Math.abs(clock.instant().getEpochSecond() - t);
    if (age > tolerance.toSeconds()) throw new InvalidSignature("signature timestamp outside tolerance");

    byte[] given;
    try {
      given = HexFormat.of().parseHex(v1);
    } catch (IllegalArgumentException e) {
      throw new InvalidSignature("malformed signature");
    }
    if (!MessageDigest.isEqual(hmac(t + ".", body), given)) throw new InvalidSignature("signature mismatch");
  }

  private byte[] hmac(String prefix, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update(prefix.getBytes(StandardCharsets.UTF_8));
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
