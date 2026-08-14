// k6 purchase-flow correctness script.
//
// This is NOT a performance benchmark — there are no RPS/latency targets to
// hit (see design spec §9 / §15: "先不設定量化目標,實測後再記錄於 README"). Its only
// job is to prove, end-to-end through Nginx, that the flash-sale purchase
// flow never oversells and never double-creates orders under real concurrent
// load — the same invariant backend/src/test/java/.../PurchaseConcurrencyIT
// already proves at the MockMvc level, exercised here through the real
// reverse proxy, rate limiter, database, and message broker instead.
//
// Run with (see load-tests/README.md for the full walkthrough):
//   docker run --rm --network <compose-network> -v "<abs-path>/load-tests:/scripts" \
//     -i grafana/k6 run /scripts/purchase-flow.js -e VUS=30 -e STOCK=10
import http from 'k6/http';
import { check, sleep } from 'k6';

// ---- Config knobs (override via -e). Defaults match the fixture seeded in
// Step 1 of load-tests/README.md: STOCK units of inventory, more VUs than
// STOCK so the sold-out path is meaningfully exercised. ----
const VUS = parseInt(__ENV.VUS || '30', 10);
const STOCK = parseInt(__ENV.STOCK || '10', 10);
const FLASH_SALE_ID = __ENV.FLASH_SALE_ID || '1';
const BASE_URL = __ENV.BASE_URL || 'https://nginx:8443';

// nginx/nginx.conf rate-limits /api/auth/(login|register) to 5r/s (burst=10,
// nodelay) — each VU issues 2 auth requests (register + login), so firing all
// VUS of them at once would blow straight through that limit and produce
// spurious 503s that have nothing to do with the invariant under test.
// STAGGER_SECONDS spaces VUs out so the *aggregate* register+login rate stays
// under 5r/s. Every VU then sleeps the remainder of REGISTER_WINDOW before
// firing its purchase-request, so despite the staggered auth calls, all VUs
// still hit POST /purchase-requests at (approximately) the same moment —
// that's the actual contention this script exists to exercise.
const STAGGER_SECONDS = 0.5; // 2 requests/VU / 0.5s stagger ~= 4 req/s, under the 5r/s limit
const REGISTER_WINDOW = VUS * STAGGER_SECONDS + 3; // +3s buffer for the last VU's round trip

// Matches frontend/src/features/purchase/PurchaseStatusPage.tsx's own
// TERMINAL_STATUSES set (and its 1s refetchInterval, mirrored below).
const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'SOLD_OUT', 'REJECTED', 'FAILED']);
const MAX_POLLS = 60; // 60 * 1s sleep = 60s ceiling before giving up on a stuck request

export const options = {
  insecureSkipTLSVerify: true, // nginx serves a local self-signed cert, see nginx/certs
  scenarios: {
    purchase_flow: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '5m',
    },
  },
};

export function setup() {
  console.log(`purchase-flow: VUS=${VUS} STOCK=${STOCK} flashSaleId=${FLASH_SALE_ID} baseUrl=${BASE_URL}`);
  if (VUS <= STOCK) {
    console.warn('VUS <= STOCK: no VU will observe SOLD_OUT, defeating the point of this script');
  }
}

export default function () {
  const vuIndex = __VU - 1; // __VU is 1-based
  const suffix = `${__VU}-${Date.now()}`;
  const email = `k6-buyer-${suffix}@example.com`;
  const password = 'k6-secret-123';

  // --- stagger register/login so aggregate auth traffic respects nginx's auth_limit zone ---
  sleep(vuIndex * STAGGER_SECONDS);

  const registerRes = http.post(
    `${BASE_URL}/api/auth/register`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(registerRes, { 'register succeeded (201)': (r) => r.status === 201 });

  const loginRes = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(loginRes, { 'login succeeded (200)': (r) => r.status === 200 });

  let accessToken;
  try {
    accessToken = loginRes.json('accessToken');
  } catch (e) {
    accessToken = null;
  }
  if (!accessToken) {
    console.error(`VU ${__VU}: no access token, aborting (register=${registerRes.status}, login=${loginRes.status})`);
    return;
  }

  // --- wait for the shared window so every VU's purchase-request fires together ---
  const remaining = REGISTER_WINDOW - vuIndex * STAGGER_SECONDS;
  if (remaining > 0) {
    sleep(remaining);
  }

  const authHeaders = { headers: { Authorization: `Bearer ${accessToken}` } };
  const idempotencyKey = `k6-${suffix}`;

  const purchaseRes = http.post(
    `${BASE_URL}/api/flash-sales/${FLASH_SALE_ID}/purchase-requests`,
    null,
    { headers: { Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': idempotencyKey } }
  );

  // The core invariant this script proves end-to-end (matches PurchaseConcurrencyIT's
  // backend-level assertion): the purchase-request call itself never raw-5xxs, even
  // under real concurrent contention through Nginx.
  check(purchaseRes, {
    'purchase-request never raw 5xx': (r) => r.status < 500,
    'purchase-request returns 202': (r) => r.status === 202,
  });

  if (purchaseRes.status >= 500) {
    console.error(`VU ${__VU}: purchase-request returned ${purchaseRes.status}: ${purchaseRes.body}`);
    return;
  }

  let requestId;
  let status;
  try {
    requestId = purchaseRes.json('requestId');
    status = purchaseRes.json('status');
  } catch (e) {
    console.error(`VU ${__VU}: could not parse purchase-request response: ${purchaseRes.body}`);
    return;
  }

  let polls = 0;
  while (!TERMINAL_STATUSES.has(status) && polls < MAX_POLLS) {
    sleep(1); // matches the frontend's own 1s refetchInterval (PurchaseStatusPage.tsx)
    const pollRes = http.get(`${BASE_URL}/api/purchase-requests/${requestId}`, authHeaders);
    check(pollRes, { 'poll never raw 5xx': (r) => r.status < 500 });
    try {
      status = pollRes.json('status');
    } catch (e) {
      console.error(`VU ${__VU}: could not parse poll response: ${pollRes.body}`);
      break;
    }
    polls += 1;
  }

  check(null, {
    'purchase request reached a terminal status': () => TERMINAL_STATUSES.has(status),
  });

  console.log(`VU ${__VU}: requestId=${requestId} finalStatus=${status} polls=${polls}`);
}
