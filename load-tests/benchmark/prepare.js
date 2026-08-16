// Benchmark preparation: create the unique buyer accounts a measured run needs,
// and hand their access tokens to the load scripts as a JSON file.
//
// This runs as its own k6 process, before any measured traffic, so that neither
// registration (BCrypt-bound) nor login shows up in the latency numbers that
// purchase-load.js / soak.js report. The measured scripts only ever issue the
// purchase-request POST and its status polls.
//
// Every account is unique per run (`bench-<RUN_ID>-<index>@benchmark.local`) so a
// run never inherits another run's per-user purchase limit.
//
//   k6 run prepare.js -e USERS=30 -e RUN_ID=contention-30x10-1 \
//     -e BASE_URL=http://localhost:18080 -e BENCH_PASSWORD=<generated> \
//     -e TOKENS_OUT=<abs path>/tokens-contention-30x10-1.json
//
// Exits nonzero (k6 threshold failure) if any account could not be created or
// logged in, so collect.ps1 can mark the run failed instead of measuring a
// half-prepared population.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const USERS = parseInt(__ENV.USERS || '30', 10);
const RUN_ID = __ENV.RUN_ID || 'adhoc';
const PASSWORD = __ENV.BENCH_PASSWORD || '';
const TOKENS_OUT = __ENV.TOKENS_OUT || `tokens-${RUN_ID}.json`;
// How many register/login calls are in flight at once. Registration hashes with
// BCrypt, so this is deliberately modest: it keeps preparation parallel enough to
// finish 6,000 soak accounts in minutes without turning preparation itself into an
// unmeasured overload of the system under test.
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || '25', 10);

const prepareFailures = new Counter('prepare_failures');

export const options = {
  scenarios: {
    prepare: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '60m' },
  },
  // All the work happens in setup(); give it room for the soak's 6,000 accounts.
  setupTimeout: __ENV.SETUP_TIMEOUT || '45m',
  thresholds: { prepare_failures: ['count==0'] },
};

const jsonHeaders = { headers: { 'Content-Type': 'application/json' } };

function emailFor(index) {
  return `bench-${RUN_ID}-${index}@benchmark.local`;
}

function chunk(items, size) {
  const chunks = [];
  for (let start = 0; start < items.length; start += size) {
    chunks.push(items.slice(start, start + size));
  }
  return chunks;
}

export function setup() {
  if (!PASSWORD) {
    throw new Error('BENCH_PASSWORD is required: pass -e BENCH_PASSWORD=<generated secret> (never hardcode one)');
  }
  if (!Number.isFinite(USERS) || USERS < 1) {
    throw new Error(`USERS must be a positive integer, got ${__ENV.USERS}`);
  }

  const indexes = [];
  for (let index = 0; index < USERS; index += 1) {
    indexes.push(index);
  }

  const failures = [];

  // --- register ---------------------------------------------------------
  for (const group of chunk(indexes, BATCH_SIZE)) {
    const requests = group.map((index) => [
      'POST',
      `${BASE_URL}/api/auth/register`,
      JSON.stringify({ email: emailFor(index), password: PASSWORD }),
      jsonHeaders,
    ]);
    const responses = http.batch(requests);
    responses.forEach((response, offset) => {
      // 409 means the account survived from an earlier prepare of the same run id;
      // that is still a usable account, so only anything else counts as a failure.
      if (response.status !== 201 && response.status !== 409) {
        failures.push(`register ${emailFor(group[offset])} -> ${response.status} ${String(response.body).slice(0, 200)}`);
      }
    });
  }

  // --- login ------------------------------------------------------------
  const users = [];
  for (const group of chunk(indexes, BATCH_SIZE)) {
    const requests = group.map((index) => [
      'POST',
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: emailFor(index), password: PASSWORD }),
      jsonHeaders,
    ]);
    const responses = http.batch(requests);
    responses.forEach((response, offset) => {
      const email = emailFor(group[offset]);
      if (response.status !== 200) {
        failures.push(`login ${email} -> ${response.status} ${String(response.body).slice(0, 200)}`);
        return;
      }
      let token = null;
      try {
        token = response.json('accessToken');
      } catch (error) {
        token = null;
      }
      if (!token) {
        failures.push(`login ${email} returned no accessToken`);
        return;
      }
      users.push({ email, token });
    });
  }

  for (const failure of failures) {
    console.error(`prepare: ${failure}`);
  }

  return {
    runId: RUN_ID,
    baseUrl: BASE_URL,
    requestedUsers: USERS,
    preparedAt: new Date().toISOString(),
    failures,
    users,
  };
}

export default function (data) {
  // Preparation is a setup-only workload; the single iteration exists so k6
  // produces a summary (and therefore the tokens file) and so the failure count
  // reaches a metric the threshold can fail on.
  prepareFailures.add(data.failures.length);
  console.log(`prepare: ${data.users.length}/${data.requestedUsers} accounts ready, ${data.failures.length} failure(s)`);
}

export function handleSummary(data) {
  const prepared = data.setup_data || { users: [], failures: ['setup data unavailable'] };
  return {
    [TOKENS_OUT]: JSON.stringify(prepared, null, 1),
    stdout: `prepare: wrote ${prepared.users.length} token(s) to ${TOKENS_OUT}\n`,
  };
}
