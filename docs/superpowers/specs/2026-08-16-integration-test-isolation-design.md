# 整合測試背景任務隔離設計規格

## 問題與目標

部分 IT 各自啟動 Postgres、Redis 與 RabbitMQ，因 cached context 中的真實 listener 與 scheduler
會跨測試搶訊息或處理資料。目標是關閉 integration profile 的非受控背景執行緒，讓所有 IT 安全
共用 `AbstractIntegrationTest` 的 containers，同時保留真實基礎設施與核心斷言。

## 設計

- 將 `@EnableScheduling` 移至受 `app.scheduling.enabled` 控制的設定類，production 預設啟用。
- integration-test profile 關閉 scheduling 與 Rabbit listener auto-startup；bean 本身仍存在。
- scheduler、outbox publisher 與 consumer 由測試顯式呼叫；Rabbit message 仍經真實 broker 傳遞。
- 9 個獨立-container IT 改繼承共享基底；每個測試後統一清理 DB、Redis 與 queues。
- 不使用固定 sleep，不刪除 redelivery、DLQ、冪等或不超賣斷言。

## 驗收

- 設定單元測試證明 scheduling 預設啟用且可關閉。
- 所有轉換後測試碼可編譯。
- 所有技術債完成後，完整 backend integration suite 只執行一次並確認無背景競爭。
