-- 一場活動的庫存。P5 之後這個資料庫裡沒有 products / flash_sales —— 它們屬於 platform。
-- 庫存列只認得 flash_sale_id 這個數字，沒有外鍵，所以 fixture 也只需要這一列。
INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 1, 1, 0, 0, 0);
SELECT setval('inventory_id_seq', (SELECT max(id) FROM inventory));
