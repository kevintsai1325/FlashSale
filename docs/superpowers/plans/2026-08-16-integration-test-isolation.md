# Integration Test Isolation Implementation Plan

**Goal:** 關閉 integration-test 的非受控 scheduler/listener，顯式驅動非同步測試，讓剩餘 9 個 IT
安全共用 containers。

## Tasks

- [x] 將 `@EnableScheduling` 移至可由 `app.scheduling.enabled` 關閉的設定類。
- [x] integration-test 關閉 scheduling 與 Rabbit listener auto-startup。
- [x] 新增 scheduling 預設啟用／可關閉的設定單元測試。
- [x] 轉換 Inventory、Payment、Notification scheduler IT，直接呼叫公開入口。
- [x] 轉換 Outbox publisher 與四個 consumer/DLQ/redelivery IT，顯式驅動訊息。
- [x] 轉換 PurchaseConcurrencyIT，保留並行 HTTP request 與庫存不超賣斷言。
- [x] 更新共享 integration test 基底說明。
- [ ] 所有技術債完成後執行一次完整 integration suite。

## Verification before final integration run

- `gradlew.bat test --tests com.flashsale.common.config.BackgroundTaskConfigTest`
- `gradlew.bat compileTestJava`
- `git diff --check`
