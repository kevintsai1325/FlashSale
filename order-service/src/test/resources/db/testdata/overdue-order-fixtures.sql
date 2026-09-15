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
--
-- P5：這裡不再需要 users / products / flash_sales —— 那三張表屬於 platform，
-- 而 orders 對它們只有數字、沒有外鍵。fixture 因此比拆分前短了一半，
-- 這正是「服務只擁有自己的資料」在測試上長出來的樣子。

INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 1, 0, 0, 1, 0);

INSERT INTO orders (id, order_no, user_id, total_amount, status, payment_due_at, flash_sale_id, purchase_request_id)
VALUES (1, 'ORD-OVERDUE-1', 1, 9.99, 'PENDING_PAYMENT', now() - interval '1 hour',
        1, '11111111-1111-1111-1111-111111111111');

INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price)
VALUES (1, 1, 1, 'Limited Sneakers', 1, 9.99);

-- 讓序列跳過手動指定的 id，避免後續 INSERT 撞主鍵。
SELECT setval('inventory_id_seq', (SELECT max(id) FROM inventory));
SELECT setval('orders_id_seq', (SELECT max(id) FROM orders));
SELECT setval('order_items_id_seq', (SELECT max(id) FROM order_items));
