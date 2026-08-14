INSERT INTO products (id, name, description) VALUES (1, 'Limited Sneakers', 'Only 100 pairs');
INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE');
INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 100, 42, 0, 58, 0);
