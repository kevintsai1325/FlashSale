// analyze-saturation.mjs 的單元測試。
//
// fixture 是完整的執行文件，每個測試只弄壞一項，因此失敗的斷言一定指得出是哪一條不變量。
import test from 'node:test';
import assert from 'node:assert/strict';

import { validateSaturationRun, buildCurve, findSaturationPoints } from './analyze-saturation.mjs';

function validRun(overrides = {}) {
  return {
    runId: 'saturation-r3-600',
    kind: 'saturation',
    replicas: 3,
    targetRate: 600,
    startRate: 50,
    rampSeconds: 30,
    holdSeconds: 60,
    connectionReuse: true,
    preAllocatedVUs: 400,
    maxVUs: 800,
    startedAt: '2026-09-14T02:00:00.000Z',
    finishedAt: '2026-09-14T02:01:30.000Z',
    metrics: {
      acceptLatencyMs: { min: 3.1, med: 41.2, p95: 180.4, p99: 260.9, max: 512.0, avg: 60.3, count: 54000 },
      pollLatencyMs: { min: 1.9, med: 8.4, p95: 30.1, p99: 44.0, max: 120.0, avg: 11.2, count: 12000 },
      achievedRps: 598.2,
      httpRequests: 66000,
      newConnections: 402,
      droppedIterations: 0,
      failedRequests: 0,
      non202Responses: 0,
      unexpected5xx: 0,
    },
    ...overrides,
  };
}

test('合格的執行沒有任何問題', () => {
  assert.deepEqual(validateSaturationRun(validRun()), []);
});

test('負的延遲被擋下', () => {
  const run = validRun();
  run.metrics.acceptLatencyMs.min = -12;
  const problems = validateSaturationRun(run);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /acceptLatencyMs/);
});

test('延遲分位數順序顛倒被擋下', () => {
  const run = validRun();
  run.metrics.acceptLatencyMs.p95 = 10; // 小於 med
  assert.match(validateSaturationRun(run)[0], /遞增/);
});

test('k6 掉迭代代表施壓端自己撐不住，該次數據不可用', () => {
  const run = validRun();
  run.metrics.droppedIterations = 37;
  assert.match(validateSaturationRun(run)[0], /droppedIterations/);
});

test('5xx 讓該次執行不合格', () => {
  const run = validRun();
  run.metrics.unexpected5xx = 2;
  assert.match(validateSaturationRun(run)[0], /5xx/);
});

test('沒有記錄連線策略就不合格', () => {
  const run = validRun({ connectionReuse: undefined });
  assert.match(validateSaturationRun(run)[0], /connectionReuse/);
});

test('沒有任何新連線代表量測方式有問題', () => {
  const run = validRun();
  run.metrics.newConnections = 0;
  assert.match(validateSaturationRun(run)[0], /newConnections/);
});

test('achievedRps 必須為正', () => {
  const run = validRun();
  run.metrics.achievedRps = 0;
  assert.match(validateSaturationRun(run)[0], /achievedRps/);
});

test('buildCurve 依副本數與目標速率排序並標出劣化', () => {
  const degradedMetrics = {
    ...validRun().metrics,
    achievedRps: 880,
    acceptLatencyMs: { min: 4, med: 120, p95: 1400, p99: 2100, max: 3000, avg: 300, count: 10 },
  };
  const runs = [
    validRun({ runId: 'r3-900', replicas: 3, targetRate: 900, metrics: degradedMetrics }),
    validRun({ runId: 'r1-300', replicas: 1, targetRate: 300 }),
  ];
  const rows = buildCurve(runs);
  assert.deepEqual(rows.map((row) => row.runId), ['r1-300', 'r3-900']);
  assert.equal(rows[0].degraded, false);
  assert.equal(rows[1].degraded, true); // p95 1400 > 預設門檻 1000
});

test('findSaturationPoints 取每個副本數下仍未劣化的最高吞吐', () => {
  const rows = [
    { runId: 'r3-300', replicas: 3, targetRate: 300, achievedRps: 299, acceptP95Ms: 90, failedRatio: 0, degraded: false },
    { runId: 'r3-600', replicas: 3, targetRate: 600, achievedRps: 598, acceptP95Ms: 180, failedRatio: 0, degraded: false },
    { runId: 'r3-900', replicas: 3, targetRate: 900, achievedRps: 880, acceptP95Ms: 1400, failedRatio: 0, degraded: true },
  ];
  assert.deepEqual(findSaturationPoints(rows), [{ replicas: 3, saturationRps: 598, acceptP95Ms: 180 }]);
});

test('某個副本數完全沒有未劣化的執行時，回報 null 而不是假裝有飽和點', () => {
  const rows = [
    { runId: 'r1-300', replicas: 1, targetRate: 300, achievedRps: 250, acceptP95Ms: 2400, failedRatio: 0, degraded: true },
  ];
  assert.deepEqual(findSaturationPoints(rows), [{ replicas: 1, saturationRps: null, acceptP95Ms: null }]);
});
