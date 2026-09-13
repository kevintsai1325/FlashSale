# Week 8 P3：飽和式壓測方法論與自動擴縮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立一套能量出系統飽和點的壓測工具，用它產出 `replicas` 1 / 3 / 5 的「RPS 對 p95」曲線與下游瓶頸證據，再據此加上 HPA 與 PodDisruptionBudget 並實測它們是否跟得上尖峰。

**Architecture:** 壓測改用 k6 的 `ramping-arrival-rate`（開放模型，到達率外生、與後端副本數無關），施壓端跑在 Windows 上以取得準確時鐘，經壓測專用的 NodePort 直接打 backend Service（不經 Nginx 與 TLS）。量測期間同步從 Windows 取樣下游指標（Hikari 連線池、Postgres 連線數、Redis 預扣延遲）。曲線與資料品質由一支獨立的 Node 分析程式驗證，它才是「這份數據可不可信」的守門。HPA 與 PDB 在有飽和點之後才加，並以實測數字回答「HPA 跟不跟得上」。

**Tech Stack:** k6 v2.2.0（Windows 原生）、Node 20+（`node --test`）、PowerShell 5.1、kubectl 1.36、k3s v1.36.3、autoscaling/v2 HPA、policy/v1 PDB、metrics-server（叢集內已存在）

**Spec:** `docs/superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md`（P3 章節見「壓測方法論與自動擴縮（P3）」，驗收條件見「驗收 → P3」）

## Global Constraints

- **施壓端放 Windows**：VM 內的時鐘實測比真實時間快約 3.5%，Windows 主機時鐘已驗證正確（`docs/portfolio/wsl2-clock-accuracy.md`）。
- **不得製造超過 200 條「同時建立」的新連線**：Rancher Desktop 的中繼行程上限約 210 條，超過的部分在 TCP 握手階段被 RST。持續新連線速率 1,200/秒 實測 0 失敗。arrival-rate 模型天生符合這個限制。
- **量測邊界固定為 `http://localhost:30880`**（`k8s/loadtest/backend-nodeport.yaml`），直接打 backend Service，不經 Nginx 與 TLS，讓 kube-proxy 的分配成為被量測的對象。
- **壓測用的 NodePort 不得進入 `k8s/base/kustomization.yaml`**；`scripts/tests/k8s-manifests-test.ps1` 已有守門。
- **所有含非 ASCII 的 `.ps1` 必須是 UTF-8 with BOM**；CI 的 `scripts` job 會擋（`node scripts/tests/ps1-encoding.mjs`，可用 `--fix` 修）。
- **不要用 PowerShell 讀寫含非 ASCII 的檔案**（`Get-Content -Raw | Set-Content` 會損毀中文）；需要改檔就用 Node 或 Python 並明確指定 `encoding="utf-8"`。
- **kubectl 一律帶 `--context rancher-desktop -n flashsale`**。
- **JWT 金鑰與密碼不得寫進 repository**；本機金鑰位於 `C:\SideProject\flashsale-secrets\`。
- **每個實驗結束都要把環境還原**：`replicas` 回到 3、刪除 HPA、刪除壓測 NodePort，並讓 `scripts/k8s/verify.ps1` 通過。

---

## File Structure

| 檔案 | 責任 |
|---|---|
| `load-tests/k8s/fixtures-saturation.sql` | 飽和測試用的資料：庫存極大、每人購買上限極大，讓每次請求都真的走完 Redis 預扣路徑，而不是第二次就被擋成 `REJECTED` |
| `load-tests/k8s/saturation.js` | k6 `ramping-arrival-rate` 腳本。分 leg 標記、用預先產生的 token、輸出固定 schema 的 JSON |
| `load-tests/k8s/analyze-saturation.mjs` | 純函式：驗證單次執行的資料品質、把多次執行併成曲線、找出飽和點。同時是 CLI |
| `load-tests/k8s/analyze-saturation.test.mjs` | 上者的單元測試（`node --test`） |
| `load-tests/k8s/sample-downstream.ps1` | 壓測期間從 Windows 每秒取樣下游指標，輸出 JSON Lines |
| `load-tests/k8s/run-saturation.ps1` | 串起整個流程：縮放、種資料、產 token、套 NodePort、開取樣、逐階段跑 k6、收檔、還原 |
| `load-tests/k8s/watch-scaling.ps1` | HPA 實驗的儀器：每秒記錄期望／就緒副本數與 CPU 使用率 |
| `k8s/base/availability.yaml` | backend 的 PodDisruptionBudget（屬於常態設定，進 base） |
| `k8s/autoscaling/hpa.yaml` | backend 的 HPA（**刻意不進 base**，理由見 Task 7） |
| `docs/portfolio/data/k8s-saturation-results.json` | 精選後的量測結果（`load-tests/k8s/results/` 被 gitignore） |
| `docs/portfolio/scaling-and-autoscaling.md` | P3 的作品集文件：曲線、瓶頸指認、HPA 與預先擴容的對照、PDB 證據 |

既有且會被沿用、不修改的：`load-tests/benchmark/prepare.js`（產 token，已支援 `BASE_URL` / `USERS` / `RUN_ID` / `TOKENS_OUT`）、`load-tests/k8s/ensure-metrics-admin.sh`（建立可讀 `/actuator/metrics` 的管理員）、`k8s/loadtest/backend-nodeport.yaml`。

---

## Task 1: 飽和測試的資料品質守門（分析器）

先做分析器而不是壓測腳本，因為它定義了「一次合格的執行長什麼樣」。壓測腳本接著照這個 schema 產出。

**Files:**
- Create: `load-tests/k8s/analyze-saturation.mjs`
- Test: `load-tests/k8s/analyze-saturation.test.mjs`

**Interfaces:**
- Consumes: 無
- Produces:
  - `validateSaturationRun(run) -> string[]`（空陣列代表合格）
  - `buildCurve(runs, options?) -> Array<{runId, replicas, targetRate, achievedRps, acceptP95Ms, failedRatio, degraded}>`
  - `findSaturationPoints(rows) -> Array<{replicas, saturationRps, acceptP95Ms}>`
  - `loadRuns(directory) -> object[]`
  - 執行結果的 JSON schema（下方 fixture 即是規格）

- [ ] **Step 1: 寫下會失敗的測試**

建立 `load-tests/k8s/analyze-saturation.test.mjs`：

```js
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
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `node --test load-tests/k8s/analyze-saturation.test.mjs`
Expected: FAIL，訊息為 `Cannot find module ... analyze-saturation.mjs`

- [ ] **Step 3: 實作分析器**

建立 `load-tests/k8s/analyze-saturation.mjs`：

```js
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
```

- [ ] **Step 4: 執行測試確認通過**

Run: `node --test load-tests/k8s/analyze-saturation.test.mjs`
Expected: PASS，10 個測試全過

- [ ] **Step 5: Commit**

```bash
git add load-tests/k8s/analyze-saturation.mjs load-tests/k8s/analyze-saturation.test.mjs
git commit -m "test: 飽和式壓測的資料品質守門與曲線計算"
```

---

## Task 2: 飽和壓測腳本

**Files:**
- Create: `load-tests/k8s/fixtures-saturation.sql`
- Create: `load-tests/k8s/saturation.js`

**Interfaces:**
- Consumes: Task 1 定義的執行文件 schema；`load-tests/benchmark/prepare.js` 產出的 `{users: [{email, token}]}` token 檔
- Produces: `saturation.js`，以環境變數 `BASE_URL` / `TOKENS_FILE` / `RUN_ID` / `REPLICAS` / `TARGET_RATE` / `START_RATE` / `RAMP_SECONDS` / `HOLD_SECONDS` / `PRE_ALLOCATED_VUS` / `MAX_VUS` / `NO_CONNECTION_REUSE` / `SUMMARY_OUT` / `FLASH_SALE_ID` 驅動

- [ ] **Step 1: 建立飽和測試的資料**

`load-tests/k8s/fixtures-saturation.sql`：

```sql
-- 飽和式壓測的資料。與 load-tests/README.md Step 1 的「搶購」資料不同，這裡刻意讓
-- 庫存與每人購買上限都極大 —— 目的是量吞吐上限，不是量搶購競爭。
--
-- 若沿用 purchase_limit_per_user = 1，同一個 token 第二次購買就會被擋成 REJECTED，
-- 那條路徑不會碰到 Redis 預扣，量到的延遲會愈跑愈低，看起來像「系統變快了」。
TRUNCATE TABLE purchase_requests, order_items, orders, inventory, flash_sales, products, users RESTART IDENTITY CASCADE;

INSERT INTO products (id, name, description)
VALUES (1, 'Saturation Test Item', 'Large stock, used only for throughput measurement');

INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '8 hours', 1000000, 'ACTIVE');

INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 5000000, 5000000, 0, 0, 0);
```

- [ ] **Step 2: 寫飽和壓測腳本**

`load-tests/k8s/saturation.js`：

```js
// 飽和式壓測：固定到達率、逐步加壓，量「吞吐上限在哪裡、p95 在哪個 RPS 開始翹起來」。
//
// 與 load-tests/purchase-flow.js 的差別是模型本身：
//   purchase-flow.js  per-vu-iterations（封閉模型）——負載總量由 VU 數決定，與副本數無關，
//                     所以副本再多也不會有更多請求進來。P1 量到「三副本 p95 慢 41%、吞吐持平」
//                     正是這個模型造成的假象。
//   saturation.js     ramping-arrival-rate（開放模型）——到達率是外生的，系統跟不上就會
//                     排隊、延遲上升，這才量得出擴展的價值。
//
// 量測邊界：直接打 backend Service 的 NodePort，不經 Nginx 與 TLS。
// 認證流量以 prepare.js 預先產生的 token 移出量測區間。
//
//   k6 run saturation.js -e BASE_URL=http://localhost:30880 -e TARGET_RATE=600 \
//     -e REPLICAS=3 -e RUN_ID=saturation-r3-600 -e TOKENS_FILE=<abs>/tokens.json \
//     -e SUMMARY_OUT=<abs>/saturation-r3-600.json
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:30880';
const RUN_ID = __ENV.RUN_ID || 'adhoc';
const REPLICAS = parseInt(__ENV.REPLICAS || '3', 10);
const TARGET_RATE = parseInt(__ENV.TARGET_RATE || '300', 10);
const START_RATE = parseInt(__ENV.START_RATE || '50', 10);
const RAMP_SECONDS = parseInt(__ENV.RAMP_SECONDS || '30', 10);
const HOLD_SECONDS = parseInt(__ENV.HOLD_SECONDS || '60', 10);
const PRE_ALLOCATED_VUS = parseInt(__ENV.PRE_ALLOCATED_VUS || '200', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '600', 10);
const NO_CONNECTION_REUSE = __ENV.NO_CONNECTION_REUSE === 'true';
const FLASH_SALE_ID = __ENV.FLASH_SALE_ID || '1';
const TOKENS_FILE = __ENV.TOKENS_FILE || './tokens.json';
const SUMMARY_OUT = __ENV.SUMMARY_OUT || `saturation-${RUN_ID}.json`;

const acceptLatency = new Trend('saturation_accept_ms');
const pollLatency = new Trend('saturation_poll_ms');
const non202 = new Counter('saturation_non_202');
const unexpected5xx = new Counter('saturation_5xx');
const missingToken = new Counter('saturation_missing_token');

function loadUsers(path) {
  try {
    const parsed = JSON.parse(open(path));
    return parsed && parsed.users ? parsed.users : [];
  } catch (error) {
    return [];
  }
}

const users = loadUsers(TOKENS_FILE);

export const options = {
  scenarios: {
    saturation: {
      executor: 'ramping-arrival-rate',
      startRate: START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: TARGET_RATE, duration: `${RAMP_SECONDS}s` },
        { target: TARGET_RATE, duration: `${HOLD_SECONDS}s` },
      ],
    },
  },
  // 預設重用連線。理由：Rancher Desktop 的中繼層只容得下約 210 條「同時建立中」的新連線，
  // 而 kube-proxy 是每條連線分配一次，所以連線策略同時影響「打不打得進去」與「量不量得到
  // 負載平衡」。這個旗標會被寫進結果檔，讓讀數字的人知道當時是哪一種。
  noConnectionReuse: NO_CONNECTION_REUSE,
  thresholds: {},
};

export function setup() {
  if (users.length === 0) {
    throw new Error(`${TOKENS_FILE} 沒有任何 token；請先執行 load-tests/benchmark/prepare.js`);
  }
  console.log(`saturation ${RUN_ID}: replicas=${REPLICAS} targetRate=${TARGET_RATE} users=${users.length} baseUrl=${BASE_URL}`);
  return { startedAt: new Date().toISOString() };
}

export default function () {
  const user = users[Math.floor(Math.random() * users.length)];
  if (!user || !user.token) {
    missingToken.add(1);
    return;
  }
  const idempotencyKey = `${RUN_ID}-${__VU}-${__ITER}`;
  const response = http.post(
    `${BASE_URL}/api/flash-sales/${FLASH_SALE_ID}/purchase-requests`,
    JSON.stringify({ quantity: 1 }),
    {
      headers: {
        Authorization: `Bearer ${user.token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: { leg: 'accept' },
    },
  );
  acceptLatency.add(response.timings.duration);
  if (response.status >= 500) {
    unexpected5xx.add(1);
    return;
  }
  if (response.status !== 202) {
    non202.add(1);
    return;
  }
  // 只輪詢一次。飽和測試量的是同步腳的吞吐上限；完整的非同步完成時間由既有的
  // benchmark soak 負責，在這裡持續輪詢只會讓施壓端自己成為瓶頸。
  let requestId;
  try {
    requestId = response.json('requestId');
  } catch (error) {
    non202.add(1);
    return;
  }
  const poll = http.get(`${BASE_URL}/api/purchase-requests/${requestId}`, {
    headers: { Authorization: `Bearer ${user.token}` },
    tags: { leg: 'poll' },
  });
  pollLatency.add(poll.timings.duration);
  if (poll.status >= 500) {
    unexpected5xx.add(1);
  }
}

function trendOf(metrics, name) {
  const values = metrics[name] && metrics[name].values ? metrics[name].values : {};
  return {
    min: values.min === undefined ? 0 : values.min,
    med: values.med === undefined ? 0 : values.med,
    p95: values['p(95)'] === undefined ? 0 : values['p(95)'],
    p99: values['p(99)'] === undefined ? 0 : values['p(99)'],
    max: values.max === undefined ? 0 : values.max,
    avg: values.avg === undefined ? 0 : values.avg,
    count: values.count === undefined ? 0 : values.count,
  };
}

function counterOf(metrics, name) {
  const values = metrics[name] && metrics[name].values ? metrics[name].values : {};
  return values.count === undefined ? 0 : values.count;
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values ? metrics.http_reqs.values : {};
  const failed = metrics.http_req_failed && metrics.http_req_failed.values ? metrics.http_req_failed.values : {};
  const summary = {
    runId: RUN_ID,
    kind: 'saturation',
    replicas: REPLICAS,
    targetRate: TARGET_RATE,
    startRate: START_RATE,
    rampSeconds: RAMP_SECONDS,
    holdSeconds: HOLD_SECONDS,
    connectionReuse: !NO_CONNECTION_REUSE,
    preAllocatedVUs: PRE_ALLOCATED_VUS,
    maxVUs: MAX_VUS,
    startedAt: data.setup_data ? data.setup_data.startedAt : null,
    finishedAt: new Date().toISOString(),
    metrics: {
      acceptLatencyMs: trendOf(metrics, 'saturation_accept_ms'),
      pollLatencyMs: trendOf(metrics, 'saturation_poll_ms'),
      achievedRps: httpReqs.rate === undefined ? 0 : httpReqs.rate,
      httpRequests: httpReqs.count === undefined ? 0 : httpReqs.count,
      // http_req_connecting 的 count 是「有經歷連線建立階段的請求數」，也就是新連線數。
      // 這是規格要求「把連線數記進結果檔」的具體做法。
      newConnections: counterOf(metrics, 'http_req_connecting'),
      droppedIterations: counterOf(metrics, 'dropped_iterations'),
      failedRequests: failed.passes === undefined ? 0 : failed.passes,
      non202Responses: counterOf(metrics, 'saturation_non_202'),
      unexpected5xx: counterOf(metrics, 'saturation_5xx'),
    },
  };
  return { [SUMMARY_OUT]: JSON.stringify(summary, null, 2) };
}
```

- [ ] **Step 3: 冒煙執行（低速率），確認 schema 與分析器相容**

```bash
kubectl --context rancher-desktop -n flashsale exec -i postgres-0 -- psql -U flashsale -d flashsale < load-tests/k8s/fixtures-saturation.sql
kubectl --context rancher-desktop -n flashsale exec redis-0 -- redis-cli DEL stock:1
kubectl --context rancher-desktop apply -f k8s/loadtest/backend-nodeport.yaml
mkdir -p /tmp/p3-smoke
k6 run load-tests/benchmark/prepare.js -e USERS=20 -e RUN_ID=smoke \
  -e BASE_URL=http://localhost:30880 -e BENCH_PASSWORD=SmokeTest123! \
  -e TOKENS_OUT=/tmp/p3-smoke/tokens.json
k6 run load-tests/k8s/saturation.js -e BASE_URL=http://localhost:30880 \
  -e RUN_ID=saturation-smoke -e REPLICAS=3 -e TARGET_RATE=30 -e START_RATE=10 \
  -e RAMP_SECONDS=5 -e HOLD_SECONDS=10 -e TOKENS_FILE=/tmp/p3-smoke/tokens.json \
  -e SUMMARY_OUT=/tmp/p3-smoke/saturation-smoke.json
node load-tests/k8s/analyze-saturation.mjs /tmp/p3-smoke
```

Expected: 分析器印出一行曲線且 exit code 0。若印出 `REJECTED`，照它列出的不變量修 `saturation.js`，**不要改守門的門檻**。

- [ ] **Step 4: 收尾並 commit**

```bash
kubectl --context rancher-desktop delete -f k8s/loadtest/backend-nodeport.yaml
git add load-tests/k8s/saturation.js load-tests/k8s/fixtures-saturation.sql
git commit -m "feat: 以 ramping-arrival-rate 量測飽和點的壓測腳本"
```

---

## Task 3: 下游指標取樣

規格要求「同時記錄下游指標（Postgres 連線數、Hikari 池使用率、Redis 延遲），指認真正的瓶頸位置」。取樣端同樣放 Windows，時間戳才可信。

**Files:**
- Create: `load-tests/k8s/sample-downstream.ps1`

**Interfaces:**
- Consumes: `load-tests/k8s/ensure-metrics-admin.sh` 建立的管理員帳號
- Produces: JSON Lines 檔，每行 `{sampledAt, hikariActive, hikariIdle, hikariPending, pgBackends, reservationMaxMs}`

- [ ] **Step 1: 寫取樣腳本**

`load-tests/k8s/sample-downstream.ps1`（**必須存成 UTF-8 with BOM**）：

```powershell
<#
.SYNOPSIS
    壓測期間每秒取樣一次下游指標，輸出 JSON Lines。

.DESCRIPTION
    P1 量到「加副本沒有讓吞吐變好」，最可能的解釋是瓶頸在下游而不是 backend 本身。
    要證實或否定它，就必須在加壓的同時看到連線池與資料庫的狀態。

    取樣端刻意放在 Windows：時間戳才與 k6 的量測區間對得起來（VM 內的時鐘快約 3.5%）。

    需要一個具 ADMIN 角色的帳號才能讀 /actuator/metrics，先跑：
        ADMIN_PASSWORD=... ./load-tests/k8s/ensure-metrics-admin.sh
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Gateway = 'https://localhost:8443',
    [string]$AdminEmail = 'metrics-admin@example.com',
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [int]$IntervalSeconds = 1,
    [int]$DurationSeconds = 180
)

# 不用 $ErrorActionPreference = 'Stop'：本腳本呼叫原生 kubectl，PowerShell 5.1 在 Stop 模式下
# 會把原生命令的 stderr 每一行包成 ErrorRecord 並中斷，即使結束碼是 0。

[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }

function Get-AccessToken {
    $body = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Uri "$Gateway/api/auth/login" -Method Post -Body $body -ContentType 'application/json'
    return $response.accessToken
}

function Get-MetricValue {
    param([string]$Token, [string]$Metric, [string]$Statistic = 'VALUE')
    try {
        $response = Invoke-RestMethod -Uri "$Gateway/actuator/metrics/$Metric" -Headers @{ Authorization = "Bearer $Token" }
        $measurement = $response.measurements | Where-Object { $_.statistic -eq $Statistic } | Select-Object -First 1
        if ($null -eq $measurement) { return $null }
        return [double]$measurement.value
    } catch {
        return $null
    }
}

function Get-PostgresBackends {
    $output = & kubectl --context $Context -n $Namespace exec postgres-0 -- psql -U flashsale -d flashsale -t -A -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'flashsale';" 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    $text = ($output | Out-String).Trim()
    $parsed = 0
    if ([int]::TryParse($text, [ref]$parsed)) { return $parsed }
    return $null
}

$token = Get-AccessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw "無法以 $AdminEmail 登入，請先執行 ensure-metrics-admin.sh" }

$deadline = (Get-Date).AddSeconds($DurationSeconds)
New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
Write-Host "取樣中：每 $IntervalSeconds 秒一次，共 $DurationSeconds 秒 -> $OutputPath"

while ((Get-Date) -lt $deadline) {
    $reservationSeconds = Get-MetricValue -Token $token -Metric 'purchase.reservation.latency' -Statistic 'MAX'
    $reservationMs = $null
    if ($null -ne $reservationSeconds) { $reservationMs = $reservationSeconds * 1000 }
    $sample = [pscustomobject]@{
        sampledAt        = (Get-Date).ToUniversalTime().ToString('o')
        hikariActive     = Get-MetricValue -Token $token -Metric 'hikaricp.connections.active'
        hikariIdle       = Get-MetricValue -Token $token -Metric 'hikaricp.connections.idle'
        hikariPending    = Get-MetricValue -Token $token -Metric 'hikaricp.connections.pending'
        pgBackends       = Get-PostgresBackends
        reservationMaxMs = $reservationMs
    }
    ($sample | ConvertTo-Json -Compress) | Add-Content -LiteralPath $OutputPath -Encoding UTF8
    Start-Sleep -Seconds $IntervalSeconds
}

Write-Host "取樣結束：$OutputPath"
```

- [ ] **Step 2: 通過編碼守門**

Run: `node scripts/tests/ps1-encoding.mjs`
Expected: PASS。若報 `missing-bom`，執行 `node scripts/tests/ps1-encoding.mjs --fix`

- [ ] **Step 3: 實際取樣 20 秒，確認欄位不是一整排 null**

```bash
ADMIN_PASSWORD='MetricsAdmin123!' ./load-tests/k8s/ensure-metrics-admin.sh
powershell -ExecutionPolicy Bypass -File load-tests/k8s/sample-downstream.ps1 \
  -OutputPath /tmp/p3-smoke/downstream.jsonl -AdminPassword 'MetricsAdmin123!' -DurationSeconds 20
node -e "const fs=require('fs');const rows=fs.readFileSync('/tmp/p3-smoke/downstream.jsonl','utf8').trim().split(/\r?\n/).map(JSON.parse);console.log('samples',rows.length);console.log('null 欄位的樣本數',rows.filter(s=>s.hikariActive===null||s.pgBackends===null).length);"
```

Expected: `samples` 約 20，`null 欄位的樣本數` 為 0。若 Hikari 指標為 null，先用下列指令確認實際的指標名稱再修腳本：

```bash
kubectl --context rancher-desktop -n flashsale run metric-probe --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  sh -c 'curl -s http://backend:8080/actuator/metrics' 
```

- [ ] **Step 4: Commit**

```bash
git add load-tests/k8s/sample-downstream.ps1
git commit -m "feat: 壓測期間的下游指標取樣"
```

---

## Task 4: 壓測流程編排

**Files:**
- Create: `load-tests/k8s/run-saturation.ps1`

**Interfaces:**
- Consumes: Task 1–3 的全部產物
- Produces: `load-tests/k8s/results/saturation-<timestamp>/` 底下的 `saturation-r<replicas>-<rate>.json`（每個速率一個）、`downstream-<runId>.jsonl`、`tokens.json`、`run.json`

- [ ] **Step 1: 寫編排腳本**

`load-tests/k8s/run-saturation.ps1`（UTF-8 with BOM）：

```powershell
<#
.SYNOPSIS
    跑一組飽和式壓測：同一個副本數下逐階段提高到達率，每階段產出一份結果檔。

.DESCRIPTION
    流程：設定副本數 -> 等就緒 -> 種資料 -> 清 Redis -> 建管理員 -> 產 token -> 套 NodePort ->
    每個速率各跑一次（同時背景取樣下游指標）-> 收檔 -> 刪 NodePort。

    施壓端在 Windows，量測邊界是 NodePort 直達 backend Service。
    不要把 StartRate 設到 200 以上：那個區間會撞上 Rancher Desktop 中繼層的同時連線上限
    （約 210 條），量到的不是系統行為（見 docs/portfolio/wsl2-clock-accuracy.md）。

.EXAMPLE
    ./load-tests/k8s/run-saturation.ps1 -Replicas 3 -Rates 150,300,600,900 -AdminPassword 'MetricsAdmin123!'
#>
[CmdletBinding()]
param(
    [int]$Replicas = 3,
    [int[]]$Rates = @(150, 300, 600, 900),
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [string]$BenchPassword = 'SaturationBench123!',
    [int]$Users = 200,
    [int]$StartRate = 50,
    [int]$RampSeconds = 30,
    [int]$HoldSeconds = 60,
    [int]$PreAllocatedVUs = 200,
    [int]$MaxVUs = 600,
    [int]$NodePort = 30880,
    [switch]$SkipScaling,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [string]$OutputDirectory
)

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$baseUrl = "http://localhost:$NodePort"
$serviceApplied = $false

function Invoke-Kubectl {
    param([string[]]$Arguments)
    $output = & kubectl --context $Context @Arguments 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output | Out-String).Trim() }
}

function Resolve-K6 {
    $command = Get-Command k6 -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $fallback = Join-Path $HOME 'bin\k6.exe'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    throw 'k6 not found on PATH.'
}

try {
    if ($StartRate -ge 200) {
        throw "StartRate=$StartRate 會在起步就產生逼近中繼層上限的同時連線；請用 200 以下的值。"
    }
    $k6 = Resolve-K6
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
        $OutputDirectory = Join-Path $repoRoot "load-tests\k8s\results\saturation-$stamp"
    }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

    if (-not $SkipScaling) {
        Write-Host "縮放 backend 至 $Replicas 個副本"
        $scale = Invoke-Kubectl -Arguments @('-n', $Namespace, 'scale', 'deployment/backend', "--replicas=$Replicas")
        if ($scale.ExitCode -ne 0) { throw $scale.Output }
        $wait = Invoke-Kubectl -Arguments @('-n', $Namespace, 'rollout', 'status', 'deployment/backend', '--timeout=240s')
        if ($wait.ExitCode -ne 0) { throw $wait.Output }
    } else {
        Write-Host '-SkipScaling：不動副本數（HPA 實驗時用這個）'
    }

    Write-Host '種入飽和測試資料'
    $fixture = Join-Path $repoRoot 'load-tests\k8s\fixtures-saturation.sql'
    Get-Content -LiteralPath $fixture -Raw | & kubectl --context $Context -n $Namespace exec -i postgres-0 -- psql -U flashsale -d flashsale -q
    if ($LASTEXITCODE -ne 0) { throw '種資料失敗' }
    Invoke-Kubectl -Arguments @('-n', $Namespace, 'exec', 'redis-0', '--', 'redis-cli', 'DEL', 'stock:1') | Out-Null

    $apply = Invoke-Kubectl -Arguments @('apply', '-f', (Join-Path $repoRoot 'k8s\loadtest\backend-nodeport.yaml'))
    if ($apply.ExitCode -ne 0) { throw $apply.Output }
    $serviceApplied = $true

    Write-Host '重建 metrics 管理員（種資料時 users 表被清空了）'
    $env:ADMIN_PASSWORD = $AdminPassword
    & bash (Join-Path $repoRoot 'load-tests/k8s/ensure-metrics-admin.sh')
    if ($LASTEXITCODE -ne 0) { throw 'ensure-metrics-admin.sh 失敗' }

    $tokensPath = Join-Path $OutputDirectory 'tokens.json'
    Write-Host "預先產生 $Users 個買家 token"
    & $k6 run (Join-Path $repoRoot 'load-tests\benchmark\prepare.js') `
        -e "USERS=$Users" -e 'RUN_ID=saturation' -e "BASE_URL=$baseUrl" `
        -e "BENCH_PASSWORD=$BenchPassword" -e "TOKENS_OUT=$tokensPath"
    if ($LASTEXITCODE -ne 0) { throw 'prepare.js 失敗' }

    foreach ($rate in $Rates) {
        $runId = "saturation-r$Replicas-$rate"
        Write-Host ''
        Write-Host "=== $runId ==="
        $summaryPath = Join-Path $OutputDirectory "$runId.json"
        $downstreamPath = Join-Path $OutputDirectory "downstream-$runId.jsonl"
        $totalSeconds = $RampSeconds + $HoldSeconds + 10
        $samplerScript = Join-Path $repoRoot 'load-tests\k8s\sample-downstream.ps1'

        $sampler = Start-Job -ScriptBlock {
            param($script, $out, $password, $seconds)
            powershell -NoProfile -ExecutionPolicy Bypass -File $script -OutputPath $out -AdminPassword $password -DurationSeconds $seconds
        } -ArgumentList $samplerScript, $downstreamPath, $AdminPassword, $totalSeconds

        & $k6 run --no-color (Join-Path $repoRoot 'load-tests\k8s\saturation.js') `
            -e "BASE_URL=$baseUrl" -e "RUN_ID=$runId" -e "REPLICAS=$Replicas" `
            -e "TARGET_RATE=$rate" -e "START_RATE=$StartRate" `
            -e "RAMP_SECONDS=$RampSeconds" -e "HOLD_SECONDS=$HoldSeconds" `
            -e "PRE_ALLOCATED_VUS=$PreAllocatedVUs" -e "MAX_VUS=$MaxVUs" `
            -e "TOKENS_FILE=$tokensPath" -e "SUMMARY_OUT=$summaryPath"
        $k6ExitCode = $LASTEXITCODE

        Wait-Job $sampler -Timeout ($totalSeconds + 30) | Out-Null
        Receive-Job $sampler | Out-Null
        Remove-Job $sampler -Force

        if ($k6ExitCode -ne 0) { Write-Warning "$runId 的 k6 結束碼為 $k6ExitCode" }
    }

    [pscustomobject]@{
        finishedAt  = (Get-Date).ToUniversalTime().ToString('o')
        replicas    = $Replicas
        rates       = $Rates
        users       = $Users
        startRate   = $StartRate
        rampSeconds = $RampSeconds
        holdSeconds = $HoldSeconds
        loadSource  = 'windows-host'
        clockSource = 'windows'
        baseUrl     = $baseUrl
        gitSha      = (& git -C $repoRoot rev-parse HEAD 2>$null)
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'run.json') -Encoding UTF8

    Write-Host ''
    Write-Host "結果目錄：$OutputDirectory"
    Write-Host "接著執行：node load-tests/k8s/analyze-saturation.mjs `"$OutputDirectory`""
} finally {
    if ($serviceApplied) {
        Invoke-Kubectl -Arguments @('delete', '-f', (Join-Path $repoRoot 'k8s\loadtest\backend-nodeport.yaml'), '--ignore-not-found') | Out-Null
    }
}
```

- [ ] **Step 2: 通過編碼守門**

Run: `node scripts/tests/ps1-encoding.mjs`
Expected: PASS

- [ ] **Step 3: 用兩個低速率做端到端驗證**

```bash
powershell -ExecutionPolicy Bypass -File load-tests/k8s/run-saturation.ps1 \
  -Replicas 3 -Rates 50,100 -RampSeconds 10 -HoldSeconds 20 -Users 50 \
  -AdminPassword 'MetricsAdmin123!'
```

Expected: 產出兩個 `saturation-r3-*.json` 與兩個 `downstream-*.jsonl`；結束後 `kubectl --context rancher-desktop -n flashsale get svc` 不再有 `backend-loadtest`。

- [ ] **Step 4: 確認分析器接受這批資料**

Run: `node load-tests/k8s/analyze-saturation.mjs <上一步的結果目錄>`
Expected: 印出兩行曲線、exit code 0

- [ ] **Step 5: Commit**

```bash
git add load-tests/k8s/run-saturation.ps1
git commit -m "feat: 飽和式壓測的流程編排"
```

---

## Task 5: replicas 1 / 3 / 5 的曲線與瓶頸指認

這是 P3 第一部分的產出，也是回答「水平擴展有沒有用」的證據。

**Files:**
- Create: `docs/portfolio/data/k8s-saturation-results.json`

**Interfaces:**
- Consumes: Task 4 的 `run-saturation.ps1`、Task 1 的分析器
- Produces: 精選結果檔，schema 為 `{environment, runs, curve, saturationPoints, downstreamPeaks}`

- [ ] **Step 1: 跑三組（每組四個速率）**

```bash
for r in 1 3 5; do
  powershell -ExecutionPolicy Bypass -File load-tests/k8s/run-saturation.ps1 \
    -Replicas $r -Rates 150,300,600,900 -AdminPassword 'MetricsAdmin123!'
done
```

三組跑完後把所有 `saturation-*.json` 複製進同一個目錄再分析。每組之間不要改動叢集上的其他東西。

- [ ] **Step 2: 分析並確認資料合格**

Run: `node load-tests/k8s/analyze-saturation.mjs <合併後的目錄>`
Expected: 沒有任何 `REJECTED`；印出 12 行曲線與三個飽和點。

若出現 `droppedIterations`，代表施壓端撐不住而不是系統撐不住：提高 `-PreAllocatedVUs` 與 `-MaxVUs` 後重跑該速率，**不要**把它當成系統的飽和點。

- [ ] **Step 3: 從下游取樣指認瓶頸**

```bash
node -e "
const fs=require('fs');
const rows=fs.readFileSync(process.argv[1],'utf8').trim().split(/\r?\n/).map(JSON.parse);
const peak=(k)=>Math.max(...rows.map(s=>s[k]||0));
console.log('hikariActive peak', peak('hikariActive'));
console.log('hikariPending peak', peak('hikariPending'));
console.log('pgBackends peak', peak('pgBackends'));
console.log('reservationMaxMs peak', peak('reservationMaxMs'));
" <飽和速率那一次的 downstream-*.jsonl>
```

判讀原則：`hikariPending` 持續大於 0 表示工作執行緒在等連線池，瓶頸在 Postgres 這一側（連線池上限是 30）；若 `hikariActive` 始終遠低於 30 而 `reservationMaxMs` 上升，瓶頸在 Redis 預扣或 backend 本身。把結論寫進 Task 8 的文件。

- [ ] **Step 4: 寫入精選結果檔**

用 Node 產生 `docs/portfolio/data/k8s-saturation-results.json`（不要用 PowerShell 寫含非 ASCII 的檔）。
把下列內容存成 repository 根目錄的 `collect-saturation.mjs`（暫用，收檔後刪掉，不要提交），
再從根目錄執行 `node collect-saturation.mjs <合併後的目錄> <飽和那次的 downstream jsonl>`
—— 放在根目錄是為了讓下面這行相對匯入成立：

```js
import { writeFileSync, readFileSync } from 'node:fs';
import { loadRuns, buildCurve, findSaturationPoints, validateSaturationRun } from './load-tests/k8s/analyze-saturation.mjs';

const [directory, downstreamPath] = process.argv.slice(2);
const runs = loadRuns(directory);
const problems = runs.flatMap((run) => validateSaturationRun(run).map((p) => `${run.runId}: ${p}`));
if (problems.length > 0) {
  console.error(problems.join('\n'));
  process.exit(1);
}
const samples = readFileSync(downstreamPath, 'utf8').trim().split(/\r?\n/).map((line) => JSON.parse(line));
const peak = (key) => Math.max(...samples.map((s) => s[key] || 0));
const curve = buildCurve(runs);
writeFileSync('docs/portfolio/data/k8s-saturation-results.json', JSON.stringify({
  environment: {
    cpu: 'Intel Core i7-14650HX, 16C/24T',
    memoryGb: 31.6,
    os: 'Microsoft Windows 11 10.0.26200',
    kubernetes: 'v1.36.3+k3s1',
    loadSource: 'windows-host',
    clockSource: 'windows',
    measurementBoundary: 'NodePort 30880 -> backend Service -> kube-proxy',
  },
  runs,
  curve,
  saturationPoints: findSaturationPoints(curve),
  downstreamPeaks: {
    hikariActive: peak('hikariActive'),
    hikariPending: peak('hikariPending'),
    pgBackends: peak('pgBackends'),
    reservationMaxMs: peak('reservationMaxMs'),
  },
}, null, 2));
console.log('written');
```

- [ ] **Step 5: 刪掉暫用腳本並 commit**

```bash
rm collect-saturation.mjs
git status --short   # 確認沒有把暫用腳本或 load-tests/k8s/results/ 帶進來
git add docs/portfolio/data/k8s-saturation-results.json
git commit -m "measure: replicas 1/3/5 的飽和式壓測結果與下游瓶頸證據"
```

---

## Task 6: PodDisruptionBudget

**Files:**
- Create: `k8s/base/availability.yaml`
- Modify: `k8s/base/kustomization.yaml`
- Modify: `scripts/tests/k8s-manifests-test.ps1`（資源總數 24 -> 25，並新增 PDB 的斷言）

**Interfaces:**
- Consumes: 無
- Produces: 名為 `backend` 的 PodDisruptionBudget，`minAvailable: 2`

- [ ] **Step 1: 先改測試（它現在會失敗）**

在 `scripts/tests/k8s-manifests-test.ps1` 中把資源總數的斷言由 24 改為 25：

```powershell
Assert-True ($resources.Count -eq 25) "Expected exactly 25 rendered resources, got $($resources.Count)."
```

並在 NodePort 守門那一段之後加入（`Get-OptionalProperty` 已存在於該檔）：

```powershell
# PDB 是常態設定而不是實驗器材：自願性中斷（節點維護、叢集升級）時要保住最低可用副本數。
$budgets = @($resources | Where-Object { $_.kind -eq 'PodDisruptionBudget' })
Assert-True ($budgets.Count -eq 1) "Expected exactly one PodDisruptionBudget, got $($budgets.Count)."
Assert-True ($budgets[0].metadata.name -eq 'backend') 'The PodDisruptionBudget must target backend.'
Assert-True ((Get-OptionalProperty -InputObject $budgets[0].spec -Name 'minAvailable') -eq 2) 'backend PDB must keep at least 2 Pods available.'
```

同時把檔尾 `Write-Host 'PASS: ...'` 那行的「24 resources」改為「25 resources」並補上 PDB。

- [ ] **Step 2: 執行測試確認失敗**

Run: `powershell -ExecutionPolicy Bypass -File scripts/tests/k8s-manifests-test.ps1`
Expected: FAIL，`Expected exactly 25 rendered resources, got 24.`

- [ ] **Step 3: 加入 PDB**

`k8s/base/availability.yaml`：

```yaml
# backend 的自願性中斷預算。
#
# 「自願性」指的是節點排空、叢集升級這類由人或控制器發起的驅逐；Pod 崩潰、節點當機
# 屬於非自願性中斷，PDB 管不到那些。
#
# minAvailable: 2 而不是百分比：副本數會被 HPA 在 3~8 之間調整，固定的下限比較好推理 ——
# 任何時刻至少有兩個 Pod 在服務，單一節點排空時 kube-proxy 仍有可分配的 Endpoint。
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: backend
  namespace: flashsale
  labels: {flashsale.dev/stage: application}
spec:
  minAvailable: 2
  selector:
    matchLabels: {app: backend}
```

在 `k8s/base/kustomization.yaml` 的 `resources` 清單中，於 `application.yaml` 之後加入 `  - availability.yaml`。

- [ ] **Step 4: 執行測試確認通過**

Run: `powershell -ExecutionPolicy Bypass -File scripts/tests/k8s-manifests-test.ps1`
Expected: PASS（25 個資源）

- [ ] **Step 5: 在叢集上實測 PDB 真的會擋下第二次驅逐**

用 Eviction API 而不是 `kubectl drain`：單節點叢集排空節點等同毀掉整個環境，而驅逐單一 Pod 可以精確驗證 PDB。先把副本數降到 2，讓 `minAvailable: 2` 完全沒有餘裕，結果才穩定可重現。

```bash
powershell -ExecutionPolicy Bypass -Command "\$env:FL_K3S_JWT_PRIVATE_KEY_PATH='C:\SideProject\flashsale-secrets\jwt-private.pem'; \$env:FL_K3S_JWT_PUBLIC_KEY_PATH='C:\SideProject\flashsale-secrets\jwt-public.pem'; & 'C:\SideProject\FlashSale\scripts\k8s\deploy.ps1'"
kubectl --context rancher-desktop -n flashsale scale deployment/backend --replicas=2
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=240s
POD=$(kubectl --context rancher-desktop -n flashsale get pods -l app=backend -o jsonpath='{.items[0].metadata.name}')
cat > /tmp/eviction.json <<JSON
{"apiVersion":"policy/v1","kind":"Eviction","metadata":{"name":"$POD","namespace":"flashsale"}}
JSON
kubectl --context rancher-desktop create --raw "/api/v1/namespaces/flashsale/pods/$POD/eviction" -f /tmp/eviction.json
```

Expected: 被拒，訊息包含 `Cannot evict pod as it would violate the pod's disruption budget`（2 個可用、下限也是 2，驅逐任何一個都會違反）。保留原始輸出，Task 8 要引用。

接著把副本數調回 3 再驅逐一次，應該會成功（`{"kind":"Status","status":"Success"}`），兩相對照才是完整證據：

```bash
kubectl --context rancher-desktop -n flashsale scale deployment/backend --replicas=3
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=240s
POD=$(kubectl --context rancher-desktop -n flashsale get pods -l app=backend -o jsonpath='{.items[0].metadata.name}')
sed "s/\"name\":\"[^\"]*\"/\"name\":\"$POD\"/" /tmp/eviction.json > /tmp/eviction2.json
kubectl --context rancher-desktop create --raw "/api/v1/namespaces/flashsale/pods/$POD/eviction" -f /tmp/eviction2.json
```

- [ ] **Step 6: Commit**

```bash
git add k8s/base/availability.yaml k8s/base/kustomization.yaml scripts/tests/k8s-manifests-test.ps1
git commit -m "feat: backend 的 PodDisruptionBudget 與其契約測試"
```

---

## Task 7: HPA 與擴容延遲實測

**Files:**
- Create: `k8s/autoscaling/hpa.yaml`
- Create: `load-tests/k8s/watch-scaling.ps1`

**Interfaces:**
- Consumes: Task 4 的 `run-saturation.ps1`（用 `-SkipScaling`）、Task 5 量到的飽和點
- Produces: 時間軸 CSV，欄位為 `sampledAt,desiredReplicas,readyReplicas,cpuUtilizationPercent`

**為什麼 HPA 不進 `k8s/base`：** base 宣告 `replicas: 3`，HPA 也會寫同一個欄位，兩者會互相覆寫；而且 P3 第一部分要能精確控制副本數做對照，基準環境必須是決定性的。HPA 因此放在 `k8s/autoscaling/`，需要時明確套用、實驗後刪除 —— 與壓測用的 NodePort 同一種處理方式。

- [ ] **Step 1: 寫 HPA manifest**

`k8s/autoscaling/hpa.yaml`：

```yaml
# backend 的 CPU-based HPA。刻意不放進 k8s/base/kustomization.yaml：
# base 宣告 replicas: 3，HPA 會寫同一個欄位，兩者互相覆寫；而且做副本數對照實驗時，
# 基準環境必須是決定性的。需要時明確套用，實驗結束後刪除。
#
#   kubectl --context rancher-desktop apply -f k8s/autoscaling/hpa.yaml
#   kubectl --context rancher-desktop delete -f k8s/autoscaling/hpa.yaml
#
# 閾值取 60%：backend 的 requests.cpu 是 500m，所以觸發點約等於單一 Pod 用到 300m。
# 這個數字要依 Task 5 量到的飽和點回頭檢查 —— 若飽和時 CPU 還遠低於 60%，代表瓶頸不在
# CPU，CPU-based HPA 對這個系統本來就不會有效。那個結論本身就是 P3 的產出之一。
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: backend
  namespace: flashsale
  labels: {flashsale.dev/role: autoscaling-experiment}
spec:
  scaleTargetRef: {apiVersion: apps/v1, kind: Deployment, name: backend}
  minReplicas: 3
  maxReplicas: 8
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {type: Utilization, averageUtilization: 60}
  behavior:
    # 擴容不設穩定視窗：秒殺場景要的是反應速度，而 Pod 就緒只要約 10 秒（P1 實測）。
    scaleUp:
      stabilizationWindowSeconds: 0
      policies: [{type: Percent, value: 100, periodSeconds: 15}]
    # 縮容保守：活動尖峰常有多波，縮太快會在下一波重新付一次冷啟動成本。
    scaleDown:
      stabilizationWindowSeconds: 300
      policies: [{type: Pods, value: 1, periodSeconds: 60}]
```

- [ ] **Step 2: 寫擴縮時間軸觀測腳本**

`load-tests/k8s/watch-scaling.ps1`（UTF-8 with BOM）：

```powershell
<#
.SYNOPSIS
    每秒記錄一次 backend 的期望副本數、就緒副本數與 HPA 觀察到的 CPU 使用率。

.DESCRIPTION
    這是量「HPA 跟不跟得上」的儀器。時間戳取自 Windows，與 k6 的量測區間對得起來。
    輸出 CSV 而不是 JSON：這份資料的用途是畫時間軸，CSV 直接貼進試算表就能看。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [int]$DurationSeconds = 300
)

New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
'sampledAt,desiredReplicas,readyReplicas,cpuUtilizationPercent' | Set-Content -LiteralPath $OutputPath -Encoding UTF8

$deadline = (Get-Date).AddSeconds($DurationSeconds)
while ((Get-Date) -lt $deadline) {
    $deployment = & kubectl --context $Context -n $Namespace get deployment backend -o 'jsonpath={.spec.replicas} {.status.readyReplicas}' 2>&1
    $hpa = & kubectl --context $Context -n $Namespace get hpa backend -o 'jsonpath={.status.currentMetrics[0].resource.current.averageUtilization}' 2>&1
    $parts = (($deployment | Out-String).Trim()) -split '\s+'
    $desired = ''
    $ready = '0'
    if ($parts.Length -ge 1) { $desired = $parts[0] }
    if ($parts.Length -ge 2) { $ready = $parts[1] }
    $cpu = ($hpa | Out-String).Trim()
    "$((Get-Date).ToUniversalTime().ToString('o')),$desired,$ready,$cpu" | Add-Content -LiteralPath $OutputPath -Encoding UTF8
    Start-Sleep -Seconds 1
}
Write-Host "時間軸寫入：$OutputPath"
```

- [ ] **Step 3: 通過編碼守門**

Run: `node scripts/tests/ps1-encoding.mjs`
Expected: PASS

- [ ] **Step 4: 實測 HPA 的完整反應延遲**

```bash
kubectl --context rancher-desktop -n flashsale scale deployment/backend --replicas=3
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=240s
kubectl --context rancher-desktop apply -f k8s/autoscaling/hpa.yaml
```

先啟動觀測（背景），再加壓。注意必須用 `-SkipScaling`，否則編排腳本會在開頭寫 `spec.replicas`，與 HPA 打架：

```bash
powershell -ExecutionPolicy Bypass -File load-tests/k8s/watch-scaling.ps1 \
  -OutputPath /tmp/p3-hpa/timeline.csv -DurationSeconds 300 &
powershell -ExecutionPolicy Bypass -File load-tests/k8s/run-saturation.ps1 \
  -Replicas 3 -SkipScaling -Rates <Task 5 量到的飽和速率> -RampSeconds 30 -HoldSeconds 180 \
  -AdminPassword 'MetricsAdmin123!'
```

從 `timeline.csv` 讀出四個時間點並記錄：

1. 負載開始（第一筆 CPU 明顯上升的樣本）
2. CPU 首次超過 60%
3. `desiredReplicas` 首次改變
4. 新副本 `readyReplicas` 到位

**「HPA 跟不跟得上」的答案就是第 3、4 點相對於第 1 點的秒數，結論無論正負都要寫下來。**

若 CPU 從頭到尾沒有超過 60%，那是個有意義的結果而不是失敗：代表瓶頸不在 backend 的 CPU，CPU-based HPA 對這個系統無效。記錄下來並在文件中說明應改用什麼指標（例如 RPS 或佇列長度）。

- [ ] **Step 5: 與「活動前預先擴容」做對照**

```bash
kubectl --context rancher-desktop delete -f k8s/autoscaling/hpa.yaml
kubectl --context rancher-desktop -n flashsale scale deployment/backend --replicas=<HPA 最終擴到的副本數>
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=240s
powershell -ExecutionPolicy Bypass -File load-tests/k8s/run-saturation.ps1 \
  -Replicas <同上> -SkipScaling -Rates <同一個速率> -RampSeconds 30 -HoldSeconds 180 \
  -AdminPassword 'MetricsAdmin123!'
```

比較兩者在**同一個到達率**下的 accept p95 與失敗率。預先擴容沒有擴容延遲，差距就是 HPA 反應期間付出的代價。

- [ ] **Step 6: 還原環境並 commit**

```bash
kubectl --context rancher-desktop delete -f k8s/autoscaling/hpa.yaml --ignore-not-found
kubectl --context rancher-desktop -n flashsale scale deployment/backend --replicas=3
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=240s
powershell -ExecutionPolicy Bypass -File scripts/k8s/verify.ps1
git add k8s/autoscaling/hpa.yaml load-tests/k8s/watch-scaling.ps1
git commit -m "feat: CPU-based HPA 與擴縮時間軸觀測"
```

---

## Task 8: 作品集文件與收尾

**Files:**
- Create: `docs/portfolio/scaling-and-autoscaling.md`
- Modify: `README.md`（深入文件索引加一列）
- Modify: `docs/portfolio/k3s-baseline.md`（證據表加一列）
- Modify: `load-tests/README.md`（加飽和式壓測的使用說明）
- Modify: `docs/superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md`（P3 驗收條件逐條標記）
- Modify: `docs/superpowers/plans/2026-09-13-week8-autonomous-session-log.md`（記錄過程中的問題與決策）

- [ ] **Step 1: 寫作品集文件**

`docs/portfolio/scaling-and-autoscaling.md` 必須包含這七節：

1. **為什麼要先修壓測**：引用 P1 的「三副本 p95 慢 41%、吞吐持平」，說明那是封閉模型造成的假象，不是水平擴展無效的證據。
2. **replicas 1 / 3 / 5 的 RPS 對 p95 表格**，以及三個飽和點。明確回答「水平擴展在什麼負載區間才開始有價值」。
3. **瓶頸指認**：Hikari `pending`、Postgres 連線數、Redis 預扣延遲的峰值，以及據此得到的結論。若瓶頸在下游，說明這正是 P4／P5 拆分 purchase-service 與獨立資料庫的直接動機。
4. **HPA 的完整反應延遲**（四個時間點）與「跟不跟得上」的結論，無論正負。
5. **HPA 與預先擴容的對照**，以及在本專案情境下的建議策略。
6. **PDB 的證據**：兩次驅逐的原始輸出（2 副本被拒、3 副本成功）。
7. **量測方法的誠實聲明**：施壓端在 Windows、時鐘來源、量測邊界、連線策略、Rancher Desktop 中繼層的上限，以及哪些數字因此不可與舊數據直接比較。

- [ ] **Step 2: 更新索引與證據表**

在 `README.md` 的「深入文件」表格中，`k3s 單節點基準` 那一列之前加入：

```
| [水平擴展與自動擴縮](docs/portfolio/scaling-and-autoscaling.md) | 飽和式壓測的方法、replicas 1/3/5 的吞吐曲線、瓶頸指認、HPA 與預先擴容的對照 |
```

在 `docs/portfolio/k3s-baseline.md` 的證據表加入一列，狀態為「已完成」，指向上述文件與 `docs/portfolio/data/k8s-saturation-results.json`。

在 `load-tests/README.md` 既有的「Running against k3s」章節之後，加入飽和式壓測的使用說明（`run-saturation.ps1` 的用法、`analyze-saturation.mjs` 的判讀方式、`StartRate` 不得超過 200 的理由）。

- [ ] **Step 3: 更新規格的驗收條件**

把 P3 的七條驗收條件逐條標記完成與對應證據；任何沒做到的條目要明確寫出來並說明原因，不要靜默略過。

- [ ] **Step 4: 全套測試**

```bash
node scripts/tests/ps1-encoding.mjs
node --test scripts/tests/ps1-encoding.test.mjs
node --test load-tests/k8s/analyze-saturation.test.mjs
node --test load-tests/benchmark/verify-results.test.mjs
powershell -ExecutionPolicy Bypass -File scripts/tests/k8s-manifests-test.ps1
powershell -ExecutionPolicy Bypass -File scripts/tests/k8s-scripts-test.ps1
powershell -ExecutionPolicy Bypass -File scripts/tests/portfolio-docs-test.ps1
powershell -ExecutionPolicy Bypass -File scripts/k8s/verify.ps1
cd backend && ./gradlew test
```

Expected: 全部通過；`verify.ps1` 顯示 10 個 Pod、0 重啟。

- [ ] **Step 5: Commit**

```bash
git add README.md docs/ load-tests/README.md
git commit -m "docs: P3 的水平擴展與自動擴縮結果"
```

---

## 風險與已知陷阱

| 陷阱 | 為什麼會踩 | 怎麼避開 |
|---|---|---|
| 把 `droppedIterations` 當成系統飽和 | k6 自己的 VU 不夠時也會掉迭代，在摘要上看起來與系統過載一樣 | 分析器已擋下；提高 `-MaxVUs` 後重跑該速率 |
| 連線重用讓負載分配看起來很不平均 | kube-proxy 是每條連線分配一次，不是每個請求 | 結果檔記錄 `connectionReuse` 與 `newConnections`；解讀分配時一併看 |
| 同時新連線超過約 210 條 | Rancher Desktop 的中繼層會在 TCP 握手階段 RST | arrival-rate 模型天生分散；`StartRate` 不得超過 200（腳本會擋） |
| 用同一個 token 反覆購買被擋成 `REJECTED` | `purchase_limit_per_user` 預設是 1 | 用 `fixtures-saturation.sql`（上限 1,000,000） |
| HPA 與 `run-saturation.ps1` 的縮放步驟打架 | 兩者都會寫 `spec.replicas` | HPA 實驗時一律加 `-SkipScaling` |
| 種完資料後取樣腳本登入失敗 | fixture 會 `TRUNCATE users`，管理員帳號跟著消失 | 編排腳本在種資料之後才呼叫 `ensure-metrics-admin.sh` |
| 容器內時鐘偏差混進數字 | 習慣性地把 k6 放進叢集 | 一律用 `run-saturation.ps1`（施壓端在 Windows） |
| 用 PowerShell 寫含中文的檔案導致亂碼 | `Get-Content -Raw \| Set-Content` 會損毀 | 改檔一律用 Node 或 Python 並指定 UTF-8 |
