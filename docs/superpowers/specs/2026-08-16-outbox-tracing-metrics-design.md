# Outbox Trace 與 Redis 失敗指標設計規格

## 目標

保存 outbox 交易建立時的 trace parent，發布時恢復同一 trace；同時讓 Redis 庫存預扣例外記錄
低基數 `error` outcome。

## 設計

- Flyway V2 為 `outbox_events` 增加 nullable `trace_context` JSONB；legacy rows 維持可發布。
- `OutboxWriter` 僅保存版本、traceId、spanId、sampled，不保存 baggage 或使用者資料。
- `OutboxPublisher` 以保存的 context 建立新的 publish span，並由 Spring AMQP observation 傳播。
- Redis reserve 正常結果維持 `reserved`／`insufficient_stock`；所有 RuntimeException 記錄一次
  `error` 後原樣拋出，latency timer 一律停止。

## 驗收

- writer 與 publisher 單元測試驗證 context capture、parent restoration 與 span 結束。
- Redis exception、`-2` 與 null 每次只增加一個 error counter，原 exception instance 不變。
- 最終完整 integration run 驗證 migration、legacy row 與真實 Rabbit propagation。
