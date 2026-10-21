-- Refunds of SUCCEEDED payments, possibly partial. Money moves (biller_payable -> wallet) only when the
-- gateway confirms; a refund that is in flight or UNKNOWN still counts against the refundable amount.
CREATE TABLE refunds (
  id                 UUID PRIMARY KEY,
  payment_id         UUID NOT NULL REFERENCES payments(id),
  amount             BIGINT NOT NULL CHECK (amount > 0),
  currency           CHAR(3) NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('PENDING','SUBMITTED','UNKNOWN','SUCCEEDED','FAILED')),
  gateway_ref        TEXT UNIQUE,
  attempts           INT NOT NULL DEFAULT 0,
  status_checks      INT NOT NULL DEFAULT 0,
  next_attempt_at    TIMESTAMPTZ,
  failure_reason     TEXT,
  points_clawed_back BIGINT NOT NULL DEFAULT 0,  -- reward points taken back with this refund
  points_shortfall   BIGINT NOT NULL DEFAULT 0,  -- points owed back but already spent (balance can't go negative)
  idempotency_key    TEXT NOT NULL,
  version            BIGINT NOT NULL DEFAULT 0,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refunds_payment_idx ON refunds (payment_id);
