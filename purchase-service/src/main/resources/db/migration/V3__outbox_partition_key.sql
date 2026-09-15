-- Kafka 的分區鍵，理由與 backend 的 V7 相同（見那支 migration 的說明）。
ALTER TABLE outbox_events ADD COLUMN partition_key VARCHAR(100);
