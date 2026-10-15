package com.ledgerline.idempotency;

import com.ledgerline.common.ApiException;
import com.ledgerline.common.Json;
import com.ledgerline.common.LedgerlineProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a write at most once per (client, Idempotency-Key).
 *
 * <p>The key row, the work and the stored response share one READ COMMITTED transaction. Either all
 * of it commits (and later repeats replay the stored response) or none of it does (and a repeat runs
 * the work again). A business failure such as insufficient funds is therefore not cached: retrying
 * it re-evaluates, which is what a client wants after topping up.
 */
@Service
public class IdempotencyService {
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private final IdempotencyRepository repo;
  private final TransactionTemplate tx;
  private final Json json;
  private final Duration lockTimeout;

  public IdempotencyService(IdempotencyRepository repo, PlatformTransactionManager txm, Json json,
                            LedgerlineProperties props) {
    this.repo = repo;
    this.json = json;
    this.lockTimeout = props.idempotency().lockTimeout();
    this.tx = new TransactionTemplate(txm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public record Result(HttpStatus status, Object body, UUID resourceId) {
    public static Result of(HttpStatus status, Object body, UUID resourceId) { return new Result(status, body, resourceId); }
  }

  public ResponseEntity<?> execute(String clientId, String key, String route, Object body, Supplier<Result> work) {
    if (key == null || key.isBlank() || key.length() > 255)
      throw new ApiException.BadRequest("invalid-idempotency-key", "Idempotency-Key must be 1-255 characters");
    String hash = sha256(route + "\n" + json.write(body));
    return tx.execute(status -> {
      boolean inserted;
      try {
        inserted = repo.tryInsert(clientId, key, hash, lockTimeout);
      } catch (DataAccessException e) {
        if (isLockTimeout(e)) throw new IdempotencyInProgressException(key);
        throw e;
      }
      if (!inserted) return replayOrReject(clientId, key, hash);

      Result r = work.get();                       // same transaction
      String stored = json.write(r.body());
      repo.complete(clientId, key, r.status().value(), stored, r.resourceId());
      // send the stored text itself, so a replay is byte-for-byte what the first caller got
      return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(stored);
    });
  }

  private ResponseEntity<?> replayOrReject(String clientId, String key, String hash) {
    var row = repo.find(clientId, key).orElseThrow(() -> new IdempotencyInProgressException(key));
    if (!row.requestHash().equals(hash))
      throw new ApiException.Unprocessable("idempotency-key-reused",
          "Idempotency-Key " + key + " was already used with a different request");
    if (!row.status().equals("COMPLETED")) throw new IdempotencyInProgressException(key);
    // the exact bytes we sent the first time
    return ResponseEntity.status(row.responseCode()).header(REPLAYED_HEADER, "true")
        .contentType(MediaType.APPLICATION_JSON).body(row.responseBody());
  }

  /** Postgres lock_not_available: the other request with this key hasn't committed within lockTimeout. */
  private static boolean isLockTimeout(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) return true;
    }
    return false;
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
