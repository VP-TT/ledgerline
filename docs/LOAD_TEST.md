# Load test: finding and removing the hot row

`loadtest/payments.js` (k6) fires `POST /v1/payments` at a constant arrival rate over many wallets, so it
measures the service rather than one wallet's row lock. After the load stops it times how long the
outbox relay takes to drive every accepted payment to SUCCEEDED against the fake gateway.

```bash
docker compose up -d postgres fake-gateway && ./mvnw -pl service spring-boot:run
k6 run -e RATE=200 -e DURATION=60s -e WALLETS=200 loadtest/payments.js
```

**Setup for every number below:** one app instance, local PostgreSQL 16, fake gateway with no faults, all
on one Apple M1 Pro laptop (8 cores, on battery), 16 Oct 2026. Fresh database each run, a 20 s warm-up
at 100/s, then 60 s at 200 payments/s spread over 200 wallets (≈12,000 payments). Numbers are single
runs except where noted; they describe this laptop, not a production deployment.

## Results

| Step | p50 | p95 | p99 | dropped | settled after load stops |
|---|---|---|---|---|---|
| 0. Baseline (v1) | 28 ms | 1,392 ms | 3,026 ms | 350 | 125 s |
| 1. Don't lock house accounts | 3.1 ms | 33 ms | 52 ms | 0 | 89 s |
| 2. + relay drains back-to-back | 5.1 ms | 103 ms | 270 ms | 0 | 0.5 s |
| 3. + relay concurrency capped at 10 | 4.4 / 4.1 ms | 57 / 35 ms | 160 / 161 ms | 0 | 0.5 s |

"Dropped" = k6 iterations it couldn't start on schedule because requests were too slow. "Settled after
load stops" = time from the end of the load until the outbox was empty. Step 3 was run twice (both shown).
No request failed in any run.

## Step 0 → 1: the hot row

Sampling `pg_stat_activity` every 250 ms during a baseline run: **552 of 593 active-session samples were
waiting on a row lock** (`Lock:transactionid`, `Lock:tuple`, `LWLock:LockManager`), all inside the
account-locking `SELECT … FOR UPDATE`. Hikari had up to 20 requests queued for a connection.

The load was spread over 200 wallets, so the contended rows were the shared house accounts. Every
payment locked `gateway_clearing:INR` and updated its cached balance, and every settlement locked
`gateway_clearing`, `biller_payable` and `rewards_expense`. Every payment in the system queued on one row.

The fix: house accounts have no balance rule (`allow_negative = true`), so there is nothing to
read-check-write and nothing to serialize. `LedgerService` now locks, checks and updates the cached balance
only for accounts with a rule (user wallets, points), still in id order. House accounts just get their
postings appended; their balance is `SUM(postings)` (migration V6 zeroes their cached column and adds a
CHECK so it stays 0). Correctness is unchanged: entries are still balanced by the deferred trigger, the
ledger is still append-only, and reconciliation now checks `gateway_clearing` from its postings.

The usual alternative is sharding the hot account into N rows (`gateway_clearing:INR:0..N-1`). That keeps
a cached balance but still serializes within each shard; dropping the lock removes the contention entirely.

## Step 1 → 2: the relay couldn't keep up

With the accept path fast, settlement became the bottleneck: the relay claimed 50 events, processed them,
then waited the full 500 ms poll interval, capping it at roughly 100 events/s. At 200/s the backlog
grew for the whole run and took 89 s to clear afterwards. `OutboxRelay.drainWhileBusy()` now keeps
claiming batches while they come back full and only sleeps once it's caught up: settled 0.5 s after load.

The cost showed up in accept latency (p95 33 → 103 ms): settlement work now runs concurrently with new
payments instead of being deferred, which is the honest steady state.

## Step 2 → 3: don't let the relay starve the API

A full batch of 50 events processed in parallel could hold most of the 30-connection pool. Capping events
in flight at 10 (`ledgerline.outbox.concurrency`) brought p95 back to 35–57 ms and p99 to ~160 ms while
the relay still kept pace.

## Not measured yet

Rates above 200/s, more than one instance, gateway faults under load, and a non-laptop machine.
