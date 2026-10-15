-- Double-entry ledger. Postings are signed minor units: debit = positive, credit = negative,
-- so every balanced journal entry sums to exactly 0 per currency.

CREATE TABLE accounts (
  id             UUID PRIMARY KEY,
  code           TEXT NOT NULL UNIQUE,          -- 'user_wallet:{walletId}', 'gateway_clearing:INR', ...
  type           TEXT NOT NULL CHECK (type IN ('ASSET','LIABILITY','EXPENSE','REVENUE','EQUITY')),
  currency       CHAR(3) NOT NULL,              -- 'INR', 'USD', or 'PTS' for reward points
  balance        BIGINT NOT NULL DEFAULT 0,     -- cached, signed, = SUM(postings.amount)
  version        BIGINT NOT NULL DEFAULT 0,
  allow_negative BOOLEAN NOT NULL DEFAULT false, -- false: balance may not cross to the wrong side of its normal side
  system         BOOLEAN NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
  id           UUID PRIMARY KEY,
  type         TEXT NOT NULL,   -- TOPUP, BILL_PAYMENT, PAYMENT_SETTLEMENT, PAYMENT_REVERSAL, REWARD_ACCRUAL, REWARD_REDEEM, REWARD_REDEEM_CASH
  reference_id UUID NOT NULL,   -- payment id, top-up id, redemption id
  description  TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (type, reference_id)   -- one entry per business event: duplicates fail at the database
);

CREATE TABLE postings (
  id         BIGSERIAL PRIMARY KEY,
  entry_id   UUID NOT NULL REFERENCES journal_entries(id),
  account_id UUID NOT NULL REFERENCES accounts(id),
  amount     BIGINT NOT NULL CHECK (amount <> 0),
  currency   CHAR(3) NOT NULL
);
CREATE INDEX postings_account_idx ON postings (account_id);
CREATE INDEX postings_entry_idx ON postings (entry_id);

-- Every entry must balance per currency. Deferred, so it runs at COMMIT after all postings are in.
CREATE FUNCTION check_entry_balanced() RETURNS trigger AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM postings WHERE entry_id = NEW.entry_id
             GROUP BY currency HAVING SUM(amount) <> 0) THEN
    RAISE EXCEPTION 'journal entry % is unbalanced', NEW.entry_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_balanced
  AFTER INSERT ON postings DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- A posting's currency must be its account's currency.
CREATE FUNCTION check_posting_currency() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND currency = NEW.currency) THEN
    RAISE EXCEPTION 'posting currency % does not match account %', NEW.currency, NEW.account_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER postings_currency BEFORE INSERT ON postings
  FOR EACH ROW EXECUTE FUNCTION check_posting_currency();

-- Append-only: money moves only by inserting new entries, never by editing old ones.
-- (A trigger rather than REVOKE so it holds for every role, including the migration owner.)
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON postings
  FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- System accounts, one per currency.
INSERT INTO accounts (id, code, type, currency, allow_negative, system)
SELECT gen_random_uuid(), a.code || ':' || c.cur, a.type, c.cur, true, true
FROM (VALUES ('cash_bank', 'ASSET'),
             ('gateway_clearing', 'LIABILITY'),
             ('rewards_funding', 'EXPENSE')) AS a(code, type)
CROSS JOIN (VALUES ('INR'), ('USD')) AS c(cur);

INSERT INTO accounts (id, code, type, currency, allow_negative, system) VALUES
  (gen_random_uuid(), 'rewards_expense:PTS',  'EXPENSE', 'PTS', true, true),
  (gen_random_uuid(), 'rewards_redeemed:PTS', 'EQUITY',  'PTS', true, true);
