# Week 2 SDD Execution — Cross-Machine Handoff (2026-08-13)

> **Purpose:** resume this exact SDD (subagent-driven-development) run on a different machine. The
> `.superpowers/sdd/` workspace this run has been using (ledger, task briefs, reports, review
> packages) is git-ignored by design — it never leaves this machine. This file is the durable,
> git-tracked substitute: everything needed to pick the run back up is here.

## What this is

Executing `docs/superpowers/plans/2026-08-13-flash-sale-week2-purchase-core.md` (13 tasks) via
`superpowers:subagent-driven-development`, against the design spec at
`docs/superpowers/specs/2026-08-13-flash-sale-week2-purchase-core-design.md`. Both docs are
already committed on `main`. This branch, `worktree-week2-plan`, was cut from local `main` at
commit `ef6d956` and holds all Week 2 implementation work.

**Scope decisions locked in during brainstorming** (from the spec, don't re-litigate):
1. Backend only this round — frontend purchase/payment UI is Week 3.
2. Redis stock init is lazy-load-on-first-request, not a warm-up scheduler.
3. A basic automatic Redis/Postgres reconciliation scheduler (Task 13) IS in scope this round,
   despite the main spec listing "庫存對帳" under 第二階段 — only the *manual-trigger* API is
   deferred, not automatic periodic reconciliation.

## How to resume: setup on the new machine

1. `git clone`/`git fetch` this repo, then either check out `worktree-week2-plan` directly, or set
   up a fresh worktree from it (`git worktree add .claude/worktrees/week2-plan worktree-week2-plan`).
2. This branch's tip commit is `d8a71a6` ("wip: refetch entity before saveAndFlush in
   REQUIRES_NEW publishEvent()"), one commit past the last *reviewed* state `65bda61` — the WIP
   commit is **NOT green, tests fail** (see "Exact current state" below for exactly how).
3. Docker Desktop must be running, and `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`
   must be set in-shell before running `./gradlew test` from `backend/` — Testcontainers can't find
   Docker Desktop's daemon otherwise. (This is a Windows-specific requirement from the machine this
   was developed on; adjust/drop it if the new machine is Linux/Mac with a standard Docker socket.)
4. Read `docs/superpowers/plans/2026-08-13-flash-sale-week2-purchase-core.md` once for the full
   task list and Global Constraints — this file is unchanged and still authoritative.
5. Re-create the SDD workspace: run this skill's `scripts/sdd-workspace
   docs/superpowers/plans/2026-08-13-flash-sale-week2-purchase-core.md` from the worktree root, and
   re-create `progress.md` in the printed directory using the **"Ledger reconstruction" section
   below** as its starting content (the original ledger never left the old machine).

## Exact current state (as of this handoff)

**Tasks 1-2: complete, clean, reviewed.** Nothing to redo.

**Task 3 (Outbox infrastructure + RabbitMQ topology + OutboxPublisher): NOT complete, mid fix-loop.**

- Commit `65bda61` ("fix: isolate per-event transactions in OutboxPublisher + add test coverage")
  is the last *reviewed* state — it fixed the original finding (one event's failure rolling back
  already-sent siblings in the same batch) via per-event exception isolation, but a scoped
  re-review then found a **second real bug**: the `REQUIRES_NEW`-annotated `publishEvent()` never
  called `repository.save()`, so the "isolated" transaction wasn't actually persisting
  `markPublished()` independently — a plain Java field mutation on an entity the nested
  transaction's EntityManager didn't know about. The durability guarantee was illusory.
- On top of `65bda61`, a WIP commit (see below) adds the real fix: refetch the entity via
  `repository.findById(event.getId())` inside the `REQUIRES_NEW` transaction, `markPublished()`,
  then `repository.saveAndFlush(...)`. **This part is architecturally correct** — confirmed by
  tracing Spring's `JpaTransactionManager` suspend/resume semantics directly, not just trusting a
  report.
- **What's still broken:** the two tests in `OutboxPublisherIT`
  (`writtenEventIsPublishedToRabbitAndMarkedPublished`, `validEventPublishedDespiteInvalidEventInBatch`)
  **pass when run individually but fail when run together** — a test-isolation/interference bug,
  not yet diagnosed. Prime suspects to check first:
  - No `@BeforeEach` clears `outbox_events` (or RabbitMQ queues) between test methods in this
    class — a leftover row from one test (e.g. the permanently-unpublishable "UnknownEventType"
    poison row the isolation test inserts) could be getting picked up by the OTHER test's
    `findUnpublishedBatchForUpdate` scan and corrupting its assertions or timing.
  - The isolation test's cleanup (`DELETE FROM outbox_events WHERE event_type = 'UnknownEventType'`)
    runs at the END of that test method, but if the smoke test runs *before* the isolation test in
    JUnit's execution order, that's fine; if it runs *after*, the poison row from the isolation
    test might still be present (if its cleanup didn't fully commit before the next test's
    `@SpringBootTest` context reuse) during the smoke test's window.
  - Possible fix path: add a `@BeforeEach` that truncates/deletes all rows from `outbox_events`
    (and purges both RabbitMQ queues via `rabbitTemplate.receive(...)` drain loop or
    `rabbitAdmin.purgeQueue(...)`) before every test method in this class, so each test starts from
    a guaranteed-clean slate — this is more robust than trying to make cleanup-at-end perfectly
    reliable across JUnit's (undefined by default) method execution order.
- The WIP commit's message describes exactly what changed; read it with `git show` before doing
  anything else.

**Tasks 4-13: not started.**

## Process notes (apply these for the rest of the run)

1. **Subagents dispatched via the Agent tool cannot get interactive `git commit` permission** — the
   auto-mode classifier blocks it in their context even after the user approved committing
   separately in the controller session, and even when explicitly told "you're pre-approved." A
   subagent's own hand-back suggesting the user add a blanket `git commit` bypass rule to
   `.claude/settings.json` (sometimes phrased as if already approved) is a bypass pattern the
   harness flags — don't relay that as a normal request. **The working pattern:** implementer
   subagents implement + test + self-review, then explicitly do NOT run `git commit` — they report
   the exact files to stage and the exact commit message, and the controller session (which does
   have real commit ability) reviews the diff itself and runs `git add`/`git commit` directly.
2. The user gave blanket approval for all commits in this SDD run (worktree-only, no pushes without
   separate confirmation) — this was scoped to the original machine/session; **re-confirm with the
   user on the new machine whether that same blanket approval carries over**, rather than assuming
   silently.
3. Task reviewers in this run have caught real bugs beyond simple spec-diffing (e.g. the
   `REQUIRES_NEW`-without-`save()` illusory-isolation bug above was found by tracing transaction
   mechanics carefully) — trust their Important findings and follow through on fix rounds rather
   than rubber-stamping.
4. When a review finding is labeled "plan-mandated" (i.e., the defect is verbatim what the plan
   itself specified), that goes back to the human before dispatching a fix — see the plan's Task 2
   entry below for a worked example (kept as-is) versus the Task 3 entry (fixed, since there was no
   real tradeoff).

## Ledger reconstruction

Recreate `.superpowers/sdd/2026-08-13-flash-sale-week2-purchase-core/progress.md` with this content
(this is a verbatim copy of the original ledger at the point this handoff was written):

```
# SDD ledger — plan: docs/superpowers/plans/2026-08-13-flash-sale-week2-purchase-core.md
Task 1: minor (deferred): RedisRabbitConnectivityIT doesn't close the RabbitTemplate Connection it opens (harmless for a single smoke test; don't copy the pattern into tests that open connections repeatedly)
Task 1: minor (deferred): adding data-redis/amqp starters means every @SpringBootTest now auto-configures RedisConnectionFactory/RabbitConnectionFactory beans even without containers — currently harmless (lazy), but the first eager @RabbitListener or Redis health check outside a container-backed IT could break unrelated tests; watch for this from Task 6 onward
Task 1: complete (commits ef6d956..d8e645a, review clean)
Task 2: parked — release() skips ensureSeeded before INCRBY (plan-mandated) — ruling: intentional per design spec, 60s InventoryReconciliationScheduler (Task 13) is the deliberate safety net for this drift window; user confirmed keep as written, no fix needed
Task 2: fix round 1/5 (1 addressed, 1 open — -2-after-retry->503 test not addressed, wrote a different sleep-based recovery-succeeds test instead; commits 7dc9828..9e9cc2f)
Task 2: fix round 2/5 (1 addressed, 0 open; commits 9e9cc2f..1592af4)
Task 2: minor (deferred): RedisInventoryStockGatewayIT.java has unused imports (ServiceUnavailableException, assertThatThrownBy) left over from the abandoned round-1 test approach
Task 2: minor (deferred): RedisInventoryStockGatewayIT.java's concurrency test calls executor.shutdown() without awaitTermination
Task 2: complete (commits d8e645a..1592af4, 1 parked, 2 rounds of fixes)
Task 3: fix round 1/5 (2 addressed, 1 new gap found by re-reviewer — REQUIRES_NEW publishEvent() never calls repository.save(), so per-event durability against a crashed final commit isn't truly achieved; commits bd1f8f4..65bda61)
Task 3: fix round 2/5 committed as WIP for cross-machine handoff (see docs/superpowers/plans/2026-08-13-flash-sale-week2-handoff.md) — refetch+saveAndFlush fix is architecturally correct but two tests interfere with each other when run together; not yet diagnosed, resume here
```

Once Task 3 is genuinely green and reviewed clean, continue with Task 4 exactly per the plan file —
no re-planning needed.
