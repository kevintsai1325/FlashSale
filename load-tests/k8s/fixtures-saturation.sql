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
