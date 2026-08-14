# FlashSale Week 4(後台與驗證)設計規格

## 0. 關聯文件

本文件是 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`(以下稱「主規格」)§9(Admin API)、§10(後台前端)、§11(API Audit)、§12(Notification 補強部分)、§14(ArchUnit/k6)、§15 Week 4 範圍的細部設計。Week 1-3 已完成使用者側全部功能(註冊登入、搶購、訂單、模擬付款/取消)並有前端視覺設計系統(`docs/superpowers/specs/2026-08-14-flash-sale-week3-user-pages-design.md` §11)。本輪新增管理後台(前後端)、API 稽核紀錄、通知中心與重試機制、以及 ArchUnit/k6 測試基礎設施。

## 1. 範圍

包含(對應主規格 §15 Week 4):
- 後台 API:dashboard 統計/趨勢、API 稽核紀錄查詢、訂單查詢(含狀態歷程)、通知中心(列表/篩選/批次已讀未讀/重試)
- 後台前端 `/admin`:儀表板、趨勢圖、API 紀錄、訂單查詢、通知中心(含小鈴鐺未讀數)
- `ApiAuditFilter`:擷取每個 API 請求的 metadata,非同步寫入 `api_audit_logs`
- `order_status_history` 寫入:訂單狀態變更軌跡,供後台訂單詳情頁呈現
- 通知重試補強:目前的註冊信寄送沒有真正的重試機制(見 §2.1),本輪補上有限次數指數退避重試 + 後台手動重試
- ArchUnit:落實主規格 §5 的模組邊界規則
- k6:壓測腳本驗證不超賣/不重複建單(不設定量化效能目標,主規格 §15 原文)

不包含(留給主規格 §2 第二階段或後續週次):
- 後台建立/編輯商品、活動(第二階段)
- 手動觸發庫存對帳的 API/UI(第二階段;自動對帳排程 Week 2 已完成)
- Prometheus/Grafana(第二階段)
- SMS/站內通知(第二階段;`NotificationChannel` enum 已預留但只實作 EMAIL)
- Micrometer Tracing、結構化 JSON 日誌、GitHub Actions(Week 5)
- 真正的分散式 trace(本輪用一個輕量 request-scoped ID 頂著用,見 §3.3)

## 2. 現狀盤點

### 2.1 Notification 模組現狀與缺口

`notification` 模組(Week 1 建的)目前是:`UserRegisteredNotificationListener` 用 Spring 的 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` 監聽站內事件(**不是**主規格 §7/§12 講的 outbox + RabbitMQ consumer,是同進程的 event listener),`EmailNotificationSender` 寄信失敗時只呼叫 `markFailed()` 存一次錯誤訊息,`attemptCount` 有累加但**沒有任何機制會再次呼叫它**——沒有重試排程、沒有 dead-letter、後台也還不能重新排程。

這是本輪要補的缺口,但**不打算把寄信流程整個改成 outbox+RabbitMQ**(那是 Week 2 為了「不能漏單、不能重複建單」這種正確性要求才上的重量級機制)。信件是 at-least-once、偶爾重複寄送不影響正確性,補一個 Postgres 排程掃描 + 指數退避即可,做法跟 Week 2 的 `PaymentTimeoutScheduler`/`InventoryReconciliationScheduler` 完全同一套路(主規格 §17 也明講「付款逾時以資料庫排程掃描偵測,而非 RabbitMQ 延遲訊息／插件」,這裡援引同樣的取捨)。詳見 §5。

`NotificationDelivery` 實體目前沒有 mapping `created_at`/`updated_at`——這兩欄位在 `V1__baseline_schema.sql` 就已經存在(DB 有預設值,只是 JPA entity 沒宣告),補上這兩個欄位的 mapping 就夠支撐退避排程的時間判斷,**不需要新的 Flyway migration**。

### 2.2 已建好但完全沒人用的資料表

`V1__baseline_schema.sql`(Week 1)已經建了 `api_audit_logs` 和 `order_status_history` 兩張表,但目前沒有任何程式碼寫入或讀取——沒有 filter 擷取 API 稽核紀錄,`Order` 的狀態轉換(`pay()`/`cancel()`/`markExpired()`)也沒有記錄歷程。本輪要把這兩張表接上。

### 2.3 已就緒的部分

- `Role.ADMIN` enum 和 JWT `role` claim → `ROLE_ADMIN` 的映射已經在 Week 1 做好。
- `SecurityConfig` 已經有 `.requestMatchers("/api/admin/**").hasRole("ADMIN")`——後台 API 的授權邊界從 Week 1 就設好了,本輪只要在這個既有邊界底下加 controller。
- 前端目前完全沒有 `admin` 模組、沒有 `/admin` 路由、`useAuth()` 的 context 沒有帶 `role`(只有 `isAuthenticated`)——這些都是本輪新增。
- `frontend/package.json` 目前沒有 Recharts、沒有任何 table 套件——主規格 §3 技術棧列了這兩個,但 Week 1-3 都沒用到,本輪是第一次真的裝進去(見 §10)。

## 3. API Audit

### 3.1 `ApiAuditFilter`

新增 `common/web/ApiAuditFilter`(`OncePerRequestFilter`,對應主規格 §5 package 圖裡本來就留的 `common/web`),註冊在 Spring Security filter chain 之後(這樣才讀得到 `SecurityContextHolder` 裡驗證過的 JWT)。`doFilterInternal` 流程:

1. 記錄開始時間、包一層讓 `filterChain.doFilter()` 照常執行(不吃例外,例外照樣往上拋,稽核記錄不能改變原本的錯誤處理行為)。
2. 執行完後讀取:method、`request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)`(path template,例如 `/api/orders/{orderId}`,不是帶實際 ID 的原始路徑,避免高基數)、`response.getStatus()`、耗時、client IP、User-Agent、`SecurityContextHolder` 裡的 `userId` claim(未登入請求則為 null)、trace ID(見 §3.3)、錯誤碼(若回應是 `ProblemDetail`,從 `response` 沒辦法直接讀已寫出的 body,改成讓 `GlobalExceptionHandler` 把 `code` 放進一個 request attribute,這裡讀那個 attribute,取不到則 null)。
3. 呼叫 `ApiAuditWriter`(`@Async`,比照 `notification` 模組已經在用的 `@Async` 模式)非同步寫入,filter 本身不等待寫入完成就返回。
4. `ApiAuditWriter` 寫入失敗時只記結構化系統日誌,不拋例外(主規格 §11 明講「失敗時記錄結構化系統日誌」)。

**絕對不擷取**:Authorization header、密碼、JWT 內容、Gmail App Password、付款欄位、任何 request/response body——這點 filter 設計上就不去讀 body,不是事後遮罩,從源頭排除。

### 3.2 保留期限

新增 `ApiAuditRetentionScheduler`(`@Scheduled`,每天跑一次)刪除 `occurred_at` 早於設定天數(`app.audit.retention-days`,預設 30)的紀錄——同樣是既有排程模式的延伸,不做分區表或封存機制,一個刪除語句足夠。

### 3.3 Trace ID(本輪的權宜設計,Week 5 會換掉)

主規格把 Micrometer Tracing 排在 Week 5,但 `api_audit_logs`/`order_status_history` 現在就需要一個關聯欄位讓後台「訂單詳情頁」能連結「這筆訂單相關的 API 呼叫紀錄」。本輪用一個輕量作法頂著:`TraceIdFilter`(`common/web`,排在 `ApiAuditFilter` 之前)每個請求產生一個 UUID(若請求已帶 `X-Trace-Id` header 則沿用,方便未來串接真正的分散式追蹤時無痛接軌),放進 MDC 與一個 request attribute,回應帶回同一個 `X-Trace-Id` header。`api_audit_logs.trace_id` 存這個值;`request_id` 欄位本輪就直接存同一個值(等 Week 5 有真正的 span/trace 概念後,`request_id` 才需要跟 `trace_id` 分開)。

## 4. 訂單狀態歷程

新增 `order/application/OrderStatusHistoryRepository`(`record(Long orderId, OrderStatus from, OrderStatus to)`)與對應的 `OrderStatusHistory` JPA entity/adapter,只插入不更新。呼叫點(訂單狀態轉換目前只發生在這三處,逐一補上,不做 AOP/攔截器這種會讓狀態轉換變隱式的東西):

1. `OrderPurchaseConsumer.handle()` 建立訂單後:`record(order.getId(), null, PENDING_PAYMENT)`。
2. `SubmitPaymentService.submit()` 付款成功分支(`order.pay()` 之後):`record(order.getId(), PENDING_PAYMENT, PAID)`。
3. `OrderCompensationService.compensate()`(`cancel`/`markExpired`/`failPayment` 三者共用的私有方法,Week 2 設計):在 `orderRepository.save(order)` 之後補一行,`from` 固定是 `PENDING_PAYMENT`(這三個路徑呼叫前都已經在 domain method 裡用 `requirePendingPayment` 檢查過,不可能是別的起始狀態),`to` 讀 `order.getStatus()`(`CANCELLED` 或 `EXPIRED`)。

後台訂單詳情頁(§7.3)顯示這個歷程列表,`changed_at` 由資料庫預設值(`now()`)產生,不需要應用層傳時間。

## 5. 通知重試補強

### 5.1 `NotificationDelivery` 補 timestamp mapping

補 `createdAt`/`updatedAt` 兩個既有欄位的 JPA mapping(§2.1 已說明欄位已存在)。`markSent()`/`markFailed()` 順手把 `updatedAt` 更新成 `Instant.now()`——這是退避排程判斷「距離上次嘗試過了多久」的依據。

### 5.2 `NotificationRetryScheduler`

新增排程(`@Scheduled(fixedDelay = 60000)`,同樣是 Postgres 掃描模式),邏輯:

```text
候選 = 查 status = FAILED AND attempt_count < MAX_ATTEMPTS(3) 的所有 notification_deliveries
對每一筆候選:
  backoff = 指數退避(attemptCount) -- 例:1 次失敗等 1 分鐘、2 次等 5 分鐘
  若 now - updatedAt >= backoff:
    重新嘗試寄送(沿用同一筆 row,不新增 row;成功則 markSent,失敗則 markFailed 讓 attemptCount 再 +1)
```

`attemptCount >= MAX_ATTEMPTS` 之後這筆紀錄永遠停在 `FAILED`,不再被排程掃到——這筆 Postgres row 本身就是主規格講的「dead-letter」,不需要另外接 RabbitMQ DLQ(§2.1 的取捨理由)。後台可以查到它、可以手動重試(§6.4 的 `POST /api/admin/notifications/{id}/retry`,不受 `attemptCount` 上限限制,管理者主動重試是明確意圖,不用再走退避判斷)。

重試需要的「用既有 row 再寄一次,不要建新 row」這個行為,跟現在 `EmailNotificationSender.send()`(專門處理全新一筆 `pendingEmail(...)`,一開始就 `save()` 插入)不是同一件事——實作時把「寄信 + 更新狀態」這段邏輯抽出來給 send 跟 retry 共用,新建的第一次寄送跟排程/手動重試都呼叫這同一段邏輯,只差在前者先插入一筆新 row、後者操作既有 row。細節留給 plan 文件。

## 6. Admin 後端 API

延續主規格 §9 的 Admin API 清單,底下寫每個端點的資料來源與備註。全部掛在既有 `/api/admin/**` → `hasRole("ADMIN")` 的安全邊界底下,新增一個 `admin` 模組(`domain` 幾乎用不到,大部分是跨模組唯讀查詢,`application` 直接组合既有 repository 或新開 read-only 查詢方法,不建不必要的 domain 物件)。

| 端點 | 資料來源 |
|---|---|
| `GET /api/admin/dashboard/summary` | `purchase_requests`(依 status 分組計數、成功率)、`orders`(依 status 分組計數、`PAID` 訂單總額)、`inventory`(各活動可用/已售庫存)——皆為單次聚合查詢,不快取(流量小,portfolio 展示用途) |
| `GET /api/admin/dashboard/trends` | `purchase_requests`/`orders` 依 `created_at` 做時間分桶(最近 1 小時每分鐘一桶、最近 24 小時每小時一桶) |
| `GET /api/admin/api-logs` | `api_audit_logs`,依 `occurred_at`/`path_template`/`status`/`user_id`/`trace_id` 篩選,分頁(`Pageable`) |
| `GET /api/admin/orders` | `orders`(admin 全域,不像 `/api/orders/me` 限定使用者),分頁,可依 status 篩選 |
| `GET /api/admin/orders/{id}` | `orders` + `order_items` + 對應 `purchase_requests`(依 `orderId` 反查)+ `order_status_history`(§4)+ 該訂單相關的 `api_audit_logs`(依 `trace_id` 關聯,§3.3——同一次 HTTP 請求造成的狀態變更,trace_id 相同) |
| `GET /api/admin/notifications` | `notification_deliveries`,依已讀/未讀、channel、status 篩選,分頁 |
| `GET /api/admin/notifications/{id}` | `notification_deliveries` 單筆 |
| `PATCH /api/admin/notifications/read-status` | 主規格原文:「接受一組通知 ID 進行批次已讀／未讀切換」,body:`{ ids: number[], read: boolean }` |
| `POST /api/admin/notifications/{id}/retry` | §5.2,忽略 `attemptCount` 上限的手動重試 |
| `GET /api/admin/notifications/unread-count` | 主規格清單沒有這條,是本輪新增——後台導覽列的小鈴鐺需要一個輕量端點只回未讀數(`{ count: number }`),不應該為了顯示一個數字去打完整通知列表 API。理由記在這裡供之後對照主規格時知道差異來源。 |

## 7. Admin 前端

### 7.1 路由與守衛

`useAuth()` 的 `AuthContext` 加一個 `role: 'USER' | 'ADMIN' | null` 欄位——從 access token(JWT)payload 解出來(瀏覽器內建 `atob()` 解 base64,不驗簽,只用來決定要不要顯示/導頁,不是安全邊界;真正的授權由後端 `hasRole("ADMIN")` 把關,前端解讀角色純粹是 UX)。`login()`/`markAuthenticated()`(refresh 成功時)都要順便解出 role 存進 context。

新增 `RequireAdmin`(比照 `RequireAuth` 的寫法,多一層 `role !== 'ADMIN'` 就導回 `/`,不是導去 `/login`——因為能走到這裡代表已經登入,只是權限不夠,導去登入頁沒意義)。`/admin` 底下所有路由包在 `RequireAdmin` 而不是 `RequireAuth`(`RequireAdmin` 內部隱含已登入檢查,不用兩層包兩次)。

```text
/admin                       AdminDashboardPage
/admin/api-logs              ApiLogsPage
/admin/orders                AdminOrdersPage
/admin/orders/:orderId       AdminOrderDetailPage
/admin/notifications         AdminNotificationsPage
/admin/notifications/:id     AdminNotificationDetailPage
```

### 7.2 儀表板與趨勢圖

`AdminDashboardPage`:摘要卡片(搶購數、成功率、各訂單狀態計數、銷售額、庫存摘要)+ 兩張趨勢圖(近 1 小時、近 24 小時)。圖表用 Recharts(主規格 §3 定案的套件,本輪第一次真的裝進 `package.json`)——折線圖或柱狀圖,不用做互動式縮放/多維度篩選這種份外的圖表功能。

### 7.3 API 紀錄 / 訂單查詢

`ApiLogsPage`:表格 + 篩選表單(時間區間、path、status、userId、traceId)。表格用 TanStack Table(主規格 §3 定案,本輪第一次裝)處理排序/分頁,不用它的進階功能(欄位拖拉、虛擬捲動)。

`AdminOrdersPage`:訂單列表表格(可依狀態篩選),點列進 `AdminOrderDetailPage`——顯示訂單明細、對應 purchase request、§4 的狀態歷程時間軸、以及依 trace_id 關聯到的 API 紀錄(§6 表格最後一列)。

### 7.4 通知中心

- 全站導覽列(不只 `/admin` 底下,任何已登入 ADMIN 都看得到)有一顆小鈴鐺,顯示 `GET /api/admin/notifications/unread-count` 的數字,`refetchInterval` 用 TanStack Query 定時刷新(例如 30 秒——不需要 WebSocket,主規格全文沒要求即時推送,Week 3 design spec §10 已經定過這個調)。
- `AdminNotificationsPage`:清單,可依使用者、管道、狀態、已讀/未讀篩選;checkbox 多選 + 批次已讀/未讀按鈕(呼叫 `PATCH .../read-status`)。
- `AdminNotificationDetailPage`:單筆詳情,`status = FAILED` 時顯示「重新排程」按鈕(呼叫 `POST .../retry`)。

## 8. ArchUnit

新增 `backend/src/test/java/com/flashsale/ArchitectureTest.java`,落實主規格 §5 已經講清楚、但從來沒有測試強制過的規則:

- 每個模組(`identity`/`catalog`/`flashsale`/`inventory`/`order`/`payment`/`notification`/`admin`)的 `domain` package 不得依賴同模組的 `adapter` package,也不得依賴任何其他模組的任何 package(`common` 除外)。
- 任一模組的 `adapter` package 不得被其他模組直接依賴(模組之間只能透過 `application` 的 interface 溝通,這條規則本輪加了 `admin` 模組後特別重要——`admin` 大量讀取其他模組的資料,必須確認它是透過既有 `application`/repository interface,不是直接碰別的模組的 JPA entity 或 adapter)。
- `domain` package 不得依賴 Spring Framework(驗證 domain 是純 Java,現有程式碼目前應該就是這樣,寫這條測試是把既有事實鎖住,防止之後不小心破壞)。

不做的:不寫「每個 Service 都要有對應測試」這種覆蓋率式規則,ArchUnit 只驗證架構邊界,不是測試覆蓋率工具。

## 9. k6

新增 `load-tests/purchase-flow.js`。腳本內容:多個虛擬使用者(VU)同時對同一個庫存有限的 flash sale 送出搶購請求,用 k6 的 `check()` 驗證主規格 §14 講的核心不變量,而不是設定 RPS/延遲門檻(主規格 §15 原文:「先不設定量化目標,實測後再記錄於 README」):

- 對每個 VU:註冊/登入 → 搶購 → 輪詢 `GET /api/purchase-requests/{id}` 直到終局狀態。
- 測試結束後對 API 查詢(或直接查 Postgres,看哪個在 k6 腳本裡方便)驗證:成功筆數 = 初始庫存量、`SOLD_OUT` 筆數 = 其餘 VU 數、`orders` 表筆數等於成功筆數(不超賣、不重複建單)。

不做的:不建 CI 自動跑 k6(那是 Week 5 GitHub Actions 的範圍,而且 k6 需要一個活著的 stack,跟一般 CI unit/integration test 的執行模型不同,是否要在 CI 跑本身就是 Week 5 要決定的問題,這裡不越界先做決定)。

## 10. 新增依賴

`frontend/package.json` 新增:

- `recharts`——儀表板趨勢圖(§7.2)。
- `@tanstack/react-table`——API 紀錄/訂單查詢表格(§7.3)。

兩者都是主規格 §3 技術棧原本就定案、只是 Week 1-3 沒用到的套件,不是本輪臨時新增的決定。後端不需要新的 Gradle 依賴——ArchUnit 通常隨 Spring Boot test starter 或需要單獨加(視目前 `build.gradle.kts` 有沒有,實作時確認;k6 是獨立執行檔,不是專案依賴)。

## 11. 測試策略

沿用既有慣例(Testcontainers Postgres/Redis/RabbitMQ IT、Mockito 單元測試、vitest+Testing Library 前端測試)。新增涵蓋範圍:

- `ApiAuditFilter`:IT 驗證一個請求打完之後 `api_audit_logs` 有對應紀錄,且欄位正確(尤其是 path template 不是原始路徑、沒有洩漏 Authorization header 相關欄位)。
- `OrderStatusHistoryRepository`:三個呼叫點各自的既有 IT(`OrderPurchaseConsumerIT`/`PaymentControllerIT`/`OrderCancelIT`/`PaymentTimeoutSchedulerIT`)追加一個歷程紀錄的斷言,不另開新測試檔案重複跑一次相同的業務流程。
- `NotificationRetryScheduler`:IT 驗證 FAILED 且退避時間已到的紀錄會被重新嘗試,未到退避時間或已達上限的不會。
- Admin API:每個端點至少一個 MockMvc/IT 測試,包含「非 ADMIN 角色打會 403」這個既有安全邊界的迴歸測試(現在才第一次真的有 controller 掛在這個邊界底下,值得驗證一次)。
- `ArchitectureTest`:見 §8,本身就是測試。
- Admin 前端:比照 Week 3 慣例(`vi.spyOn` API 模組、`MemoryRouter`),涵蓋 `RequireAdmin` 導頁邏輯、儀表板渲染、通知批次已讀操作。

## 12. 刻意不做的事

- **不做完整的 RBAC 權限系統**。只有 `USER`/`ADMIN` 兩種角色,`/api/admin/**` 整段用 `hasRole("ADMIN")` 一刀切,不做「哪個 admin 能看哪些子功能」這種細粒度權限——履歷作品集用不到,加了也沒地方展示。
- **不做通知中心的即時推送(WebSocket/SSE)**。小鈴鐺數字用輪詢,理由同 Week 3 design spec §10。
- **不做 API 稽核紀錄的全文檢索**。篩選條件就主規格列的那幾個欄位(時間、path、status、userId、traceId),不接 Elasticsearch 之類的全文索引引擎。
- **不做 order_status_history 之外的通用 audit trail 框架**。只記訂單狀態轉換這一種歷程,不做「所有 entity 的所有欄位變更都自動記錄」這種通用機制——沒有其他 entity 現在需要歷程,先不要為了假設的未來需求建框架。
- **k6 不接 CI**,原因見 §9。
