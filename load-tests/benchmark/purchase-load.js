// Contention scenario: VUS pre-authenticated buyers fire one purchase-request each
// at the same instant against STOCK seeded units.
//
// What it measures, in the vocabulary the async purchase flow actually uses
// (see docs/portfolio/architecture.md):
//
//   accepted  — the synchronous leg: how long POST /api/flash-sales/{id}/purchase-requests
//               takes to answer. That answer is 202 once the Redis Lua reservation
//               has run, and it already carries a terminal status when the buyer
//               lost the race (SOLD_OUT) or had already bought (REJECTED).
//   completed — wall-clock from issuing that POST until the request is observed in a
//               terminal status. For SOLD_OUT/REJECTED that is the POST itself; for a
//               winner it additionally covers outbox publish -> RabbitMQ -> consumer
//               -> order row, observed by polling GET /api/purchase-requests/{id}.
//   orderCreated — the winners-only subset of `completed`, i.e. the cost of the
//               asynchronous order-creation pipeline on its own.
//
// Tokens come from prepare.js, so no auth traffic pollutes the measurement.
//
//   k6 run purchase-load.js -e VUS=30 -e STOCK=10 -e RUN_ID=contention-30x10-1 \
//     -e BASE_URL=http://localhost:18080 -e TOKENS_FILE=<abs path>/tokens.json \
//     -e SUMMARY_OUT=<abs path>/k6-contention-30x10-1.json
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const VUS = parseInt(__ENV.VUS || '30', 10);
const STOCK = parseInt(__ENV.STOCK || '10', 10);
const RUN_ID = __ENV.RUN_ID || 'adhoc';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const FLASH_SALE_ID = __ENV.FLASH_SALE_ID || '1';
const TOKENS_FILE = __ENV.TOKENS_FILE || './tokens.json';
const SUMMARY_OUT = __ENV.SUMMARY_OUT || `k6-${RUN_ID}.json`;
// Poll cadence for the async leg. Finer than the UI's 1s refetch so that the
// completed-latency trend is not quantised into 1s buckets.
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '250', 10);
const POLL_TIMEOUT_MS = parseInt(__ENV.POLL_TIMEOUT_MS || '60000', 10);

const TERMINAL_STATUSES = ['SUCCEEDED', 'SOLD_OUT', 'REJECTED', 'FAILED'];

// Loaded in the init context. A missing file is not fatal here on purpose: it keeps
// `k6 archive` / `k6 inspect` usable on a clean checkout, and setup() below turns an
// empty population into an explicit "run prepare.js first" before any traffic flows.
function loadUsers(path) {
  try {
    const parsed = JSON.parse(open(path));
    if (parsed && parsed.users) {
      return parsed.users;
    }
    return [];
  } catch (error) {
    console.warn(`could not read ${path} (${error.message}); run prepare.js first`);
    return [];
  }
}

const users = loadUsers(TOKENS_FILE);

const acceptedLatency = new Trend('purchase_accepted_ms');
const completedLatency = new Trend('purchase_completed_ms');
const orderCreatedLatency = new Trend('purchase_order_created_ms');

const acceptedCount = new Counter('purchase_accepted');
const succeededCount = new Counter('purchase_succeeded');
const soldOutCount = new Counter('purchase_sold_out');
const rejectedCount = new Counter('purchase_rejected');
const failedCount = new Counter('purchase_failed');
const unexpected5xxCount = new Counter('purchase_unexpected_5xx');
const pendingTimeoutCount = new Counter('purchase_pending_timeout');
const missingTokenCount = new Counter('purchase_missing_token');

export const options = {
  scenarios: {
    contention: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '10m' },
  },
  // Reported, never enforced: this harness records what the current system does
  // under load, it does not assert a performance target. Only invariant breaches
  // fail a run, and those are checked by verify-results.mjs against the database.
  thresholds: {},
};

export function setup() {
  if (users.length < VUS) {
    throw new Error(`${TOKENS_FILE} holds ${users.length} token(s) but VUS=${VUS}; run prepare.js with USERS>=VUS first`);
  }
  console.log(`contention ${RUN_ID}: VUS=${VUS} STOCK=${STOCK} flashSaleId=${FLASH_SALE_ID} baseUrl=${BASE_URL}`);
  return { startedAt: new Date().toISOString() };
}

/**
 * Issue one purchase request and follow it to a terminal status.
 * Shared shape with soak.js on purpose: both measure the same two legs.
 */
function purchaseOnce(user, idempotencyKey) {
  if (!user || !user.token) {
    missingTokenCount.add(1);
    return;
  }

  const authHeaders = {
    Authorization: `Bearer ${user.token}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
  };

  const startedAtMs = Date.now();
  const response = http.post(
    `${BASE_URL}/api/flash-sales/${FLASH_SALE_ID}/purchase-requests`,
    JSON.stringify({ quantity: 1 }),
    { headers: authHeaders, tags: { leg: 'accept' } }
  );
  acceptedLatency.add(response.timings.duration);

  if (response.status >= 500) {
    unexpected5xxCount.add(1);
    console.error(`${RUN_ID}: purchase-request -> ${response.status} ${String(response.body).slice(0, 200)}`);
    return;
  }
  if (response.status !== 202) {
    failedCount.add(1);
    console.error(`${RUN_ID}: purchase-request -> ${response.status} ${String(response.body).slice(0, 200)}`);
    return;
  }
  acceptedCount.add(1);

  let requestId;
  let status;
  try {
    requestId = response.json('requestId');
    status = response.json('status');
  } catch (error) {
    failedCount.add(1);
    console.error(`${RUN_ID}: unparseable purchase-request body ${String(response.body).slice(0, 200)}`);
    return;
  }

  const wasAsync = status === 'PENDING';
  const pollHeaders = { Authorization: `Bearer ${user.token}` };
  while (TERMINAL_STATUSES.indexOf(status) === -1 && Date.now() - startedAtMs < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_MS / 1000);
    const poll = http.get(`${BASE_URL}/api/purchase-requests/${requestId}`, {
      headers: pollHeaders,
      tags: { leg: 'poll' },
    });
    if (poll.status >= 500) {
      unexpected5xxCount.add(1);
      console.error(`${RUN_ID}: poll -> ${poll.status} ${String(poll.body).slice(0, 200)}`);
      break;
    }
    try {
      status = poll.json('status');
    } catch (error) {
      console.error(`${RUN_ID}: unparseable poll body ${String(poll.body).slice(0, 200)}`);
      break;
    }
  }

  const elapsedMs = Date.now() - startedAtMs;
  if (TERMINAL_STATUSES.indexOf(status) === -1) {
    pendingTimeoutCount.add(1);
    return;
  }

  completedLatency.add(elapsedMs);
  if (status === 'SUCCEEDED') {
    succeededCount.add(1);
    if (wasAsync) {
      orderCreatedLatency.add(elapsedMs);
    }
  } else if (status === 'SOLD_OUT') {
    soldOutCount.add(1);
  } else if (status === 'REJECTED') {
    rejectedCount.add(1);
  } else if (status === 'FAILED') {
    failedCount.add(1);
  }
}

export default function () {
  // __VU is 1-based; every VU owns exactly one prepared account, so no two VUs
  // ever collide on the per-user purchase limit.
  purchaseOnce(users[__VU - 1], `${RUN_ID}-vu-${__VU}`);
}

function trendOf(metrics, name) {
  // A trend with no samples yields nulls rather than a fabricated zero, so the
  // verifier rejects a run that measured nothing instead of reporting 0ms.
  const metric = metrics[name];
  if (!metric || !metric.values) {
    return { avg: null, min: null, med: null, p90: null, p95: null, max: null };
  }
  return {
    avg: metric.values.avg,
    min: metric.values.min,
    med: metric.values.med,
    p90: metric.values['p(90)'],
    p95: metric.values['p(95)'],
    max: metric.values.max,
  };
}

function counterOf(metrics, name) {
  const metric = metrics[name];
  if (!metric || !metric.values || metric.values.count === undefined) {
    return 0;
  }
  return metric.values.count;
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const iterations = counterOf(metrics, 'iterations');
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values ? metrics.http_reqs.values : {};
  const checks = metrics.checks && metrics.checks.values ? metrics.checks.values : {};
  const summary = {
    runId: RUN_ID,
    kind: 'contention',
    vus: VUS,
    stock: STOCK,
    flashSaleId: FLASH_SALE_ID,
    startedAt: data.setup_data ? data.setup_data.startedAt : null,
    finishedAt: new Date().toISOString(),
    metrics: {
      acceptedLatencyMs: trendOf(metrics, 'purchase_accepted_ms'),
      completedLatencyMs: trendOf(metrics, 'purchase_completed_ms'),
      orderCreatedLatencyMs: trendOf(metrics, 'purchase_order_created_ms'),
      requestsPerSecond: httpReqs.rate === undefined ? null : httpReqs.rate,
      httpRequests: httpReqs.count === undefined ? null : httpReqs.count,
      iterations,
    },
    outcomes: {
      accepted: counterOf(metrics, 'purchase_accepted'),
      succeeded: counterOf(metrics, 'purchase_succeeded'),
      soldOut: counterOf(metrics, 'purchase_sold_out'),
      rejected: counterOf(metrics, 'purchase_rejected'),
      failed: counterOf(metrics, 'purchase_failed'),
      unexpected5xx: counterOf(metrics, 'purchase_unexpected_5xx'),
      pendingTimeout: counterOf(metrics, 'purchase_pending_timeout'),
      missingToken: counterOf(metrics, 'purchase_missing_token'),
    },
    checksFailed: checks.fails === undefined ? 0 : checks.fails,
    rawMetrics: metrics,
  };
  return {
    [SUMMARY_OUT]: JSON.stringify(summary, null, 1),
    stdout: `contention ${RUN_ID}: ${summary.outcomes.succeeded} succeeded / ${summary.outcomes.soldOut} sold out / ${summary.outcomes.unexpected5xx} 5xx -> ${SUMMARY_OUT}\n`,
  };
}
