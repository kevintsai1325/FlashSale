-- Benchmark fixture: reset the benchmark database and seed exactly one active
-- flash sale with a known amount of stock.
--
-- DESTRUCTIVE. This truncates every domain table, so it must only ever be piped
-- into the PostgreSQL container of the isolated `flashsale-benchmark` Compose
-- project. collect.ps1 verifies that project identity (Compose project label and
-- config file) before it pipes this file anywhere; do not run it by hand against
-- a stack you care about.
--
--   docker compose -p flashsale-benchmark --project-directory . \
--     -f load-tests/benchmark/compose.benchmark.yaml exec -T postgres \
--     psql -U flashsale -d flashsale -v stock=10 < load-tests/benchmark/fixtures.sql
--
-- `:stock` is required and sets both total and available quantity, so every run
-- starts from a stock level the verifier can compare the order count against.

\set ON_ERROR_STOP on

BEGIN;

-- Wipe first: each measured run must start from an empty database, otherwise the
-- post-run invariant queries (orders created, residual PENDING, inventory) would
-- count rows an earlier run produced.
TRUNCATE TABLE
    api_audit_logs,
    order_status_history,
    payment_records,
    notification_deliveries,
    consumed_messages,
    outbox_events,
    purchase_requests,
    order_items,
    orders,
    inventory,
    flash_sales,
    products,
    refresh_tokens,
    users
    RESTART IDENTITY CASCADE;

INSERT INTO products (id, name, description)
VALUES (1, '[BENCHMARK] Load Harness Product', 'Seeded by load-tests/benchmark/fixtures.sql');

-- purchase_limit_per_user = 1 so that one buyer can only ever hold one order:
-- that is what makes "duplicate orders per user" a meaningful invariant.
INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '6 hours', 1, 'ACTIVE');

INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, :stock, :stock, 0, 0, 0);

-- The three inserts above set explicit ids, which leaves their sequences behind.
-- Advance them so anything the application inserts later (admin CRUD, reruns)
-- cannot collide with the fixture rows.
SELECT setval('products_id_seq', 1, true);
SELECT setval('flash_sales_id_seq', 1, true);
SELECT setval('inventory_id_seq', 1, true);

COMMIT;

SELECT 'benchmark fixture ready' AS status,
       (SELECT total_quantity FROM inventory WHERE flash_sale_id = 1) AS stock;
