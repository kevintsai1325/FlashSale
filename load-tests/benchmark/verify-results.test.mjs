// Unit tests for the benchmark result verifier.
//
// Fixtures are literal result documents (the same shape collect.ps1 writes), not
// mocks: each test starts from a known-good document and breaks exactly one thing,
// so a failing assertion always names the invariant that regressed.
import test from 'node:test';
import assert from 'node:assert/strict';

import { validateBenchmarkResults, SOAK_SCENARIO, SOAK_USERS } from './verify-results.mjs';

const VALID_ENVIRONMENT = {
  gitSha: '0f1e2d3c4b5a69788796a5b4c3d2e1f000000000',
  gitBranch: 'codex/week7-portfolio',
  gitDirty: false,
  k6Version: 'k6.exe v2.2.0 (commit/00a9a1b7f5, go1.26.5, windows/amd64)',
  dockerVersion: '29.6.2',
  dockerComposeVersion: 'v5.3.1',
  os: 'Microsoft Windows 11 Home 10.0.26200',
  cpu: { model: 'AMD Ryzen 7 5800X 8-Core Processor', logicalCores: 16 },
  memoryGb: 32,
  composeProject: 'flashsale-benchmark',
  startedAt: '2026-08-16T10:00:00.0000000Z',
  finishedAt: '2026-08-16T10:42:00.0000000Z',
};

function contentionRun(overrides = {}) {
  return {
    runId: 'contention-30x10-1',
    kind: 'contention',
    status: 'valid',
    vus: 30,
    stock: 10,
    startedAt: '2026-08-16T10:00:10.0000000Z',
    finishedAt: '2026-08-16T10:00:24.0000000Z',
    k6: { exitCode: 0, checksFailed: 0 },
    metrics: {
      acceptedLatencyMs: { avg: 41.2, p95: 88.7, max: 121.4 },
      completedLatencyMs: { avg: 402.5, p95: 830.1, max: 1104.0 },
      requestsPerSecond: 62.5,
      iterations: 30,
    },
    outcomes: {
      accepted: 30,
      succeeded: 10,
      soldOut: 20,
      rejected: 0,
      failed: 0,
      unexpected5xx: 0,
      pendingTimeout: 0,
    },
    invariants: {
      ordersCreated: 10,
      succeededRequests: 10,
      residualPending: 0,
      duplicateOrderUsers: 0,
      duplicateSucceededUsers: 0,
      inventory: { available: 0, reserved: 0, sold: 10 },
      unpublishedOutboxEvents: 0,
    },
    queues: [
      { name: 'order.create.queue', messages: 0, messagesReady: 0, messagesUnacknowledged: 0 },
      { name: 'order.create.queue.dlq', messages: 0, messagesReady: 0, messagesUnacknowledged: 0 },
    ],
    health: { readiness: 'UP' },
    errors: [],
    ...overrides,
  };
}

function soakRun(overrides = {}) {
  return {
    runId: 'soak-1',
    kind: 'soak',
    status: 'valid',
    users: 6000,
    stock: 6000,
    scenario: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
    startedAt: '2026-08-16T10:30:00.0000000Z',
    finishedAt: '2026-08-16T10:40:12.0000000Z',
    k6: { exitCode: 0, checksFailed: 0 },
    metrics: {
      acceptedLatencyMs: { avg: 18.4, p95: 31.9, max: 233.0 },
      completedLatencyMs: { avg: 261.7, p95: 512.0, max: 1899.0 },
      requestsPerSecond: 10.0,
      iterations: 6000,
    },
    outcomes: {
      accepted: 6000,
      succeeded: 6000,
      soldOut: 0,
      rejected: 0,
      failed: 0,
      unexpected5xx: 0,
      pendingTimeout: 0,
    },
    invariants: {
      ordersCreated: 6000,
      succeededRequests: 6000,
      residualPending: 0,
      duplicateOrderUsers: 0,
      duplicateSucceededUsers: 0,
      inventory: { available: 0, reserved: 0, sold: 6000 },
      unpublishedOutboxEvents: 0,
    },
    queues: [{ name: 'order.create.queue', messages: 0, messagesReady: 0, messagesUnacknowledged: 0 }],
    health: { readiness: 'UP' },
    errors: [],
    ...overrides,
  };
}

/** A minimal but complete valid document: one contention run plus one soak run. */
function validSmokeResult() {
  return {
    schemaVersion: 1,
    mode: 'smoke',
    environment: structuredClone(VALID_ENVIRONMENT),
    summary: { expectedRuns: 2, failedRuns: 0 },
    runs: [contentionRun(), soakRun()],
  };
}

/** The document Task 5 produces: 15 contention runs (3 profiles x 5) plus one soak run. */
function validFullResult() {
  const profiles = [
    { vus: 30, stock: 10 },
    { vus: 100, stock: 30 },
    { vus: 300, stock: 100 },
  ];
  const runs = [];
  for (const profile of profiles) {
    for (let repeat = 1; repeat <= 5; repeat += 1) {
      const run = contentionRun({
        runId: `contention-${profile.vus}x${profile.stock}-${repeat}`,
        vus: profile.vus,
        stock: profile.stock,
      });
      run.outcomes = { ...run.outcomes, accepted: profile.vus, succeeded: profile.stock, soldOut: profile.vus - profile.stock };
      run.invariants = {
        ...run.invariants,
        ordersCreated: profile.stock,
        succeededRequests: profile.stock,
        inventory: { available: 0, reserved: 0, sold: profile.stock },
      };
      runs.push(run);
    }
  }
  runs.push(soakRun());
  return {
    schemaVersion: 1,
    mode: 'full',
    environment: structuredClone(VALID_ENVIRONMENT),
    summary: { expectedRuns: 16, failedRuns: 0 },
    runs,
  };
}

function assertRejects(result, expectedFragment) {
  const outcome = validateBenchmarkResults(result);
  assert.equal(outcome.valid, false, `expected the document to be rejected; errors: ${JSON.stringify(outcome.errors)}`);
  assert.ok(
    outcome.errors.some((error) => error.includes(expectedFragment)),
    `expected an error containing ${JSON.stringify(expectedFragment)}, got ${JSON.stringify(outcome.errors)}`
  );
}

test('accepts a well-formed smoke result', () => {
  const outcome = validateBenchmarkResults(validSmokeResult());
  assert.deepEqual(outcome.errors, []);
  assert.equal(outcome.valid, true);
});

test('accepts a well-formed full 15-run + soak result', () => {
  const outcome = validateBenchmarkResults(validFullResult());
  assert.deepEqual(outcome.errors, []);
  assert.equal(outcome.valid, true);
});

// 時鐘相關的資料品質檢查。
// 2026-09-13 實測：k6 跑在 WSL2 的容器內時，VM 的牆鐘大約每 30 秒被校正一次、每次往回跳
// 約 1.5 秒。那讓 k6 內建的 http_reqs.rate 算出負的吞吐量，也曾讓以 Date.now() 相減的延遲
// 算出負值。延遲那一側已改為單調累加而免疫，但 rate 是 k6 內部計算的，腳本改不到。
// 驗證器的職責就是擋下不能發布的結果 —— 物理上不可能的數字必須讓整份結果失效，
// 而不是靜靜地被寫進作品集。
test('rejects a non-positive request rate (clock went backwards during the run)', () => {
  const result = validSmokeResult();
  result.runs[0].metrics.requestsPerSecond = -1437.4;
  assertRejects(result, 'requestsPerSecond');
});

test('rejects a zero request rate', () => {
  const result = validSmokeResult();
  result.runs[0].metrics.requestsPerSecond = 0;
  assertRejects(result, 'requestsPerSecond');
});

test('rejects a negative latency statistic', () => {
  const result = validSmokeResult();
  result.runs[0].metrics.completedLatencyMs.min = -1342;
  assertRejects(result, 'completedLatencyMs.min');
});

test('rejects a negative latency average', () => {
  const result = validSmokeResult();
  result.runs[0].metrics.acceptedLatencyMs.avg = -0.5;
  assertRejects(result, 'acceptedLatencyMs.avg');
});

test('rejects oversell: more orders created than seeded stock', () => {
  const result = validSmokeResult();
  result.runs[0].invariants.ordersCreated = 11;
  assertRejects(result, 'oversell');
});

test('rejects oversell: more SUCCEEDED purchase requests than seeded stock', () => {
  const result = validSmokeResult();
  result.runs[0].invariants.succeededRequests = 12;
  assertRejects(result, 'oversell');
});

test('rejects duplicate orders for the same user', () => {
  const result = validSmokeResult();
  result.runs[0].invariants.duplicateOrderUsers = 1;
  assertRejects(result, 'duplicate order');
});

test('rejects duplicate SUCCEEDED requests for the same user', () => {
  const result = validSmokeResult();
  result.runs[1].invariants.duplicateSucceededUsers = 3;
  assertRejects(result, 'duplicate');
});

test('rejects negative inventory', () => {
  const result = validSmokeResult();
  result.runs[0].invariants.inventory.available = -1;
  assertRejects(result, 'negative inventory');
});

test('rejects negative sold/reserved inventory counters', () => {
  const result = validSmokeResult();
  result.runs[1].invariants.inventory.reserved = -4;
  assertRejects(result, 'negative inventory');
});

test('rejects a nonzero unexpected 5xx count', () => {
  const result = validSmokeResult();
  result.runs[0].outcomes.unexpected5xx = 2;
  assertRejects(result, 'unexpected 5xx');
});

test('rejects purchase requests left in PENDING', () => {
  const result = validSmokeResult();
  result.runs[0].invariants.residualPending = 5;
  assertRejects(result, 'PENDING');
});

test('rejects missing environment metadata', () => {
  const result = validSmokeResult();
  delete result.environment.gitSha;
  assertRejects(result, 'environment.gitSha');
});

test('rejects missing hardware metadata', () => {
  const result = validSmokeResult();
  delete result.environment.cpu;
  assertRejects(result, 'environment.cpu');
});

test('rejects a result collected under a different Compose project', () => {
  const result = validSmokeResult();
  result.environment.composeProject = 'flashsale';
  assertRejects(result, 'flashsale-benchmark');
});

test('rejects omitted runs: fewer records than expectedRuns', () => {
  const result = validSmokeResult();
  result.runs = [result.runs[0]];
  assertRejects(result, 'omits');
});

test('rejects omitted failed runs: failedRuns count without matching failed records', () => {
  const result = validSmokeResult();
  result.summary.failedRuns = 1;
  assertRejects(result, 'failedRuns');
});

test('accepts a preserved failed run that carries its error detail', () => {
  const result = validSmokeResult();
  result.runs.push(
    contentionRun({
      runId: 'contention-300x100-3',
      status: 'failed',
      vus: 300,
      stock: 100,
      k6: { exitCode: 99, checksFailed: 7 },
      errors: ['k6 exited with code 99: dial tcp 127.0.0.1:18080: connectex: connection refused'],
    })
  );
  result.summary.expectedRuns = 3;
  result.summary.failedRuns = 1;
  const outcome = validateBenchmarkResults(result);
  assert.deepEqual(outcome.errors, []);
  assert.equal(outcome.valid, true);
});

test('rejects a failed run stripped of its error detail', () => {
  const result = validSmokeResult();
  result.runs.push(contentionRun({ runId: 'contention-300x100-3', status: 'failed', k6: { exitCode: 99, checksFailed: 0 }, errors: [] }));
  result.summary.expectedRuns = 3;
  result.summary.failedRuns = 1;
  assertRejects(result, 'no error detail');
});

test('rejects a failing run relabelled as valid', () => {
  const result = validSmokeResult();
  result.runs[0].k6.exitCode = 99;
  assertRejects(result, 'marked valid');
});

test('rejects a valid run whose recorded errors contradict its status', () => {
  const result = validSmokeResult();
  result.runs[0].errors = ['queue drain timed out after 120s'];
  assertRejects(result, 'marked valid');
});

test('rejects a soak run with the wrong arrival rate', () => {
  const result = validSmokeResult();
  result.runs[1].scenario.rate = 20;
  assertRejects(result, 'rate');
});

test('rejects a soak run with the wrong duration', () => {
  const result = validSmokeResult();
  result.runs[1].scenario.duration = '5m';
  assertRejects(result, 'duration');
});

test('rejects a soak run using the wrong executor', () => {
  const result = validSmokeResult();
  result.runs[1].scenario.executor = 'ramping-arrival-rate';
  assertRejects(result, 'executor');
});

test('rejects a soak run with the wrong VU allocation', () => {
  const result = validSmokeResult();
  result.runs[1].scenario.preAllocatedVUs = 10;
  assertRejects(result, 'preAllocatedVUs');
});

test('rejects a soak run with the wrong maxVUs', () => {
  const result = validSmokeResult();
  result.runs[1].scenario.maxVUs = 1000;
  assertRejects(result, 'maxVUs');
});

test('rejects a soak run with the wrong timeUnit', () => {
  const result = validSmokeResult();
  result.runs[1].scenario.timeUnit = '1m';
  assertRejects(result, 'timeUnit');
});

test('rejects a soak run that does not use 6,000 unique users', () => {
  const result = validSmokeResult();
  result.runs[1].users = 600;
  assertRejects(result, 'unique users');
});

test('rejects a full result missing the soak run', () => {
  const result = validFullResult();
  result.runs = result.runs.filter((run) => run.kind !== 'soak');
  result.summary.expectedRuns = 15;
  assertRejects(result, 'soak');
});

test('rejects a full result without 15 contention runs', () => {
  const result = validFullResult();
  result.runs = result.runs.filter((run, index) => run.kind !== 'contention' || index < 10);
  result.summary.expectedRuns = result.runs.length;
  assertRejects(result, '15 contention');
});

test('rejects a valid run missing its measurement blocks', () => {
  const result = validSmokeResult();
  delete result.runs[0].metrics;
  assertRejects(result, 'metrics');
});

test('rejects a non-object document', () => {
  assertRejects(null, 'object');
});

test('exports the soak contract the scenarios must implement', () => {
  assert.equal(SOAK_USERS, 6000);
  assert.deepEqual(SOAK_SCENARIO, {
    executor: 'constant-arrival-rate',
    rate: 10,
    timeUnit: '1s',
    duration: '10m',
    preAllocatedVUs: 50,
    maxVUs: 200,
  });
});
