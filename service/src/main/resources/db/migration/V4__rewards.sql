CREATE TABLE reward_rules (
  id              UUID PRIMARY KEY,
  currency        CHAR(3) NOT NULL,     -- unit_minor is in this currency's minor units
  biller_category TEXT,                 -- NULL = all billers
  points_per_unit INT NOT NULL CHECK (points_per_unit > 0),
  unit_minor      BIGINT NOT NULL CHECK (unit_minor > 0),
  max_points      INT,
  active_from     DATE NOT NULL,
  active_to       DATE
);

INSERT INTO reward_rules (id, currency, biller_category, points_per_unit, unit_minor, max_points, active_from) VALUES
  (gen_random_uuid(), 'INR', NULL,          1, 10000, 500, DATE '2026-01-01'),  -- 1 pt per Rs 100
  (gen_random_uuid(), 'INR', 'ELECTRICITY', 2, 10000, 1000, DATE '2026-01-01'), -- 2 pts per Rs 100
  (gen_random_uuid(), 'USD', NULL,          1, 100,   500, DATE '2026-01-01');  -- 1 pt per $1

CREATE TABLE redemptions (
  id              UUID PRIMARY KEY,
  wallet_id       UUID NOT NULL REFERENCES wallets(id),
  points          BIGINT NOT NULL CHECK (points > 0),
  credited        BIGINT NOT NULL CHECK (credited > 0),
  currency        CHAR(3) NOT NULL,
  idempotency_key TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
