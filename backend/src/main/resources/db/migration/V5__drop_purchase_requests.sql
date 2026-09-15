-- purchase_requests 搬到 purchase-service 自己的資料庫了（P4 步驟 2）。
--
-- 這是整個拆分裡唯一不可逆的一步，所以順序很重要：
--   V4 先把 flash_sale_id 與 purchase_request_id 回填進 orders（上一個 commit），
--   backend 的三處讀取先改掉（上一個 commit），
--   最後才是這裡刪表。反過來做的話，中間會有一段時間 backend 讀不到也補不回來。
--
-- 既有資料的搬遷不在 migration 裡：這是作品集專案，purchase-service 的新資料庫
-- 從空的開始。真實系統要在這裡多一步 dump/restore，並且在雙寫期間驗證兩邊一致。
DROP TABLE IF EXISTS purchase_requests;
