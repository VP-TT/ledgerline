-- Every webhook event we accepted, keyed by the gateway's event id. The insert happens in the same
-- transaction as the payment transition it causes, so a redelivered event is a no-op.
CREATE TABLE gateway_webhook_events (
  event_id    TEXT PRIMARY KEY,
  type        TEXT NOT NULL,
  payment_id  TEXT NOT NULL,
  outcome     TEXT NOT NULL,        -- APPLIED_SUCCEEDED, APPLIED_REVERSED, IGNORED_<status>, UNKNOWN_PAYMENT, AMOUNT_MISMATCH
  payload     TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
