package com.ledgerline.idempotency;

import com.ledgerline.common.ApiException;

/** Another request with the same key is still running; the client should retry after a moment. */
public class IdempotencyInProgressException extends ApiException.Conflict {
  public IdempotencyInProgressException(String key) {
    super("idempotency-in-progress", "A request with Idempotency-Key " + key + " is still in progress");
  }
}
