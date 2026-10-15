CREATE TABLE outbox_events (
  id             UUID PRIMARY KEY,
  aggregate_type TEXT NOT NULL,          -- 'PAYMENT'
  aggregate_id   UUID NOT NULL,
  event_type     TEXT NOT NULL,          -- 'PAYMENT_SUBMIT_REQUESTED', 'PAYMENT_STATUS_CHECK'
  payload        JSONB NOT NULL,
  status         TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','PROCESSING','DONE','DEAD')),
  attempts       INT NOT NULL DEFAULT 0,
  available_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_at     TIMESTAMPTZ,
  last_error     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at   TIMESTAMPTZ
);
CREATE INDEX outbox_ready ON outbox_events (available_at) WHERE status = 'NEW';
CREATE INDEX outbox_processing ON outbox_events (claimed_at) WHERE status = 'PROCESSING';
CREATE INDEX outbox_aggregate ON outbox_events (aggregate_id);
