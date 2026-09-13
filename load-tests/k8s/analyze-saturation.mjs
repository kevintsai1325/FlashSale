// 飽和式壓測的資料品質守門與曲線計算。
//
// 為什麼獨立成一支純函式模組：壓測的價值取決於數字可不可信，而「可不可信」是一組明確的
// 不變量，不是看圖說故事。這裡的每一條規則都對應一次真的踩過的坑：
//   - 負延遲 / 分位數順序顛倒 → WSL2 的牆鐘會往回跳（docs/portfolio/wsl2-clock-accuracy.md）
//   - droppedIterations > 0   → 施壓端自己撐不住，achievedRps 不再代表系統能力
//   - newConnections === 0    → kube-proxy 是每條連線分配一次，沒有新連線就量不到負載平衡
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// p95 超過這個值就視為已經劣化。這不是 SLA，只是「曲線在哪裡開始往上翹」的判定門檻。
export const DEFAULT_DEGRADED_P95_MS = 1000;

const LATENCY_FIELDS = ['acceptLatencyMs', 'pollLatencyMs'];
const ORDERED_STATS = ['min', 'med', 'p95', 'p99', 'max'];

function checkLatency(problems, name, stats) {
  if (!stats) {
    problems.push(`${name}: 缺少延遲統計`);
    return;
  }
  for (const key of [...ORDERED_STATS, 'avg']) {
    const value = stats[key];
    if (typeof value !== 'number' || Number.isNaN(value)) {
      problems.push(`${name}.${key}: 不是數字`);
      return;
    }
    if (value < 0) {
      problems.push(`${name}.${key}: 負的延遲 (${value})，時鐘來源有問題`);
      return;
    }
  }
  for (let i = 1; i < ORDERED_STATS.length; i += 1) {
    const previous = stats[ORDERED_STATS[i - 1]];
    const current = stats[ORDERED_STATS[i]];
    if (current < previous) {
      problems.push(`${name}: 分位數未遞增 (${ORDERED_STATS[i - 1]}=${previous} > ${ORDERED_STATS[i]}=${current})`);
      return;
    }
  }
}

export function validateSaturationRun(run) {
  const problems = [];
  if (!run || typeof run !== 'object') {
    return ['執行文件不是物件'];
  }
  for (const field of ['runId', 'replicas', 'targetRate']) {
    if (run[field] === undefined || run[field] === null) {
      problems.push(`缺少必要欄位 ${field}`);
    }
  }
  if (typeof run.connectionReuse !== 'boolean') {
    problems.push('connectionReuse: 沒有記錄連線策略，這筆數據無法解讀負載平衡');
  }
  const metrics = run.metrics || {};
  for (const field of LATENCY_FIELDS) {
    checkLatency(problems, field, metrics[field]);
  }
  if (!(metrics.achievedRps > 0)) {
    problems.push(`achievedRps: 必須為正 (${metrics.achievedRps})`);
  }
  if (!(metrics.newConnections > 0)) {
    problems.push(`newConnections: 為 ${metrics.newConnections}，沒有任何新連線就量不到 kube-proxy 的分配`);
  }
  if (metrics.droppedIterations > 0) {
    problems.push(`droppedIterations=${metrics.droppedIterations}：施壓端自己撐不住，achievedRps 不代表系統能力`);
  }
  if (metrics.unexpected5xx > 0) {
    problems.push(`unexpected5xx=${metrics.unexpected5xx}：執行期間出現 5xx`);
  }
  return problems;
}

export function buildCurve(runs, options = {}) {
  const threshold = options.degradedP95Ms === undefined ? DEFAULT_DEGRADED_P95_MS : options.degradedP95Ms;
  return runs
    .map((run) => {
      const metrics = run.metrics || {};
      const accept = metrics.acceptLatencyMs || {};
      const httpRequests = metrics.httpRequests || 0;
      const failed = metrics.failedRequests || 0;
      return {
        runId: run.runId,
        replicas: run.replicas,
        targetRate: run.targetRate,
        achievedRps: metrics.achievedRps,
        acceptP95Ms: accept.p95,
        failedRatio: httpRequests === 0 ? 0 : failed / httpRequests,
        degraded: accept.p95 > threshold,
      };
    })
    .sort((left, right) => (left.replicas - right.replicas) || (left.targetRate - right.targetRate));
}

export function findSaturationPoints(rows) {
  const byReplicas = new Map();
  for (const row of rows) {
    if (!byReplicas.has(row.replicas)) {
      byReplicas.set(row.replicas, []);
    }
    byReplicas.get(row.replicas).push(row);
  }
  return [...byReplicas.entries()]
    .sort((left, right) => left[0] - right[0])
    .map(([replicas, group]) => {
      const healthy = group.filter((row) => !row.degraded && row.failedRatio === 0);
      if (healthy.length === 0) {
        return { replicas, saturationRps: null, acceptP95Ms: null };
      }
      const best = healthy.reduce((a, b) => (b.achievedRps > a.achievedRps ? b : a));
      return { replicas, saturationRps: best.achievedRps, acceptP95Ms: best.acceptP95Ms };
    });
}

export function loadRuns(directory) {
  return readdirSync(directory)
    .filter((name) => name.startsWith('saturation-') && name.endsWith('.json'))
    .map((name) => JSON.parse(readFileSync(join(directory, name), 'utf8')));
}

const isDirectRun = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isDirectRun) {
  const directory = process.argv[2];
  if (!directory) {
    console.error('用法：node load-tests/k8s/analyze-saturation.mjs <結果目錄>');
    process.exit(2);
  }
  const runs = loadRuns(directory);
  if (runs.length === 0) {
    console.error(`${directory} 底下沒有 saturation-*.json`);
    process.exit(2);
  }
  let rejected = 0;
  const accepted = [];
  for (const run of runs) {
    const problems = validateSaturationRun(run);
    if (problems.length > 0) {
      rejected += 1;
      console.error(`REJECTED ${run.runId}`);
      for (const problem of problems) console.error(`  - ${problem}`);
    } else {
      accepted.push(run);
    }
  }
  const rows = buildCurve(accepted);
  console.log('replicas  targetRate  achievedRps  acceptP95Ms  failed%  degraded');
  for (const row of rows) {
    console.log(
      String(row.replicas).padEnd(10) +
      String(row.targetRate).padEnd(12) +
      row.achievedRps.toFixed(1).padEnd(13) +
      row.acceptP95Ms.toFixed(1).padEnd(13) +
      (row.failedRatio * 100).toFixed(2).padEnd(9) +
      (row.degraded ? 'yes' : 'no'),
    );
  }
  console.log('');
  for (const point of findSaturationPoints(rows)) {
    if (point.saturationRps === null) {
      console.log(`replicas=${point.replicas}: 沒有任何未劣化的執行，量不到飽和點`);
    } else {
      console.log(`replicas=${point.replicas}: 飽和點 ${point.saturationRps.toFixed(1)} RPS（accept p95 ${point.acceptP95Ms.toFixed(1)} ms）`);
    }
  }
  process.exit(rejected > 0 ? 1 : 0);
}
