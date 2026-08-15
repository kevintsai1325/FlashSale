# k6 Purchase-Flow Correctness Script

This is a **correctness check, not a performance benchmark**. Per the Week 4
design spec (§9 / §15: "先不設定量化目標,實測後再記錄於 README" — no quantitative
targets, just record what's actually observed), there is no RPS/latency
target to hit. The only thing this script proves is the invariant
`backend/src/test/java/com/flashsale/order/adapter/web/PurchaseConcurrencyIT`
already proves at the MockMvc level — **no oversell, no duplicate orders**
under real concurrent load — but end-to-end through the real stack: Nginx
(with its rate limiter), the backend, Postgres, and RabbitMQ.

No `k6` binary is assumed on `PATH`. All commands below run k6 via the
official `grafana/k6` Docker image against the Docker Compose stack.

## Step 0: Reset before re-running (skip on the very first run)

`compose.yaml` declares a named volume for Postgres data, which persists
across `docker compose down` (without `-v`). Re-running Step 1's seed SQL a
second time without resetting first hits a primary-key conflict (`products`/
`flash_sales`/`inventory` id `1` already exists), and even if you dodge that
by picking new ids, leftover rows from the previous run would silently
inflate Step 3's verification counts. Before seeding again, truncate just the
tables this script touches:

```bash
docker compose exec -T postgres psql -U flashsale -d flashsale <<'EOF'
TRUNCATE TABLE purchase_requests, order_items, orders, inventory, flash_sales, products, users RESTART IDENTITY CASCADE;
EOF
```

(A full `docker compose down -v` before `docker compose up --build -d` also
works and is a bit more foolproof — it forces Flyway migrations to rerun from
scratch too — but is slower to iterate with; the `TRUNCATE` above is the
faster option for repeated runs against an already-running stack.)

## Step 1: Seed a flash sale with known, small stock

Scale, following the same values/structure `PurchaseConcurrencyIT` and the
backend's own fixture files
(`backend/src/test/resources/db/testdata/*-fixtures.sql`) already use —
`STOCK` units of inventory, more VUs (`VUS`) than stock so the sold-out path
is meaningfully exercised:

- `STOCK = 10`
- `VUS = 30`

After `docker compose up --build -d` (see repo root `README.md` for `.env` /
TLS-cert setup first, if not already done), seed the database directly —
same shape as `flashsale-fixtures.sql`, with `available_quantity` set to
`STOCK` instead of that file's `42`:

```bash
docker compose exec -T postgres psql -U flashsale -d flashsale <<'EOF'
INSERT INTO products (id, name, description) VALUES (1, 'Limited Sneakers', 'Only 100 pairs');
INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE');
INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 10, 10, 0, 0, 0);
EOF
```

(`total_quantity`/`available_quantity` = `STOCK` = 10; adjust both together
if you override `STOCK`.)

## Step 2: The script

`load-tests/purchase-flow.js`. Each VU:

1. Registers a unique user (`POST /api/auth/register`) and logs in
   (`POST /api/auth/login`).
2. Fires `POST /api/flash-sales/{id}/purchase-requests` with a unique
   `Idempotency-Key`. `check()`s that this call itself never returns a raw
   5xx (it should always be `202`, whether the eventual outcome is a sale or
   `SOLD_OUT` — matching the invariant `PurchaseConcurrencyIT` proves at the
   backend-test level).
3. Polls `GET /api/purchase-requests/{requestId}` every 1s (matching
   `PurchaseStatusPage.tsx`'s own `refetchInterval`) until a terminal status
   (`SUCCEEDED` / `SOLD_OUT` / `REJECTED` / `FAILED`).

`VUS`, `STOCK`, `FLASH_SALE_ID` and `BASE_URL` are all overridable via
`__ENV` (`-e VUS=... -e STOCK=... -e FLASH_SALE_ID=... -e BASE_URL=...`);
defaults are `30`, `10`, `1`, and `https://nginx:8443` respectively, matching
the fixture seeded above.

**Why staggered registration**: `nginx/nginx.conf` rate-limits
`/api/auth/(login|register)` to 5 requests/sec (burst 10, `nodelay`). 30 VUs
each firing register+login simultaneously would blow straight through that
limit and produce spurious `503`s unrelated to the invariant under test. The
script staggers each VU's register/login by `0.5s * (VU index)`, then has
every VU wait out the remainder of a shared window before firing its
purchase-request — so despite the staggered auth calls, all VUs still hit
`POST /purchase-requests` at (approximately) the same moment, which is the
actual contention this script needs to exercise.

## Step 3: Post-run correctness verification (manual `psql` check)

Per the design spec's explicit "no CI integration this round," verification
here is a documented manual check rather than a k6 `handleSummary`/teardown
step:

All queries below are scoped to `flash_sale_id = 1` (the id seeded in Step 1)
so that, if you skip Step 0's reset, leftover rows from a previous run can't
silently inflate these counts (`orders` has no `flash_sale_id` column of its
own, so that one is scoped via a join through `purchase_requests`):

```bash
docker compose exec -T postgres psql -U flashsale -d flashsale <<'EOF'
-- Must equal STOCK (10): exactly as many orders as there was stock, never more.
SELECT COUNT(*) AS successful_orders
FROM orders o
JOIN purchase_requests pr ON pr.order_id = o.id
WHERE pr.flash_sale_id = 1;

-- Must equal VUS - STOCK (30 - 10 = 20): every buyer who didn't get stock is SOLD_OUT.
SELECT COUNT(*) AS sold_out_requests FROM purchase_requests WHERE status = 'SOLD_OUT' AND flash_sale_id = 1;

-- Sanity: inventory must never go negative or under-decrement.
SELECT available_quantity, reserved_quantity, sold_quantity FROM inventory WHERE flash_sale_id = 1;

-- Sanity: every purchase_request should have landed in a terminal state (no VU stuck PENDING).
SELECT status, COUNT(*) FROM purchase_requests WHERE flash_sale_id = 1 GROUP BY status ORDER BY status;
EOF
```

## Step 4: Run it

```bash
# 1. Bring up the full stack (see repo root README.md for first-time .env / TLS-cert setup)
docker compose up --build -d

# 2. Seed the fixture (Step 1 above)

# 3. Find the compose network name (usually "<project-dir-name>_default")
docker network ls

# 4. Run k6 against the stack via Docker (adjust the network name and the
#    absolute path to load-tests/ for your machine; MSYS_NO_PATHCONV=1 avoids
#    Git-Bash mangling the -v path on Windows, same as the frontend Docker
#    workaround elsewhere in this repo)
MSYS_NO_PATHCONV=1 docker run --rm --network week4-plan_default \
  -v "/c/SideProject/FlashSale/.claude/worktrees/week4-plan/load-tests:/scripts" \
  -i grafana/k6 run /scripts/purchase-flow.js -e VUS=30 -e STOCK=10

# 5. Run the Step 3 verification queries
```

## Actual results (recorded run)

Run on 2026-08-15 against a freshly built stack (`docker compose up --build -d`),
seeded per Step 1 with `STOCK=10`, `VUS=30`, `FLASH_SALE_ID=1`.

Command:

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network week4-plan_default \
  -v "/c/SideProject/FlashSale/.claude/worktrees/week4-plan/load-tests:/scripts" \
  -i grafana/k6 run /scripts/purchase-flow.js -e VUS=30 -e STOCK=10
```

k6 summary (30 VUs, 1 iteration each):

```
checks_total.......: 160     8.315319/s
checks_succeeded...: 100.00% 160 out of 160
checks_failed......: 0.00%   0 out of 160

✓ register succeeded (201)
✓ login succeeded (200)
✓ purchase-request never raw 5xx
✓ purchase-request returns 202
✓ purchase request reached a terminal status
✓ poll never raw 5xx

HTTP
http_req_duration..............: avg=69.91ms min=7.67ms med=61.14ms max=136.68ms p(90)=112.56ms p(95)=118.66ms
http_req_failed................: 0.00%  0 out of 100
http_reqs......................: 100    5.197074/s

EXECUTION
iteration_duration.............: avg=18.57s  min=18.22s med=18.24s  max=19.24s   p(90)=19.23s   p(95)=19.23s
iterations.....................: 30     1.559122/s
vus_max.........................: 30

Wall-clock duration of the whole run: ~19.2s (dominated by the deliberate
register/login stagger — see "Why staggered registration" above, not by
backend/DB latency; actual HTTP request latency through Nginx averaged 70ms).
```

Per-VU outcome breakdown from k6's console log: 20 VUs got `finalStatus=SOLD_OUT`
(all resolved synchronously, `polls=0`), 10 VUs got `finalStatus=SUCCEEDED`
(resolved after exactly 1 poll, i.e. within ~1-2s of the async consumer
processing the reservation). No VU saw a raw 5xx, a stuck `PENDING`, a
`REJECTED`, or a `FAILED` at any point.

Step 3 verification queries, run immediately after:

```
 successful_orders
-------------------
                10
(1 row)

 sold_out_requests
-------------------
                20
(1 row)

 available_quantity | reserved_quantity | sold_quantity
---------------------+-------------------+---------------
                   0 |                 0 |            10
(1 row)

  status   | count
-----------+-------
 SOLD_OUT  |    20
 SUCCEEDED |    10
(2 rows)
```

**Invariants held exactly, on the first real run, no fixture/script fixes needed:**

- `successful_orders` (10) == seeded `STOCK` (10) — no oversell.
- `sold_out_requests` (20) == `VUS - STOCK` (30 - 10 = 20) — every buyer who
  didn't get stock was told `SOLD_OUT`, none errored or hung.
- `inventory.available_quantity` reached exactly `0` with `sold_quantity`
  exactly `10` — inventory never went negative or double-decremented.
- Every `purchase_requests` row landed in a terminal status (`SOLD_OUT` or
  `SUCCEEDED`); none were left `PENDING`, and none were `REJECTED`/`FAILED`
  (expected, since every buyer is a unique user purchasing exactly once).

There is no quantitative target here per the design spec — the numbers above
(70ms avg request latency, 19.2s wall-clock for the whole run, 5.2 req/s
aggregate `http_reqs` rate) are recorded as observed, for later reference
(e.g. Week 6's portfolio writeup), not as a pass/fail bar.
