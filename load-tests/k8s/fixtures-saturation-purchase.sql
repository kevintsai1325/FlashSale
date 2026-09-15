-- purchase-service 自己資料庫的重置（P4 步驟 2 起兩個資料庫要各自清一次）。
--
-- 這兩張表沒有外鍵指向 platform 的任何東西，所以不能靠那邊的 CASCADE 一起清掉——
-- 漏了這一步，上一輪殘留的 PENDING 會被下一輪的「不殘留 PENDING」驗收條件算進去，
-- 看起來像這次跑出了問題，實際上是上次的殘渣。
TRUNCATE TABLE purchase_requests, outbox_events RESTART IDENTITY;
