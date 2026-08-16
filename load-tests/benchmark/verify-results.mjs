// Benchmark result verifier.
//
// `collect.ps1` writes one result document per harness invocation; this module is
// the gate that decides whether that document may be quoted anywhere. It checks
// two separate things:
//
//   1. Correctness invariants of the system under test — no oversell, no duplicate
//      orders per user, no negative inventory, no unexpected 5xx, nothing stuck in
//      PENDING.
//   2. Honesty of the document itself — environment metadata is present, the run
//      count matches what the collector claimed it would run, failed runs are
//      preserved with their error detail rather than silently dropped, and the soak
//      run really used the configuration it claims.
//
// Usage:
//   node verify-results.mjs <results.json>
// Exits 0 when the document is valid, 1 with one line per violation otherwise.

/** The exact soak configuration the harness is required to run. */
export const SOAK_SCENARIO = Object.freeze({
  executor: 'constant-arrival-rate',
  rate: 10,
  timeUnit: '1s',
  duration: '10m',
  preAllocatedVUs: 50,
  maxVUs: 200,
});

/** Unique users the soak run must drive (10/s for 10m = 6,000 iterations). */
export const SOAK_USERS = 6000;

/** The only Compose project this harness is ever allowed to measure. */
export const BENCHMARK_COMPOSE_PROJECT = 'flashsale-benchmark';

/** Contention matrix a `full` result must contain: 3 profiles x 5 repeats. */
export const FULL_CONTENTION_RUNS = 15;

const REQUIRED_ENVIRONMENT_STRINGS = [
  'gitSha',
  'gitBranch',
  'k6Version',
  'dockerVersion',
  'dockerComposeVersion',
  'os',
  'composeProject',
  'startedAt',
  'finishedAt',
];

const RUN_KINDS = ['contention', 'soak'];
const RUN_STATUSES = ['valid', 'failed'];

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value);
const isNonEmptyString = (value) => typeof value === 'string' && value.trim().length > 0;
const isFiniteNumber = (value) => typeof value === 'number' && Number.isFinite(value);

function validateEnvironment(environment, errors) {
  if (!isObject(environment)) {
    errors.push('environment is missing: a result without environment metadata is not reproducible');
    return;
  }
  for (const field of REQUIRED_ENVIRONMENT_STRINGS) {
    if (!isNonEmptyString(environment[field])) {
      errors.push(`environment.${field} is missing or empty`);
    }
  }
  if (typeof environment.gitDirty !== 'boolean') {
    errors.push('environment.gitDirty is missing: a result must record whether the tree was clean');
  }
  if (!isObject(environment.cpu)) {
    errors.push('environment.cpu is missing: a result must record the CPU it ran on');
  } else {
    if (!isNonEmptyString(environment.cpu.model)) {
      errors.push('environment.cpu.model is missing or empty');
    }
    if (!isFiniteNumber(environment.cpu.logicalCores) || environment.cpu.logicalCores <= 0) {
      errors.push('environment.cpu.logicalCores is missing or not a positive number');
    }
  }
  if (!isFiniteNumber(environment.memoryGb) || environment.memoryGb <= 0) {
    errors.push('environment.memoryGb is missing or not a positive number');
  }
  if (isNonEmptyString(environment.composeProject) && environment.composeProject !== BENCHMARK_COMPOSE_PROJECT) {
    errors.push(
      `environment.composeProject is "${environment.composeProject}" but benchmarks may only be collected from the isolated "${BENCHMARK_COMPOSE_PROJECT}" project`
    );
  }
}

function validateInvariants(run, errors) {
  const label = `run ${run.runId}`;
  const invariants = run.invariants;
  const numericFields = [
    'ordersCreated',
    'succeededRequests',
    'residualPending',
    'duplicateOrderUsers',
    'duplicateSucceededUsers',
  ];
  for (const field of numericFields) {
    if (!isFiniteNumber(invariants[field])) {
      errors.push(`${label}: invariants.${field} is missing or not a number`);
    }
  }

  const stock = run.stock;
  if (!isFiniteNumber(stock)) {
    errors.push(`${label}: stock is missing, so oversell cannot be checked`);
  } else {
    if (isFiniteNumber(invariants.ordersCreated) && invariants.ordersCreated > stock) {
      errors.push(`${label}: oversell — ${invariants.ordersCreated} orders created from ${stock} seeded units`);
    }
    if (isFiniteNumber(invariants.succeededRequests) && invariants.succeededRequests > stock) {
      errors.push(
        `${label}: oversell — ${invariants.succeededRequests} SUCCEEDED purchase requests from ${stock} seeded units`
      );
    }
  }

  if (isFiniteNumber(invariants.duplicateOrderUsers) && invariants.duplicateOrderUsers > 0) {
    errors.push(`${label}: duplicate orders — ${invariants.duplicateOrderUsers} user(s) hold more than one order`);
  }
  if (isFiniteNumber(invariants.duplicateSucceededUsers) && invariants.duplicateSucceededUsers > 0) {
    errors.push(
      `${label}: duplicate SUCCEEDED purchase requests — ${invariants.duplicateSucceededUsers} user(s) succeeded more than once`
    );
  }
  if (isFiniteNumber(invariants.residualPending) && invariants.residualPending > 0) {
    errors.push(`${label}: ${invariants.residualPending} purchase request(s) never left PENDING`);
  }

  if (!isObject(invariants.inventory)) {
    errors.push(`${label}: invariants.inventory is missing`);
  } else {
    for (const counter of ['available', 'reserved', 'sold']) {
      const value = invariants.inventory[counter];
      if (!isFiniteNumber(value)) {
        errors.push(`${label}: invariants.inventory.${counter} is missing or not a number`);
      } else if (value < 0) {
        errors.push(`${label}: negative inventory — inventory.${counter} is ${value}`);
      }
    }
  }
}

function validateOutcomes(run, errors) {
  const label = `run ${run.runId}`;
  const outcomes = run.outcomes;
  for (const field of ['accepted', 'succeeded', 'soldOut', 'rejected', 'failed', 'unexpected5xx', 'pendingTimeout']) {
    if (!isFiniteNumber(outcomes[field])) {
      errors.push(`${label}: outcomes.${field} is missing or not a number`);
    }
  }
  if (isFiniteNumber(outcomes.unexpected5xx) && outcomes.unexpected5xx > 0) {
    errors.push(`${label}: ${outcomes.unexpected5xx} unexpected 5xx response(s) — the purchase API must never raw-5xx`);
  }
  if (isFiniteNumber(outcomes.pendingTimeout) && outcomes.pendingTimeout > 0) {
    errors.push(`${label}: ${outcomes.pendingTimeout} request(s) were still PENDING when polling timed out`);
  }
}

function validateMetrics(run, errors) {
  const label = `run ${run.runId}`;
  const metrics = run.metrics;
  for (const trend of ['acceptedLatencyMs', 'completedLatencyMs']) {
    if (!isObject(metrics[trend])) {
      errors.push(`${label}: metrics.${trend} is missing`);
      continue;
    }
    for (const statistic of ['avg', 'p95', 'max']) {
      if (!isFiniteNumber(metrics[trend][statistic])) {
        errors.push(`${label}: metrics.${trend}.${statistic} is missing or not a number`);
      }
    }
  }
  if (!isFiniteNumber(metrics.requestsPerSecond)) {
    errors.push(`${label}: metrics.requestsPerSecond is missing or not a number`);
  }
  if (!isFiniteNumber(metrics.iterations)) {
    errors.push(`${label}: metrics.iterations is missing or not a number`);
  }
}

function validateSoakConfiguration(run, errors) {
  const label = `soak run ${run.runId}`;
  if (!isObject(run.scenario)) {
    errors.push(`${label} misconfigured: scenario configuration is missing`);
  } else {
    for (const [key, expected] of Object.entries(SOAK_SCENARIO)) {
      const actual = run.scenario[key];
      if (actual !== expected) {
        errors.push(`${label} misconfigured: scenario.${key} is ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`);
      }
    }
  }
  if (run.users !== SOAK_USERS) {
    errors.push(`${label} misconfigured: ${JSON.stringify(run.users)} unique users, expected ${SOAK_USERS}`);
  }
}

function validateRun(run, index, seenRunIds, errors) {
  if (!isObject(run)) {
    errors.push(`runs[${index}] is not an object`);
    return;
  }
  const label = `run ${run.runId}`;
  if (!isNonEmptyString(run.runId)) {
    errors.push(`runs[${index}].runId is missing or empty`);
  } else if (seenRunIds.has(run.runId)) {
    errors.push(`duplicate runId "${run.runId}": every run record must be distinguishable`);
  } else {
    seenRunIds.add(run.runId);
  }

  if (!RUN_KINDS.includes(run.kind)) {
    errors.push(`${label}: kind ${JSON.stringify(run.kind)} is not one of ${RUN_KINDS.join(', ')}`);
  }
  if (!RUN_STATUSES.includes(run.status)) {
    errors.push(`${label}: status ${JSON.stringify(run.status)} is not one of ${RUN_STATUSES.join(', ')}`);
  }
  for (const field of ['startedAt', 'finishedAt']) {
    if (!isNonEmptyString(run[field])) {
      errors.push(`${label}: ${field} is missing or empty`);
    }
  }
  if (!isObject(run.k6) || !isFiniteNumber(run.k6.exitCode)) {
    errors.push(`${label}: k6.exitCode is missing or not a number`);
  }
  if (!Array.isArray(run.errors)) {
    errors.push(`${label}: errors must be an array (use [] for a clean run)`);
  }

  const exitCode = isObject(run.k6) ? run.k6.exitCode : undefined;
  const recordedErrors = Array.isArray(run.errors) ? run.errors : [];

  if (run.status === 'valid') {
    if (exitCode !== 0) {
      errors.push(`${label} is marked valid but k6 exited with code ${JSON.stringify(exitCode)}`);
    }
    if (recordedErrors.length > 0) {
      errors.push(`${label} is marked valid but records ${recordedErrors.length} error(s): ${recordedErrors.join('; ')}`);
    }
    for (const block of ['metrics', 'outcomes', 'invariants']) {
      if (!isObject(run[block])) {
        errors.push(`${label} is marked valid but ${block} is missing`);
      }
    }
  }

  if (run.status === 'failed' && recordedErrors.length === 0) {
    errors.push(`${label} is marked failed but preserves no error detail — a failed run must record why it failed`);
  }

  if (isObject(run.metrics)) {
    validateMetrics(run, errors);
  }
  if (isObject(run.outcomes)) {
    validateOutcomes(run, errors);
  }
  if (isObject(run.invariants)) {
    validateInvariants(run, errors);
  }
  if (run.kind === 'soak') {
    validateSoakConfiguration(run, errors);
  }
}

/**
 * Validate a benchmark result document.
 *
 * @param {unknown} value parsed result JSON
 * @returns {{valid: boolean, errors: string[]}}
 */
export function validateBenchmarkResults(value) {
  const errors = [];

  if (!isObject(value)) {
    return { valid: false, errors: ['result document is not an object'] };
  }

  if (value.schemaVersion !== 1) {
    errors.push(`schemaVersion ${JSON.stringify(value.schemaVersion)} is unsupported (expected 1)`);
  }
  if (value.mode !== 'full' && value.mode !== 'smoke') {
    errors.push(`mode ${JSON.stringify(value.mode)} is not one of full, smoke`);
  }

  validateEnvironment(value.environment, errors);

  if (!isObject(value.summary)) {
    errors.push('summary is missing: a result must declare how many runs it expected and how many failed');
  }
  if (!Array.isArray(value.runs)) {
    errors.push('runs is missing or not an array');
    return { valid: false, errors };
  }
  if (value.runs.length === 0) {
    errors.push('runs is empty: a result must contain at least one run');
  }

  if (isObject(value.summary)) {
    const { expectedRuns, failedRuns } = value.summary;
    if (!isFiniteNumber(expectedRuns)) {
      errors.push('summary.expectedRuns is missing or not a number');
    } else if (value.runs.length !== expectedRuns) {
      errors.push(
        `runs omits ${expectedRuns - value.runs.length} run(s): summary.expectedRuns is ${expectedRuns} but ${value.runs.length} run record(s) are present`
      );
    }
    const actualFailed = value.runs.filter((run) => isObject(run) && run.status === 'failed').length;
    if (!isFiniteNumber(failedRuns)) {
      errors.push('summary.failedRuns is missing or not a number');
    } else if (failedRuns !== actualFailed) {
      errors.push(
        `summary.failedRuns is ${failedRuns} but ${actualFailed} run record(s) are marked failed — failed runs must be preserved, never dropped`
      );
    }
  }

  const seenRunIds = new Set();
  value.runs.forEach((run, index) => validateRun(run, index, seenRunIds, errors));

  if (value.mode === 'full') {
    const contentionRuns = value.runs.filter((run) => isObject(run) && run.kind === 'contention').length;
    const soakRuns = value.runs.filter((run) => isObject(run) && run.kind === 'soak').length;
    if (contentionRuns !== FULL_CONTENTION_RUNS) {
      errors.push(`a full result must contain ${FULL_CONTENTION_RUNS} contention runs, found ${contentionRuns}`);
    }
    if (soakRuns !== 1) {
      errors.push(`a full result must contain exactly 1 soak run, found ${soakRuns}`);
    }
  }

  return { valid: errors.length === 0, errors };
}

async function main(argv) {
  const [file] = argv;
  if (!file) {
    process.stderr.write('usage: node verify-results.mjs <results.json>\n');
    return 2;
  }
  const { readFile } = await import('node:fs/promises');
  let parsed;
  try {
    parsed = JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    process.stderr.write(`cannot read ${file}: ${error.message}\n`);
    return 2;
  }

  const { valid, errors } = validateBenchmarkResults(parsed);
  if (valid) {
    process.stdout.write(`OK ${file}: ${parsed.runs.length} run(s) satisfy every benchmark invariant\n`);
    return 0;
  }
  for (const error of errors) {
    process.stdout.write(`${error}\n`);
  }
  process.stderr.write(`FAIL ${file}: ${errors.length} invariant violation(s)\n`);
  return 1;
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href;

if (invokedDirectly) {
  process.exitCode = await main(process.argv.slice(2));
}
