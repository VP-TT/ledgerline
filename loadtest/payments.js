// k6 load test for POST /v1/payments.
//
//   k6 run loadtest/payments.js                        # 200 payments/s for 60 s over 200 wallets
//   k6 run -e RATE=400 -e WALLETS=1 loadtest/payments.js   # everything on one wallet (row-lock ceiling)
//
// Measures the accept path (HTTP latency, throughput, errors) and, in teardown, how long the outbox
// relay takes to drive every accepted payment to SUCCEEDED against the fake gateway.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '60s';
const WALLETS = Number(__ENV.WALLETS || 200);
const HEADERS = { 'Content-Type': 'application/json', 'X-Client-Id': 'k6' };

const accepted = new Counter('payments_accepted');
const settleSeconds = new Trend('settle_all_seconds');

export const options = {
  scenarios: {
    pay: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 400),
      maxVUs: 2000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '300s',
  teardownTimeout: '600s',
};

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function post(path, key, body) {
  return http.post(BASE + path, JSON.stringify(body), { headers: { ...HEADERS, 'Idempotency-Key': key } });
}

function succeededCount() {
  const text = http.get(BASE + '/actuator/prometheus', { tags: { name: 'metrics' } }).body;
  const m = text.match(/^ledgerline_payments_transitions_total\{[^}]*to="SUCCEEDED"[^}]*\} ([0-9.eE+]+)$/m);
  return m ? Number(m[1]) : 0;
}

export function setup() {
  const wallets = [];
  for (let i = 0; i < WALLETS; i++) {
    const w = post('/v1/wallets', uuid(), { userId: 'k6-' + uuid(), currency: 'INR' });
    const id = w.json('walletId');
    post(`/v1/wallets/${id}/topups`, uuid(), { amount: 1000000.0 });
    wallets.push(id);
  }
  return { wallets, succeededBefore: succeededCount() };
}

export default function (data) {
  const wallet = data.wallets[Math.floor(Math.random() * data.wallets.length)];
  const res = post('/v1/payments', uuid(), {
    walletId: wallet,
    billerId: '00000000-0000-0000-0000-0000000000b1',
    accountRef: 'LOAD-' + __VU,
    amount: 1.0,
    currency: 'INR',
  });
  if (check(res, { 'accepted (202)': r => r.status === 202 })) accepted.add(1);
}

// After the load stops: wait for the relay to settle everything, and report how long that took.
export function teardown(data) {
  const start = Date.now();
  for (;;) {
    const backlog = http.get(BASE + '/v1/admin/outbox', { tags: { name: 'outbox' } }).json();
    if (backlog.pending === 0) break;
    if (Date.now() - start > 550000) { console.error('outbox did not drain: ' + JSON.stringify(backlog)); break; }
    sleep(0.5);
  }
  settleSeconds.add((Date.now() - start) / 1000);
  console.log(`succeeded during run: ${succeededCount() - data.succeededBefore}`);
}

export function handleSummary(data) {
  const d = data.metrics.http_req_duration.values;
  const reqs = data.metrics['http_reqs{scenario:pay}'] || data.metrics.http_reqs;
  const line = {
    rate_target: RATE, wallets: WALLETS, duration: DURATION,
    accepted: data.metrics.payments_accepted ? data.metrics.payments_accepted.values.count : 0,
    failed_rate: data.metrics.http_req_failed.values.rate,
    p50_ms: d.med, p95_ms: d['p(95)'], p99_ms: d['p(99)'], max_ms: d.max,
    drain_after_load_s: data.metrics.settle_all_seconds ? data.metrics.settle_all_seconds.values.max : null,
    dropped_iterations: data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0,
  };
  const name = `loadtest/results/rate${RATE}-wallets${WALLETS}-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
  return { stdout: '\n' + JSON.stringify(line, null, 2) + '\n', [name]: JSON.stringify(data, null, 2) };
}
