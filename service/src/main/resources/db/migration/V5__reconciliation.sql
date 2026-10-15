CREATE TABLE reconciliation_runs (
  id               UUID PRIMARY KEY,
  run_date         DATE NOT NULL UNIQUE,      -- one run per day: re-running is a no-op
  started_at       TIMESTAMPTZ NOT NULL,
  finished_at      TIMESTAMPTZ,
  payments_checked INT NOT NULL DEFAULT 0,
  gateway_records  INT NOT NULL DEFAULT 0,
  mismatches       INT NOT NULL DEFAULT 0,
  status           TEXT NOT NULL
);

CREATE TABLE reconciliation_mismatches (
  id             BIGSERIAL PRIMARY KEY,
  run_id         UUID NOT NULL REFERENCES reconciliation_runs(id),
  payment_id     UUID,
  type           TEXT NOT NULL,
  severity       TEXT NOT NULL CHECK (severity IN ('HIGH','MEDIUM','LOW')),
  ledger_status  TEXT,
  gateway_status TEXT,
  ledger_amount  BIGINT,
  gateway_amount BIGINT,
  resolved       BOOLEAN NOT NULL DEFAULT false,
  notes          TEXT
);
CREATE INDEX reconciliation_mismatches_run ON reconciliation_mismatches (run_id);

-- Raw copy of the gateway's settlement report, as received.
CREATE TABLE gateway_settlements (
  report_date DATE NOT NULL,
  charge_id   TEXT NOT NULL,
  payment_id  TEXT NOT NULL,
  status      TEXT NOT NULL,
  amount      BIGINT NOT NULL,
  currency    CHAR(3) NOT NULL,
  charged_at  TIMESTAMPTZ NOT NULL,
  raw         JSONB NOT NULL,
  PRIMARY KEY (report_date, charge_id)
);

-- ShedLock: only one instance runs the nightly job.
CREATE TABLE shedlock (
  name       VARCHAR(64) PRIMARY KEY,
  lock_until TIMESTAMPTZ NOT NULL,
  locked_at  TIMESTAMPTZ NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
