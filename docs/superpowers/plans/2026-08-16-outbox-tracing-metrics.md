# Outbox Tracing and Redis Metrics Implementation Plan

**Goal:** 保存並恢復 outbox trace parent，並讓 Redis reservation 失敗產生低基數 error metric。

## Tasks

- [x] 新增 nullable JSONB migration 與版本化 `StoredTraceContext`。
- [x] OutboxWriter capture current span，無 span 時存 null。
- [x] OutboxPublisher 從 stored parent 建立每次發布專用的新 span。
- [x] 新增 writer/publisher 單元測試與 migration integration assertion。
- [x] Redis reserve exception 記錄一次 `outcome=error` 並原樣 rethrow。
- [x] 驗證 success、insufficient、`-2`、null、connection exception 與 latency samples。
- [ ] 全部技術債完成後執行一次完整 integration suite 與 trace smoke verification。
