// Soak scenario: a steady 10 purchase requests per second for 10 minutes, each one
// from a different pre-authenticated buyer (6,000 accounts = 10/s x 600s).
//
// Where purchase-load.js answers "what happens when everyone arrives at once", this
// answers "does the system stay flat when the same arrival rate is sustained" — the
// latency trends below are what Task 5 reports, together with the database and queue
// invariants collect.ps1 captures after the run.
//
// The scenario block is deliberately literal and not environment-overridable: the
// soak contract (constant-arrival-rate, 10/s, 10m, 50 preallocated / 200 max VUs,
// 6,000 unique users) is the same one verify-results.mjs re-checks in the collected
// result, so the two can never silently drift apart.
//
//   k6 run soak.js -e RUN_ID=soak-1 -e BASE_URL=http://localhost:18080 \
//     -e TOKENS_FILE=<abs path>/tokens-soak.json -e SUMMARY_OUT=<abs path>/k6-soak-1.json
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

/** Unique buyers the soak drives — one per iteration, 10/s x 600s. */
export const SOAK_USERS = 6000;

const RUN_ID = __ENV.RUN_ID || 'soak';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const FLASH_SALE_ID = __ENV.FLASH_SALE_ID || '1';
const TOKENS_FILE = __ENV.TOKENS_FILE || './tokens-soak.json';
const SUMMARY_OUT = __ENV.SUMMARY_OUT || `k6-${RUN_ID}.json`;
const STOCK = parseInt(__ENV.STOCK || String(SOAK_USERS), 10);
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '250', 10);
const POLL_TIMEOUT_MS = parseInt(__ENV.POLL_TIMEOUT_MS || '60000', 10);

const TERMINAL_STATUSES = ['SUCCEEDED', 'SOLD_OUT', 'REJECTED', 'FAILED'];

// Loaded in the init context; see purchase-load.js for why a missing file only
// warns here and becomes a hard error in setup().
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

/** The soak contract, mirrored verbatim into the result document. */
const SOAK_SCENARIO = {
  executor: 'constant-arrival-rate',
  rate: 10,
  timeUnit: '1s',
  duration: '10m',
  preAllocatedVUs: 50,
  maxVUs: 200,
};

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  // Reported, never enforced — see purchase-load.js.
  thresholds: {},
};

export function setup() {
  if (users.length < SOAK_USERS) {
    throw new Error(`${TOKENS_FILE} holds ${users.length} token(s) but the soak needs ${SOAK_USERS}; run prepare.js with USERS=${SOAK_USERS} first`);
  }
  console.log(`soak ${RUN_ID}: 10/s for 10m over ${SOAK_USERS} unique users, flashSaleId=${FLASH_SALE_ID}, baseUrl=${BASE_URL}`);
  return { startedAt: new Date().toISOString() };
}

/** Identical measurement contract to purchase-load.js: accepted leg, then completed leg. */
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

  const response = http.post(
    `${BASE_URL}/api/flash-sales/${FLASH_SALE_ID}/purchase-requests`,
    JSON.stringify({ quantity: 1 }),
    { headers: authHeaders, tags: { leg: 'accept' } }
  );
  acceptedLatency.add(response.timings.duration);
  // 不以 Date.now() 計算耗時：容器內的牆鐘會被 WSL2 的時鐘校正往回跳（2026-09-13 實測
  // 每約 30 秒一次、最大 -1489ms）。牆鐘相減不只可能得到負值，回跳幅度小於真實耗時時還會
  // 得到「偏小的正值」，那種樣本無法被偵測，比負值更危險。
  // 改為累加兩個單調來源：k6 自己量的請求耗時（Go runtime 的單調時鐘）與我們自己指定的
  // sleep 間隔。代價是這個數字不含 VU 被 k6 調度器擱置的空檔，因此略小於真實牆鐘耗時。
  let elapsedMs = response.timings.duration;

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
  while (TERMINAL_STATUSES.indexOf(status) === -1 && elapsedMs < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_MS / 1000);
    elapsedMs += POLL_INTERVAL_MS;
    const poll = http.get(`${BASE_URL}/api/purchase-requests/${requestId}`, {
      headers: pollHeaders,
      tags: { leg: 'poll' },
    });
    elapsedMs += poll.timings.duration;
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
  // iterationInTest is unique and monotonic across the whole run, so each of the
  // 6,000 iterations gets its own buyer and never trips the per-user limit.
  const index = exec.scenario.iterationInTest;
  purchaseOnce(users[index], `${RUN_ID}-iter-${index}`);
}

function trendOf(metrics, name) {
  // See purchase-load.js: no samples means nulls, never a fabricated zero.
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
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values ? metrics.http_reqs.values : {};
  const checks = metrics.checks && metrics.checks.values ? metrics.checks.values : {};
  const summary = {
    runId: RUN_ID,
    kind: 'soak',
    users: SOAK_USERS,
    stock: STOCK,
    flashSaleId: FLASH_SALE_ID,
    scenario: SOAK_SCENARIO,
    startedAt: data.setup_data ? data.setup_data.startedAt : null,
    finishedAt: new Date().toISOString(),
    metrics: {
      acceptedLatencyMs: trendOf(metrics, 'purchase_accepted_ms'),
      completedLatencyMs: trendOf(metrics, 'purchase_completed_ms'),
      orderCreatedLatencyMs: trendOf(metrics, 'purchase_order_created_ms'),
      requestsPerSecond: httpReqs.rate === undefined ? null : httpReqs.rate,
      httpRequests: httpReqs.count === undefined ? null : httpReqs.count,
      iterations: counterOf(metrics, 'iterations'),
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
    stdout: `soak ${RUN_ID}: ${summary.metrics.iterations} iterations, ${summary.outcomes.succeeded} succeeded, ${summary.outcomes.unexpected5xx} 5xx -> ${SUMMARY_OUT}\n`,
  };
}
