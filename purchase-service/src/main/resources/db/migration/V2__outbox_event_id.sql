-- 與 backend 的 V6 同一件事、同一個理由（見那支 migration 的說明）：
-- 消費端的去重不能依賴發佈端資料庫的序號，因為拆庫之後那個序號從 1 重新開始。
ALTER TABLE outbox_events ADD COLUMN event_id UUID;
UPDATE outbox_events SET event_id = gen_random_uuid() WHERE event_id IS NULL;
ALTER TABLE outbox_events ALTER COLUMN event_id SET NOT NULL;
CREATE UNIQUE INDEX idx_outbox_events_event_id ON outbox_events(event_id);
