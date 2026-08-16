# Reproducible load harness

A self-contained benchmark for the current FlashSale purchase flow. It runs the
whole stack a second time — in its own Docker Compose project, on its own volumes,
on its own port — drives it with k6, and writes one JSON document per session that
records both the measurements and the correctness invariants that held while they
were taken.

The point is reproducibility, not a leaderboard. Everything it needs to be re-run
and re-checked (Git SHA, Docker version, OS, CPU, RAM, timestamps, per-run
configuration) is captured in the result document, and `verify-results.mjs` decides
whether that document may be quoted anywhere.

## What this measures (and what it does not claim)

Two legs of the asynchronous purchase flow (see
[`docs/portfolio/architecture.md`](../../docs/portfolio/architecture.md)):

| Metric | Meaning |
|---|---|
| `acceptedLatencyMs` | The synchronous leg: `POST /api/flash-sales/{id}/purchase-requests` answering `202`, i.e. limit checks plus the Redis Lua reservation. Already terminal when the buyer lost the race (`SOLD_OUT`) or had already bought (`REJECTED`). |
| `completedLatencyMs` | Wall clock from issuing that POST until the request is observed in a terminal status by polling `GET /api/purchase-requests/{requestId}`. |
| `orderCreatedLatencyMs` | The winners-only subset of the above: outbox publish → RabbitMQ → consumer → order row. |

Alongside every run the harness records the database and queue state afterwards —
orders created, `SUCCEEDED`/`SOLD_OUT`/`REJECTED`/`FAILED` counts, residual
`PENDING`, duplicate orders per user, inventory counters, unpublished outbox rows,
RabbitMQ queue depths (including both DLQs), the Actuator purchase metrics, and the
health probes.

Deliberate boundaries, so the numbers are read for what they are:

- **These are load characteristics of the system as it stands today, on one
  developer machine.** They are not a production capacity figure, and there is no
  comparison against any earlier or alternative implementation.
- **The load generator talks to the backend directly, not through Nginx.** Nginx
  rate-limits purchase requests per client IP (50r/s, burst 100). A single-host load
  generator firing 300 concurrent requests would mostly be measuring that limiter
  shedding traffic, so the harness leaves Nginx (and therefore TLS termination and
  the frontend) out of the request path. `load-tests/purchase-flow.js` is the script
  that exercises the full end-to-end path through Nginx.
- **Nothing here is a pass/fail performance target.** Only the correctness
  invariants can fail a result; latency and throughput are reported as observed.

## Prerequisites

- Docker Desktop (Compose v2).
- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) on `PATH`.
- [Node.js](https://nodejs.org/) on `PATH` (runs the verifier; nothing is installed
  from npm — the verifier is dependency-free and uses the built-in test runner).
- A repo-root `.env` with `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`, exactly as the normal
  stack needs — see the [root README](../../README.md). The benchmark backend reads
  the same file and will refuse to start without it.

Windows PowerShell 5.1 is enough; `collect.ps1` does not require PowerShell 7.

## Isolation

Benchmark runs truncate databases and flush Redis. Everything below exists so they
can only ever do that to the benchmark stack:

- The Compose project name is the literal **`flashsale-benchmark`**. `collect.ps1`
  compares it case-sensitively and refuses to start otherwise, and also refuses to
  run if `COMPOSE_PROJECT_NAME`, `COMPOSE_FILE`, `DOCKER_HOST` or a non-local
  `DOCKER_CONTEXT` could retarget it — the same fail-closed shape as
  [`scripts/demo-data.sh`](../../scripts/demo-data.sh).
- Before the first destructive operation it inspects the running containers and
  requires their Compose labels to name both this project *and* this compose file.
- The volume is `benchmark_postgres_data` (fully qualified:
  `flashsale-benchmark_benchmark_postgres_data`), so `down -v` here cannot reach
  `flashsale_postgres_data`.
- Exactly one host port is published: the backend on `18080`
  (override with `BENCHMARK_BACKEND_PORT`). The normal stack publishes `8443` and
  `9411` and does not publish the backend, so both stacks can run side by side.
- Cleanup only ever names `flashsale-benchmark`.

## Running it

From the repository root:

```powershell
# Full matrix: 30/10, 100/30, 300/100 five times each, then the soak. Takes a while.
powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode full

# Smoke: one tiny run, just to prove the harness works end to end.
powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode smoke -SmokeVus 3 -SmokeStock 1
```

Useful switches: `-OutDir <path>` (default `load-tests/benchmark/results`),
`-KeepStack` (leave the isolated stack up for inspection).

`collect.ps1` brings the stack up, verifies isolation, and then for every run:
resets and reseeds the database (`fixtures.sql`), flushes the Redis reservation
counters, purges the queues, creates that run's buyer accounts (`prepare.js`), runs
the measured scenario (`purchase-load.js` / `soak.js`), waits for the async pipeline
to drain, captures the invariants, and finally tears the project down and runs the
verifier.

### Driving Compose by hand

```powershell
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml config --quiet
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml up -d --build --wait
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml down -v
```

`--project-directory .` is required, not optional: Compose resolves both the
backend's relative build context and the `.env` lookup against the project
directory, so leaving it out builds from the wrong path and cannot find the JWT
keys. Always run these from the repository root.

## The runs

| Run | Shape | Why |
|---|---|---|
| `contention-30x10-*` | 30 buyers, 10 units | Light oversubscription, 3:1. |
| `contention-100x30-*` | 100 buyers, 30 units | Mid oversubscription. |
| `contention-300x100-*` | 300 buyers, 100 units | Heavy simultaneous arrival. |
| `soak-1` | `constant-arrival-rate`, `rate: 10`, `timeUnit: '1s'`, `duration: '10m'`, `preAllocatedVUs: 50`, `maxVUs: 200`, 6,000 unique buyers | Steady state rather than a spike: does latency stay flat for ten minutes. |

Each contention profile runs five times so spread can be reported instead of one
lucky run. Every buyer is a distinct account created before the measured window, so
no VU trips the per-user purchase limit and no auth traffic lands in the numbers.
The soak's scenario block is literal in `soak.js`, mirrored into the result
document, and re-checked by the verifier — the three cannot drift apart silently.

## Results and verification

Each session writes `results/<mode>-<timestamp>/`:

- `results.json` — the result document (schema below).
- `k6-<runId>.json` — that run's k6 summary, including the raw k6 metrics.
- `tokens-<runId>.json` — the accounts and access tokens `prepare.js` created.

```jsonc
{
  "schemaVersion": 1,
  "mode": "full",                 // or "smoke"
  "environment": { "gitSha": "...", "gitDirty": false, "k6Version": "...",
                   "dockerVersion": "...", "dockerComposeVersion": "...",
                   "os": "...", "cpu": { "model": "...", "logicalCores": 16 },
                   "memoryGb": 32, "composeProject": "flashsale-benchmark",
                   "startedAt": "...", "finishedAt": "..." },
  "summary": { "expectedRuns": 16, "failedRuns": 0 },
  "runs": [ { "runId": "contention-30x10-1", "kind": "contention",
              "status": "valid",            // or "failed"
              "vus": 30, "stock": 10,
              "k6": { "exitCode": 0, "checksFailed": 0, "summaryFile": "..." },
              "metrics": { ... }, "outcomes": { ... }, "invariants": { ... },
              "queues": [ ... ], "health": { ... }, "actuator": { ... },
              "errors": [] } ]
}
```

Verify a document at any time:

```powershell
node load-tests/benchmark/verify-results.mjs load-tests/benchmark/results/<session>/results.json
```

It exits `0` when the document is clean and `1` with one line per violation
otherwise. It rejects:

- **oversell** — more orders (or `SUCCEEDED` requests) than seeded stock;
- **duplicate orders** — any user holding more than one order for a run;
- **negative inventory** — any of `available`/`reserved`/`sold` below zero;
- **unexpected 5xx** — the purchase API must never raw-5xx, matching what
  `PurchaseConcurrencyIT` asserts at the backend-test level;
- **residual `PENDING`** — any request that never reached a terminal status;
- **missing environment metadata**, or a document collected under any project other
  than `flashsale-benchmark`;
- **omitted runs** — fewer records than `summary.expectedRuns`, a `failedRuns` count
  that does not match the failed records, a failed run stripped of its error detail,
  or a run marked `valid` despite a nonzero k6 exit code;
- **soak misconfiguration** — any deviation from `constant-arrival-rate` at `10/s`
  for `10m` with `50`/`200` VUs over `6000` unique users.

### Failed runs are kept

A run that fails is written into the document with `status: "failed"` and the reason
in its `errors` array, counted in `summary.failedRuns`, and its invariants are still
captured. The verifier rejects any document whose run count or failure count implies
a record went missing, so a bad run cannot be quietly dropped to make a session look
clean. Outliers are likewise never trimmed — every one of the five repeats per
profile is recorded.

## Files

| File | Responsibility |
|---|---|
| `compose.benchmark.yaml` | The isolated stack: Postgres, Redis, RabbitMQ, Mailpit, Zipkin, backend. |
| `fixtures.sql` | Destructive per-run reset plus one active flash sale with `:stock` units. |
| `prepare.js` | Creates the run's unique buyer accounts and tokens, outside measured traffic. |
| `purchase-load.js` | Contention scenario (`VUS` buyers, `STOCK` units, one shot each). |
| `soak.js` | Soak scenario, 10/s for 10m over 6,000 buyers. |
| `collect.ps1` | Orchestration, isolation guards, probes, result document, cleanup. |
| `verify-results.mjs` | `validateBenchmarkResults(value)` plus a CLI gate. |
| `verify-results.test.mjs` | `node --test load-tests/benchmark/verify-results.test.mjs`. |

## Tests

```powershell
node --test load-tests/benchmark/verify-results.test.mjs
```
