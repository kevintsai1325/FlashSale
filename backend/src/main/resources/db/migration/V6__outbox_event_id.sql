-- 每筆 outbox 事件帶一個全域唯一的 event_id。
--
-- 為什麼需要：消費端的去重（consumed_messages）原本用的是「發佈端 outbox 表的 BIGSERIAL id」。
-- 兩個服務共用一張 outbox 表時那個數字是唯一的；P4 步驟 2 拆庫之後，purchase-service 的
-- outbox 從 1 重新開始，而 backend 的 consumed_messages 裡早就有 message_id='1' ——
-- 第一筆搶購的建單事件因此被當成重複投遞，靜靜地丟掉，搶購請求永遠停在 PENDING。
--
-- 這個 bug 只有真的部署才會出現：單元測試各自挑自己的 id，整合測試每次都清空資料庫。
-- 修法是讓事件識別碼不再依賴任何一個資料庫的序號 —— UUID 由發佈端產生，
-- 跟著訊息走，消費端拿它去重。這也是 CloudEvents 的 id 欄位在做的事。
ALTER TABLE outbox_events ADD COLUMN event_id UUID;
UPDATE outbox_events SET event_id = gen_random_uuid() WHERE event_id IS NULL;
ALTER TABLE outbox_events ALTER COLUMN event_id SET NOT NULL;
CREATE UNIQUE INDEX idx_outbox_events_event_id ON outbox_events(event_id);
