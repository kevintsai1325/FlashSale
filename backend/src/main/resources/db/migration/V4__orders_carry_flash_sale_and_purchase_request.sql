-- 訂單記下自己來自哪一場活動、哪一筆搶購請求。
--
-- 這兩個欄位在拆庫前就該存在：訂單本來就是「某人在某場活動買到某商品」這件事的紀錄，
-- 而 flash_sale_id 一直只存在於 purchase_requests，補償流程必須反查才拿得到。
-- P4 步驟 2 把 purchase_requests 搬到 purchase-service 自己的資料庫之後，那個反查會跨庫，
-- 所以回填必須在拆庫之前做完 —— 這是最後一次兩張表還在同一個資料庫裡的機會。
--
-- 刻意不加 flash_sale_id 的外鍵：flash_sales 屬於 platform、orders 屬於 order-service，
-- P5 拆庫後這條外鍵會跨越服務邊界。現在加、下一階段再拆掉，等於做白工。
ALTER TABLE orders ADD COLUMN flash_sale_id BIGINT;
ALTER TABLE orders ADD COLUMN purchase_request_id BIGINT;

UPDATE orders o
   SET flash_sale_id = pr.flash_sale_id,
       purchase_request_id = pr.id
  FROM purchase_requests pr
 WHERE pr.order_id = o.id;

CREATE INDEX idx_orders_flash_sale_id ON orders(flash_sale_id);

-- 不設 NOT NULL：回填只涵蓋得到「有對應搶購請求的訂單」。理論上每一筆訂單都是搶購建立的，
-- 但若有任何一筆不是（手動塞的測試資料、將來的其他下單管道），NOT NULL 會讓 migration 直接失敗，
-- 而拿不到 flash_sale_id 的後果只是那一筆無法自動釋放庫存 —— 用可為 null 換整個 migration 的安全。
