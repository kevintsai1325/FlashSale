// 飽和式壓測的資料品質守門與曲線計算。
//
// 為什麼獨立成一支純函式模組：壓測的價值取決於數字可不可信，而「可不可信」是一組明確的
// 不變量，不是看圖說故事。這裡的每一條規則都對應一次真的踩過的坑：
//   - 負延遲 / 分位數順序顛倒 → WSL2 的牆鐘會往回跳（docs/portfolio/wsl2-clock-accuracy.md）
//   - droppedIterations > 0   → 施壓端自己撐不住，achievedRps 不再代表系統能力
//   - newConnections === 0    → kube-proxy 是每條連線分配一次，沒有新連線就量不到負載平衡
//   - 下游指標整段為 0        → 高負載那次歸零、低負載那次有值，是指標停止回報而不是負載消失
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

// 下游取樣的指標欄位。這些值不是 k6 量的，是壓測期間另外對 actuator／psql 取樣來的
// （load-tests/k8s/sample-downstream.ps1），所以它們有一種 k6 指標沒有的失敗模式：
// 指標停止回報時回傳的是 0，跟「真的沒有負載」在檔案裡長得一模一樣。
// P3 就是這樣把 reservationMaxMs 在高負載讀到的 0 寫成「Redis 沒有變慢」，
// 實際上低負載那組同一個指標有 48.9 ms——是回報能力消失，不是延遲消失。
const DOWNSTREAM_FIELDS = ['hikariActive', 'hikariIdle', 'hikariPending', 'pgBackends', 'reservationMaxMs'];

// 「整段為 0」本身不能當成問題：hikariPending 在低速率下本來就整段是 0，那是真的沒有排隊。
// 唯一能把「停止回報」跟「真的是 0」分開的，是同一組副本數裡的速率方向：負載更低的那次
// 量得到非零值，負載更高的這次卻整段為 0——這個方向的變化沒有物理解釋，只有量測解釋。
export function findSilentDownstreamMetrics(runSamples) {
  const problems = [];
  const byReplicas = new Map();
  for (const entry of runSamples) {
    if (!byReplicas.has(entry.replicas)) byReplicas.set(entry.replicas, []);
    byReplicas.get(entry.replicas).push(entry);
  }
  for (const [, group] of [...byReplicas.entries()].sort((a, b) => a[0] - b[0])) {
    group.sort((left, right) => left.targetRate - right.targetRate);
    for (const field of DOWNSTREAM_FIELDS) {
      let lowerRateHadValue = null;
      for (const entry of group) {
        const values = entry.samples.map((sample) => sample[field]).filter((value) => typeof value === 'number');
        if (values.length === 0) {
          problems.push(`${entry.runId}.${field}: ${entry.samples.length} 個樣本全部沒有值，取樣機制本身失敗，不是「沒有負載」`);
          continue;
        }
        if (values.some((value) => value !== 0)) {
          lowerRateHadValue = entry;
          continue;
        }
        if (lowerRateHadValue) {
          problems.push(
            `${entry.runId}.${field}: ${values.length} 個樣本整段為 0，但同組較低速率的 ` +
            `${lowerRateHadValue.runId}（${lowerRateHadValue.targetRate} rps）量得到非零值——` +
            '負載變高反而歸零沒有物理解釋，視為指標停止回報，不可當成量到的 0',
          );
        }
      }
    }
  }
  return problems;
}

export function loadDownstreamSamples(directory) {
  return readdirSync(directory)
    .filter((name) => name.startsWith('downstream-') && name.endsWith('.jsonl'))
    .map((name) => {
      // 取樣腳本用 Add-Content -Encoding UTF8 寫檔，Windows PowerShell 5.1 會在檔首放 BOM。
      // 不剝掉的話第一行 JSON.parse 會直接拋錯，而第一行往往正是壓測開始前的基準樣本。
      const text = readFileSync(join(directory, name), 'utf8').replace(/^\uFEFF/, '');
      const samples = text.split(/\r?\n/).filter((line) => line.trim() !== '').map((line) => JSON.parse(line));
      return { runId: name.slice('downstream-'.length, -'.jsonl'.length), samples };
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
  const samplesByRunId = new Map(loadDownstreamSamples(directory).map((entry) => [entry.runId, entry.samples]));
  const downstreamProblems = findSilentDownstreamMetrics(
    runs
      .filter((run) => samplesByRunId.has(run.runId))
      .map((run) => ({
        runId: run.runId,
        replicas: run.replicas,
        targetRate: run.targetRate,
        samples: samplesByRunId.get(run.runId),
      })),
  );
  if (downstreamProblems.length > 0) {
    console.error('');
    console.error('SUSPECT 下游取樣指標（上面的曲線仍然可用，但這些欄位不可當成量到的值引用）');
    for (const problem of downstreamProblems) console.error(`  - ${problem}`);
  }
  process.exit((rejected > 0 || downstreamProblems.length > 0) ? 1 : 0);
}
