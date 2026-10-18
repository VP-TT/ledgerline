-- House accounts (allow_negative = true) are no longer locked or given a cached balance: they have no
-- balance rule to enforce, and locking them serialized every payment on gateway_clearing.
-- Their balance is SUM(postings.amount); the cached column stays 0 for them.
UPDATE accounts SET balance = 0 WHERE allow_negative;
ALTER TABLE accounts ADD CONSTRAINT house_accounts_have_no_cached_balance
  CHECK (NOT allow_negative OR balance = 0);
COMMENT ON COLUMN accounts.balance IS
  'Cached SUM(postings.amount), maintained under a row lock, only for accounts with allow_negative = false';
