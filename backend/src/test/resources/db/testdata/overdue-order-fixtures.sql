-- 一筆逾時未付款的訂單，庫存已售罄。
--
-- 這份 fixture 專門給 PaymentTimeoutSchedulerConcurrencyIT 用：它讓「庫存被回補幾次」
-- 直接可觀察。inventory 起始為 total=1、available=0、sold=1（售罄），而
-- Inventory.release(q) 是 availableQuantity += q、soldQuantity -= q，沒有任何邊界檢查。
--
--   回補一次 → available=1、sold=0   （正確）
--   回補兩次 → available=2、sold=-1  （available 超過 total，且 sold 變負；兩者都不可能）
--
-- payment_due_at 設在過去，讓 PaymentTimeoutScheduler 的
-- findByStatusAndPaymentDueAtBefore(now) 一定撈得到這筆訂單。

INSERT INTO users (id, email, password_hash, role, status)
VALUES (1, 'overdue-buyer@example.com', 'not-a-real-hash', 'USER', 'ACTIVE');

INSERT INTO products (id, name, description) VALUES (1, 'Limited Sneakers', 'Only 100 pairs');

INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE');

INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 1, 0, 0, 1, 0);

-- flash_sale_id 是 compensate() 釋放庫存的依據（V4 之後由訂單自己帶著，不再反查 purchase_requests）。
INSERT INTO orders (id, order_no, user_id, total_amount, status, payment_due_at, flash_sale_id, purchase_request_id)
VALUES (1, 'ORD-OVERDUE-1', 1, 9.99, 'PENDING_PAYMENT', now() - interval '1 hour',
        1, '11111111-1111-1111-1111-111111111111');

INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price)
VALUES (1, 1, 1, 'Limited Sneakers', 1, 9.99);


-- 讓序列跳過手動指定的 id，避免後續 INSERT 撞主鍵。
SELECT setval('users_id_seq', (SELECT max(id) FROM users));
SELECT setval('products_id_seq', (SELECT max(id) FROM products));
SELECT setval('flash_sales_id_seq', (SELECT max(id) FROM flash_sales));
SELECT setval('inventory_id_seq', (SELECT max(id) FROM inventory));
SELECT setval('orders_id_seq', (SELECT max(id) FROM orders));
SELECT setval('order_items_id_seq', (SELECT max(id) FROM order_items));
