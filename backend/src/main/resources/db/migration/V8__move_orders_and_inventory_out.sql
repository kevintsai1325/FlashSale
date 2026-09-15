-- 訂單、訂單明細、狀態歷程、付款紀錄與庫存搬到 order-service 自己的資料庫了（P5）。
--
-- 順序與 V5（搬走 purchase_requests）相同，理由也相同：
--   1. 先讓事件帶上商品名稱，order-service 才不需要 catalog；
--   2. 再把 platform 的三處讀取換成跨服務呼叫（店面列表、後台訂單、後台儀表板）；
--   3. 最後才刪表。反過來做，中間會有一段時間 platform 讀不到也補不回來。
--
-- outbox_events 與 consumed_messages 也一起刪：platform 在這一步之後**不再發佈也不再消費
-- 任何訊息**。它剩下的職責是身分、商品、活動、通知與後台 —— 全部是同步的請求／回應。
-- 留著一組沒有人用的 outbox 機制，只會讓下一個人以為這裡還有非同步流程。
--
-- inventory 的外鍵（flash_sales）也隨表消失。order-service 那邊只留 flash_sale_id 這個數字：
-- 跨服務的參照完整性由流程保證，不再由資料庫保證。
DROP TABLE IF EXISTS payment_records;
DROP TABLE IF EXISTS order_status_history;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS inventory;
DROP TABLE IF EXISTS outbox_events;
DROP TABLE IF EXISTS consumed_messages;
