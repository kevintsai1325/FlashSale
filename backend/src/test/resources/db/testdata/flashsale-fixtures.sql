-- P5：inventory 已經在 order-service 的資料庫，這裡只剩 platform 擁有的兩張表。
-- 庫存數量由測試 stub OrderServiceClient 供應（見 FlashSaleControllerIT）。
INSERT INTO products (id, name, description) VALUES (1, 'Limited Sneakers', 'Only 100 pairs');
INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE');
