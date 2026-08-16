# 技術債清理總體 Roadmap 設計規格

## 背景

Week 6 完成後，交接文件列出 7 類尚未處理的技術債；本次盤點另發現前端沒有一致處理 API
401／403。使用者決定先清理這 8 類技術債，再開始 Week 7 作品集包裝工作。

本文件定義整體分批、依賴與交付方式。每一批仍須有可直接實作的子 spec 與 implementation plan；
所有規劃經確認後，才開始修改程式碼。

## 整體原則

- 分成 6 批，每批使用獨立 `codex/` 分支、獨立測試與獨立合併確認。
- 不直接在 `main` 修改；commit 與 push 均須取得使用者明確同意。
- 每批採測試驅動，先建立可重現問題的失敗測試，再做最小範圍修正。
- 每批先跑相關測試，交付前再跑與風險相稱的完整測試。
- 不把順手發現、但不屬於本 roadmap 的重構混入任何批次。
- 文件、註解與使用者可見文字維持繁體中文；穩定的程式識別碼保留英文。

## 執行順序

### 批次 1：後端 Security error 稽核

修正 `ApiAuditFilter` 漏記 401／403：將 filter 納入 Spring Security chain，確保每個請求恰好
一筆紀錄。401 使用 `UNAUTHENTICATED` 且沒有 userId；403 使用 `ACCESS_DENIED`，有效 JWT
保留 userId。

詳細設計見 `2026-08-16-api-audit-security-errors-design.md`。

### 批次 2：前端 401／403 全域處理

建立保留 HTTP status 與 Problem Details code 的 API error 型別。一般 API 回 401 時，只進行一次
refresh；成功後重送原請求，失敗則清除登入狀態並導向登入頁，保留返回路徑。多個並行 401 共用
同一個 refresh promise，避免 refresh storm。403 不登出，顯示無權限訊息；直接進入非授權後台
路由仍回首頁。

此批依賴批次 1 固定後端 401／403 語意，但不得改變後端授權規則。

### 批次 3：整合測試背景任務隔離與共用 containers

為 integration-test profile 建立可控制的 listener／scheduler 啟用策略。測試只啟用或直接呼叫
自己要驗證的背景元件，避免其他 cached ApplicationContext 的執行緒搶 RabbitMQ 訊息或修改共享資料。
在隔離根因後，將剩餘 9 個 IT 類別轉為 `AbstractIntegrationTest` 的共用 container 架構。

此批排在其他後端大改之前，目的是縮短後續完整測試週期；不得藉此弱化真實非同步行為的驗證。

### 批次 4：可觀測性補強

本批包含兩個相近但可分 commit 的項目：

1. 在 `outbox_events` 保存可傳遞的 trace context，發布 RabbitMQ message 時恢復父 trace，使 HTTP、
   outbox publisher 與 consumer 屬於同一條分散式 trace。使用新的 Flyway migration，不修改既有
   baseline migration。
2. Redis 庫存預扣失敗時記錄明確的 failure metric，與成功／售罄等正常結果區分，避免 Redis
   故障成為監控上的靜默失敗；metric 不得吞掉或改寫原例外。

### 批次 5：管理功能與視覺完整性

本批清理 Week 6 final review 與 visual audit 的低風險項目：

- 商品管理 UI 實際接上既有 `updateProduct`。
- 搶購活動列表增加編輯 UI，使用既有後端 PUT API。
- 修正已知 CSS selector scope 外漏。
- 加強 final review 指出的弱測試斷言。
- 完成 visual audit 5 項：中文結果印章、訂單空狀態副標、付款期限提示條、處理中 request ID、
  活動列表分區標題與卡片箭頭提示。

活動分區只根據既有活動狀態／時間資料呈現，不在此批新增後端分類 API 或新業務狀態。

### 批次 6：全服務即時健康度

涵蓋 Compose 中 Backend、PostgreSQL、Redis、RabbitMQ、Mailpit、Zipkin、Frontend、Nginx：

- 為缺少 healthcheck 的服務補上 Compose healthcheck，並驗證依賴啟動條件。
- 明確定義 backend readiness 是否包含必要外部依賴，避免只有 Application readiness 卻被誤認為
  完整服務健康。
- 建立受 ADMIN 保護的健康度聚合 API，提供各服務 `UP`、`DOWN` 或 `UNKNOWN`、最近檢查時間及
  不含敏感資訊的簡短原因。
- 後台新增健康度頁面，支援定時與手動重新整理。
- nginx 只代理必要的 Actuator health／metrics 路徑；health 公開回應不得洩漏細節，詳細資訊與
  metrics 維持 ADMIN JWT 保護，其他 Actuator endpoint 不對外開放。
- 不掛載 Docker socket 到應用程式；健康度由標準 Actuator contributor、服務自身 probe 或受控的
  HTTP／TCP 探測取得。

本批只提供目前快照，不做 uptime 歷史、告警、Prometheus 或 Grafana。

## 依賴關係

1. 批次 2 依賴批次 1 的後端錯誤語意。
2. 批次 3 完成後，可降低批次 4 與批次 6 的完整後端測試成本。
3. 批次 4 的 metrics 會由批次 6 對外存取規則涵蓋，但兩者不共用實作分支。
4. 批次 5 與其他批次沒有功能依賴，排在後段是為了先處理安全、測試與可觀測性風險。
5. 批次 6 涉及最多服務與安全邊界，最後獨立整合驗證。

## 分支與交付

預定分支名稱：

1. `codex/audit-security-errors`
2. `codex/frontend-auth-errors`
3. `codex/integration-test-isolation`
4. `codex/outbox-tracing-metrics`
5. `codex/admin-visual-debt`
6. `codex/service-health-dashboard`

每一批完成時提供：變更摘要、測試證據、已知限制、建議 commit 訊息；取得使用者同意後才 commit、
push 或合併。

## 共通驗收標準

- 8 類技術債都有明確子 spec、implementation plan、測試與完成定義。
- 各批不跨越已確認的範圍，能獨立回滾與審閱。
- 安全修正不記錄或顯示 token、密碼、敏感 header、內部例外堆疊或連線憑證。
- 可觀測性功能失效時不改變核心購買、訂單或登入流程的結果。
- 健康度與 Actuator 對外暴露遵守最小權限原則。
- 完成全部 6 批後，更新 handoff／README 中已失效的技術債與限制描述，再進入 Week 7。

## 規劃階段完成條件

開始實作前必須完成：

1. 本總體 roadmap 經使用者核准。
2. 6 批的子 spec 均完成、自我審查並經使用者核准。
3. 6 批的 implementation plan 均完成並可直接依步驟執行。
4. 已確認第一批的分支起點與工作目錄狀態，且沒有覆蓋使用者既有變更。
