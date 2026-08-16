# FlashSale Week 7 Portfolio Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package FlashSale as a reproducible Traditional-Chinese engineering portfolio with safe demo data, architecture/API/trade-off documentation, current-system load evidence, and sanitized screenshots.

**Architecture:** Keep `README.md` as the evidence-first entry point and move depth into `docs/portfolio/`. Use isolated Compose projects for destructive benchmark fixtures, generate machine-readable benchmark summaries before writing conclusions, and keep demo tooling idempotent and scoped by explicit identifiers.

**Tech Stack:** Markdown/Mermaid, Bash, Docker Compose, PostgreSQL, curl, jq, k6, Spring Boot/Actuator, React, GitHub Actions.

## Global Constraints

- All public portfolio prose is Traditional Chinese; stable identifiers and API fields remain English.
- No fixed password, JWT, Cookie, private key, `.env` value, real email, or local absolute path may be committed.
- Demo cleanup deletes only rows with the exact demo identifiers; no unconditional `TRUNCATE`.
- Benchmark data uses a separate Compose project and volumes, never the user's normal `flashsale` data.
- Report current-system load characteristics only; do not compare the historical synchronous version or claim production capacity.
- Preserve failed/outlier benchmark runs and distinguish them from valid runs.
- README uses an evidence-first hero, a system-overview Mermaid diagram, then a purchase sequence diagram.
- Commit screenshots under `docs/portfolio/assets/`; use the CI badge/link instead of a stale CI screenshot.
- Unit/scoped checks run per task. Run the complete regression/CI-equivalent verification once after all implementation tasks finish.

---

## File Structure

- `scripts/demo-data.sh`: idempotent seed/cleanup entry point.
- `scripts/lib/demo-data-lib.sh`: identifier validation and SQL/API helpers, isolated for shell tests.
- `scripts/tests/demo-data-lib-test.sh`: no-database behavior tests for safety helpers.
- `load-tests/benchmark/prepare.js`: creates unique benchmark users/tokens and fixture metadata.
- `load-tests/benchmark/purchase-load.js`: 30/100/300 contention scenarios.
- `load-tests/benchmark/soak.js`: 10 req/s, 10-minute constant-arrival-rate scenario.
- `load-tests/benchmark/collect.ps1`: executes matrix, captures k6/Actuator/DB/RabbitMQ evidence, and writes JSON.
- `load-tests/benchmark/compose.benchmark.yaml`: isolated resource limits and ports.
- `load-tests/benchmark/verify-results.mjs`: validates result schema and invariants.
- `docs/portfolio/data/benchmark-results.json`: reproducible summarized observations.
- `docs/portfolio/{architecture,api-examples,performance-report,trade-offs,demo-script}.md`: detailed portfolio documents.
- `docs/portfolio/assets/*.png`: six sanitized screenshots.
- `README.md`: concise evidence-first portfolio entry.
- `docs/superpowers/plans/2026-08-16-session-handoff.md`: corrected historical state.
- `docs/superpowers/specs/2026-08-16-technical-debt-roadmap-design.md`: recovered historical roadmap.
- `docs/superpowers/plans/2026-08-16-week7-session-handoff.md`: Week 7 handoff.

---

### Task 1: Recover and synchronize project history documents

**Files:**
- Create: `docs/superpowers/specs/2026-08-16-technical-debt-roadmap-design.md`
- Modify: `docs/superpowers/plans/2026-08-16-session-handoff.md`
- Test: document scans via `rg` and Git blob comparison.

**Interfaces:**
- Consumes: immutable Git blob `4260c7d58334511a9f7cc3e1927d47b70512ace0` (the missing technical-debt roadmap captured from the current stash), current Git history, test artifacts, and completed technical-debt plans.
- Produces: an accurate historical baseline for README and the Week 7 handoff.

- [ ] **Step 1: Write a failing stale-content scan**

Run:

```powershell
rg -n "Week 7（未開始）|131 個|ApiAuditFilter.*稽核不到|outbox_events.*沒有 trace|nginx 沒有 /actuator" docs/superpowers/plans/2026-08-16-session-handoff.md
```

Expected: matches prove the handoff is stale.

- [ ] **Step 2: Recover only the missing roadmap blob**

Read blob `4260c7d58334511a9f7cc3e1927d47b70512ace0`, recreate that exact file with `apply_patch`, and verify the new file's blob hash matches. Do not restore or overwrite the other divergent stash files.

- [ ] **Step 3: Update the handoff**

Record backend 159 tests, frontend 70 tests, CI success, all six technical-debt batches, service-health dashboard, order-item snapshots, and Week 7 as in progress. Replace completed limitations with current honest limitations: local self-signed TLS, local Compose-only demonstration, and no production-capacity claim.

- [ ] **Step 4: Verify GREEN and commit**

Re-run the stale scan and require no matches. Run `git diff --check`, then commit:

```powershell
git add docs/superpowers
git commit -m "docs: synchronize Week 7 project history"
```

### Task 2: Build safe, idempotent demo-data tooling

**Files:**
- Create: `scripts/demo-data.sh`
- Create: `scripts/lib/demo-data-lib.sh`
- Create: `scripts/tests/demo-data-lib-test.sh`
- Modify: `.env.example`

**Interfaces:**
- Consumes: running Compose stack, `/api/auth/register`, PostgreSQL schema, `DEMO_USER_PASSWORD`, and `DEMO_ADMIN_PASSWORD`.
- Produces: `scripts/demo-data.sh seed|cleanup`, fixed emails `demo.user@example.test` and `demo.admin@example.test`, product marker `[DEMO] Portfolio Product`, and order/activity identifiers prefixed `DEMO-PORTFOLIO-`.

- [ ] **Step 1: Write failing shell tests**

Tests must prove empty passwords fail, unsupported commands fail, non-demo identifiers are rejected by cleanup helpers, SQL literals escape single quotes, and generated deletion predicates contain the exact demo prefix. Each test invokes real shell functions and asserts exit status/output literals.

- [ ] **Step 2: Verify RED**

Run from Git Bash:

```bash
bash scripts/tests/demo-data-lib-test.sh
```

Expected: FAIL because the library does not exist.

- [ ] **Step 3: Implement minimal helpers and entry point**

`seed` checks eight Compose services are healthy, registers both users if absent, updates only the exact admin email to `ADMIN`, inserts/upserts the exact demo product/activity/inventory, and prints URLs without secrets. `cleanup` verifies the database name is `flashsale`, lists exact target counts, deletes FK children before parents inside one transaction, and remains successful when nothing exists.

Add commented password variables to `.env.example` with empty values and explicit local-demo wording.

- [ ] **Step 4: Verify unit behavior and real idempotency**

Run the shell test, `bash -n` on both scripts, then against the normal local stack run `seed` twice and compare exact demo row counts; run `cleanup` twice; insert one non-demo sentinel row before the cycle and prove it remains afterward.

- [ ] **Step 5: Commit**

```powershell
git add scripts .env.example
git commit -m "feat: add safe portfolio demo data tooling"
```

### Task 3: Write architecture, API, trade-off, and demo documents

**Files:**
- Create: `docs/portfolio/architecture.md`
- Create: `docs/portfolio/api-examples.md`
- Create: `docs/portfolio/trade-offs.md`
- Create: `docs/portfolio/demo-script.md`
- Create: `scripts/tests/portfolio-docs-test.ps1`

**Interfaces:**
- Consumes: current Compose config, controller routes, security rules, metrics names, demo identifiers, and tracing implementation.
- Produces: README-linked detailed documentation with executable API examples.

- [ ] **Step 1: Write a failing documentation contract test**

The PowerShell test asserts all four files exist, required headings are present, every relative Markdown link resolves, forbidden secret patterns are absent, Mermaid fences are balanced, API paths exist in controller source/nginx config, and stale claims such as “outbox trace 分成兩段” are absent.

- [ ] **Step 2: Verify RED**

Run `pwsh -File scripts/tests/portfolio-docs-test.ps1`. Expected: FAIL because documents are missing.

- [ ] **Step 3: Write the four documents**

`architecture.md` includes system-context and purchase-sequence Mermaid diagrams. `api-examples.md` uses shell variables (`$ACCESS_TOKEN`) and exact curl commands. `trade-offs.md` covers Redis Lua, outbox, compensation, timeout scanning, OSIV, Actuator, local TLS, and Compose-only delivery. `demo-script.md` provides a timed 3–5 minute narration and fallback steps.

- [ ] **Step 4: Verify GREEN and commit**

Run the documentation test and `git diff --check`, then commit `docs: add FlashSale portfolio deep dives`.

### Task 4: Implement the isolated benchmark harness

**Files:**
- Create: `load-tests/benchmark/compose.benchmark.yaml`
- Create: `load-tests/benchmark/prepare.js`
- Create: `load-tests/benchmark/purchase-load.js`
- Create: `load-tests/benchmark/soak.js`
- Create: `load-tests/benchmark/collect.ps1`
- Create: `load-tests/benchmark/verify-results.mjs`
- Create: `load-tests/benchmark/fixtures.sql`
- Create: `load-tests/benchmark/README.md`
- Test: `load-tests/benchmark/verify-results.test.mjs`

**Interfaces:**
- Consumes: current purchase API, isolated Compose project `flashsale-benchmark`, health endpoints, PostgreSQL/RabbitMQ/Actuator probes.
- Produces: one result JSON containing environment metadata, 15 contention runs, one soak run, latency/throughput metrics, and DB/queue invariants.

- [ ] **Step 1: Write failing verifier tests**

Use literal valid/invalid result fixtures. Assert rejection for oversell, duplicate orders, negative inventory, nonzero unexpected 5xx, residual `PENDING`, missing environment metadata, omitted failed runs, and a soak run not configured as `10/s` for `10m`.

- [ ] **Step 2: Verify RED**

Run bundled Node with `node --test load-tests/benchmark/verify-results.test.mjs`. Expected: module-not-found failure.

- [ ] **Step 3: Implement the result verifier**

Export `validateBenchmarkResults(value)` returning `{valid, errors}` and a CLI that exits nonzero with one line per invariant violation.

- [ ] **Step 4: Implement k6 scenarios**

`prepare.js` creates unique users/tokens outside measured traffic. `purchase-load.js` accepts `VUS`, `STOCK`, `RUN_ID` and measures accepted/completed latency. `soak.js` uses `constant-arrival-rate`, `rate:10`, `timeUnit:'1s'`, `duration:'10m'`, `preAllocatedVUs:50`, `maxVUs:200`, and 6,000 unique users.

- [ ] **Step 5: Implement isolated orchestration**

`collect.ps1` validates the project name, starts Compose with `-p flashsale-benchmark`, uses benchmark-only volumes/ports, runs 30/10, 100/30, and 300/100 five times each, then soak. It captures k6 JSON summary, health, metrics, RabbitMQ queue depth, DB invariants, Git SHA, Docker version, OS/CPU/RAM, timestamps, and failed runs. Cleanup targets only `flashsale-benchmark`.

- [ ] **Step 6: Verify harness without full matrix**

Run verifier unit tests, `docker compose -p flashsale-benchmark ... config --quiet`, k6 archive/inspect for both scripts, and one 3-user/1-stock smoke run. Verify the smoke JSON and clean the isolated project.

- [ ] **Step 7: Commit**

```powershell
git add load-tests/benchmark
git commit -m "test: add reproducible current-system load harness"
```

### Task 5: Execute and preserve benchmark evidence

**Files:**
- Create: `docs/portfolio/data/benchmark-results.json`
- Create: `docs/portfolio/performance-report.md`
- Modify: benchmark tooling only if a proven harness defect prevents honest collection.

**Interfaces:**
- Consumes: Task 4 harness at the current commit and a healthy Docker engine.
- Produces: verified real observations for README and portfolio claims.

- [ ] **Step 1: Record the clean environment**

Require clean Git status, capture commit SHA and Docker/test-machine metadata, verify normal `flashsale` data is not mounted by the benchmark project, and save the planned run manifest.

- [ ] **Step 2: Execute the full matrix once**

Run `collect.ps1` for 15 contention runs plus the 10-minute soak. Do not discard failed or outlier runs. If tooling fails, preserve the failed artifact, write a regression test, fix the harness, commit separately, and restart only the affected run with a new run id.

- [ ] **Step 3: Verify invariants and JSON**

Run `verify-results.mjs`; independently query the isolated DB before cleanup; confirm all configured runs are present and the normal Compose project's sentinel counts are unchanged.

- [ ] **Step 4: Write the performance report from evidence**

Include environment, method, medians, p50/p95/min/max, accepted vs completed latency, throughput, failed runs, soak trends, bottlenecks, correctness, and explicit non-production limitations. Every number in Markdown must trace to a JSON path.

- [ ] **Step 5: Clean benchmark resources and commit**

Remove only project `flashsale-benchmark`, confirm its containers/volumes are absent, then commit data/report with `docs: record FlashSale load characteristics`.

### Task 6: Capture and sanitize portfolio screenshots

**Files:**
- Create: six PNGs under `docs/portfolio/assets/`
- Create: `docs/portfolio/assets/README.md`

**Interfaces:**
- Consumes: Task 2 demo fixture, current UI, Zipkin, and local Compose stack.
- Produces: deterministic, compressed, secret-free visuals for README/docs.

- [ ] **Step 1: Seed demo state and define the shot manifest**

List exact route, viewport, expected visible text, output filename, and crop for storefront, purchase result, my-orders items, admin dashboard, service health, and Zipkin trace.

- [ ] **Step 2: Capture actual screens**

Use 1440×900 desktop viewport. Trust the local certificate or use a loopback-only capture route without changing production nginx security. Never capture browser warnings, tokens, cookies, `.env`, terminal secrets, or absolute local paths.

- [ ] **Step 3: Verify and optimize**

Inspect every image visually, verify expected text, strip metadata, compress losslessly, and require each image under 500 KiB unless legibility demonstrably requires more. Record capture date/commit and regeneration steps in the assets README.

- [ ] **Step 4: Cleanup and commit**

Run demo cleanup twice, verify non-demo rows unchanged, then commit `docs: add sanitized portfolio screenshots`.

### Task 7: Rewrite README and create the Week 7 handoff

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-16-week7-session-handoff.md`
- Modify: `scripts/tests/portfolio-docs-test.ps1`

**Interfaces:**
- Consumes: all prior documents, verified benchmark JSON, screenshots, demo tooling, and current CI URL.
- Produces: the public portfolio entry point and final maintenance handoff.

- [ ] **Step 1: Extend the failing README contract**

Assert README is Traditional Chinese, contains the CI badge, evidence-first summary, real test/health counts, both Mermaid diagrams, quick start, demo commands, six local image links, benchmark summary values sourced from JSON, limitations, and links to every portfolio document. Assert stale Week 1 stub/old observability limitations are absent.

- [ ] **Step 2: Verify RED**

Run `portfolio-docs-test.ps1`. Expected: FAIL against the current mixed-language README.

- [ ] **Step 3: Rewrite README**

Keep the first screen concise, show results before setup, provide copy-paste startup and demo commands, use collapsible detail only where GitHub supports it, and state self-signed TLS/local Compose limitations honestly.

- [ ] **Step 4: Write Week 7 handoff**

Record commits, files, benchmark environment/results, screenshot regeneration, test evidence, remaining limitations, stash status, and future optional improvements without relisting completed debt.

- [ ] **Step 5: Verify GREEN and commit**

Run docs test, link scan, `git diff --check`, then commit `docs: complete FlashSale portfolio packaging`.

### Task 8: Final end-to-end verification

**Files:**
- Modify only files required to fix a reproducible verification defect.

**Interfaces:**
- Consumes: completed Week 7 branch.
- Produces: reviewable, clean, CI-equivalent portfolio branch.

- [ ] **Step 1: Verify documentation and scripts**

Run shell tests, `bash -n`, PowerShell docs tests, benchmark verifier tests, Compose config validation, Markdown link validation, and image size/metadata checks.

- [ ] **Step 2: Verify demo from a clean Compose environment**

Build/start eight services, require all healthy, seed twice, execute the documented USER/ADMIN API examples, cleanup twice, and prove a non-demo sentinel survives.

- [ ] **Step 3: Run complete project regression once**

Run backend `clean test` and frontend unit tests serially, build, and lint. Preserve machine-readable test artifacts and report exact counts/failures.

- [ ] **Step 4: Review portfolio claims against evidence**

Cross-check README/report numbers with JSON/test artifacts, architecture names with Compose, API examples with routes, and every screenshot against its manifest. Run secret scan and `git diff --check`.

- [ ] **Step 5: Commit verification fixes if any**

Commit only proven fixes with scoped tests. End with clean Git status and do not push or merge without explicit user authorization.
