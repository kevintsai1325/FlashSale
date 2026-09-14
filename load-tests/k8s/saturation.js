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
const newConnections = new Counter('saturation_new_connections');

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
  // k6 預設的 summaryTrendStats 不含 p(99)，缺席的分位數會在 trendOf() 裡被當成 0，
  // 落在 p95 之後就會被 analyze-saturation.mjs 判成「分位數未遞增」而整筆 REJECTED。
  // 明確列出需要的統計量，讓 p99 真的算出來。
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max', 'count'],
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
  if (response.timings.connecting > 0) {
    newConnections.add(1);
  }
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
  if (poll.timings.connecting > 0) {
    newConnections.add(1);
  }
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
      // saturation_new_connections 是我們自己在每次 HTTP 呼叫後累加的 Counter：
      // response.timings.connecting > 0 代表這次呼叫真的建立了新連線（連線重用時為 0）。
      // http_req_connecting 是 Trend，每個請求都會記一筆樣本（重用時值是 0），
      // 所以它的 count 等於總請求數，不是新連線數，不能拿來當這個欄位的來源。
      newConnections: counterOf(metrics, 'saturation_new_connections'),
      droppedIterations: counterOf(metrics, 'dropped_iterations'),
      failedRequests: failed.passes === undefined ? 0 : failed.passes,
      non202Responses: counterOf(metrics, 'saturation_non_202'),
      unexpected5xx: counterOf(metrics, 'saturation_5xx'),
    },
  };
  return { [SUMMARY_OUT]: JSON.stringify(summary, null, 2) };
}
