CREATE TABLE billers (
  id                 UUID PRIMARY KEY,
  code               TEXT NOT NULL UNIQUE,
  name               TEXT NOT NULL,
  category           TEXT NOT NULL,       -- ELECTRICITY, WATER, MOBILE, BROADBAND, ...
  currency           CHAR(3) NOT NULL,
  payable_account_id UUID NOT NULL REFERENCES accounts(id),
  active             BOOLEAN NOT NULL DEFAULT true
);

WITH seed(id, code, name, category, currency) AS (VALUES
  ('00000000-0000-0000-0000-0000000000b1'::uuid, 'tsspdcl',     'TS Southern Power',     'ELECTRICITY', 'INR'),
  ('00000000-0000-0000-0000-0000000000b2'::uuid, 'hmwssb',      'Hyderabad Metro Water', 'WATER',       'INR'),
  ('00000000-0000-0000-0000-0000000000b3'::uuid, 'jio-postpaid','Jio Postpaid',          'MOBILE',      'INR'),
  ('00000000-0000-0000-0000-0000000000b4'::uuid, 'act-fibernet','ACT Fibernet',          'BROADBAND',   'INR'),
  ('00000000-0000-0000-0000-0000000000b5'::uuid, 'pge',         'Pacific Gas & Electric','ELECTRICITY', 'USD')
), accts AS (
  INSERT INTO accounts (id, code, type, currency, allow_negative, system)
  SELECT gen_random_uuid(), 'biller_payable:' || code, 'LIABILITY', currency, true, true FROM seed
  RETURNING id, code
)
INSERT INTO billers (id, code, name, category, currency, payable_account_id)
SELECT s.id, s.code, s.name, s.category, s.currency, a.id
FROM seed s JOIN accts a ON a.code = 'biller_payable:' || s.code;

CREATE TABLE wallets (
  id                UUID PRIMARY KEY,
  user_id           TEXT NOT NULL,
  currency          CHAR(3) NOT NULL,
  account_id        UUID NOT NULL UNIQUE REFERENCES accounts(id),
  points_account_id UUID NOT NULL REFERENCES accounts(id),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, currency)
);

CREATE TABLE topups (
  id              UUID PRIMARY KEY,
  wallet_id       UUID NOT NULL REFERENCES wallets(id),
  amount          BIGINT NOT NULL CHECK (amount > 0),
  currency        CHAR(3) NOT NULL,
  idempotency_key TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per (client, key). The row is inserted and completed inside the same transaction as the
-- work it protects, so other transactions only ever see COMPLETED rows; a concurrent duplicate
-- blocks on the primary key until the first commits (then replays) or rolls back (then runs).
CREATE TABLE idempotency_keys (
  key           TEXT NOT NULL,
  client_id     TEXT NOT NULL,
  request_hash  TEXT NOT NULL,              -- SHA-256 of method + path + body
  status        TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_code INT,
  response_body TEXT,                       -- exact bytes of the first response, replayed verbatim
  resource_id   UUID,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (client_id, key)
);

CREATE TABLE payments (
  id              UUID PRIMARY KEY,
  wallet_id       UUID NOT NULL REFERENCES wallets(id),
  biller_id       UUID NOT NULL REFERENCES billers(id),
  account_ref     TEXT NOT NULL,
  amount          BIGINT NOT NULL CHECK (amount > 0),
  currency        CHAR(3) NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('PENDING','SUBMITTED','UNKNOWN','SUCCEEDED','FAILED','REVERSED')),
  gateway_ref     TEXT UNIQUE,
  attempts        INT NOT NULL DEFAULT 0,      -- gateway submits
  status_checks   INT NOT NULL DEFAULT 0,      -- gateway status checks while UNKNOWN
  next_attempt_at TIMESTAMPTZ,
  failure_reason  TEXT,
  idempotency_key TEXT NOT NULL,
  version         BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payments_status_idx ON payments (status, next_attempt_at);
CREATE INDEX payments_wallet_idx ON payments (wallet_id, created_at);
CREATE INDEX payments_created_idx ON payments (created_at);
