# FlashSale 限量商品搶購系統設計規格

## 1. 目標

建立一個可放入後端工程師履歷作品集的限量商品搶購系統。系統需透過可操作的 React 網頁呈現完整流程，並展示 Spring Boot、交易一致性、高併發削峰、非同步訊息、冪等、測試、自動化部署與可觀測性。

預計以每週 5–10 小時、3–4 週完成第一個可公開展示的版本。專案優先採用成熟套件，不自行實作 JWT、API 文件、郵件傳輸、圖表或其他已有標準解法的基礎功能。

## 2. 範圍

### MVP

- 使用者註冊、登入與 JWT 驗證
- 註冊成功 Email 通知
- 商品與搶購活動瀏覽
- 限量商品搶購及非同步結果查詢
- 訂單查詢、模擬付款、取消與付款逾時
- Redis 原子預扣庫存與 RabbitMQ 排隊削峰
- 冪等、防止超賣、訊息去重及失敗補償
- 管理後台儀表板、API 紀錄與訂單查詢
- Swagger UI、統一錯誤格式、測試、CI 與 Docker Compose

### 第二階段

- 後台建立與編輯商品、搶購活動
- 庫存對帳與手動觸發對帳
- Prometheus 與 Grafana 儀表板
- SMS、站內通知等其他通知管道

### 明確不包含

- 購物車、物流、優惠券與真實金流串接
- 多商品訂單
- 完整電商 CMS
- 為展示技術而提前拆分微服務

第一版限制每位使用者在每場活動購買一件商品。

## 3. 技術棧

### Backend

- Java 21、Spring Boot 3
- Spring Web MVC、Spring Data JPA、PostgreSQL
- Spring Security、OAuth2 Resource Server、`JwtEncoder`
- Spring Data Redis、Redis Lua Script
- Spring AMQP、RabbitMQ
- Spring Boot Mail Starter、Thymeleaf Email Template
- Flyway、Spring Boot Actuator、Micrometer、Micrometer Tracing
- springdoc-openapi Swagger UI
- Spring `ProblemDetail`（RFC 9457）
- Logback JSON encoder

### Frontend

- React、TypeScript、Vite
- React Router、TanStack Query
- React Hook Form、Zod
- TanStack Table、Recharts

### Testing and Delivery

- JUnit 5、AssertJ、Mockito
- Spring Boot Test、MockMvc、Testcontainers
- ArchUnit、k6
- Docker、Docker Compose、GitHub Actions

## 4. Repository 結構

```text
flash-sale/
├─ backend/
│  ├─ src/
│  ├─ pom.xml
│  └─ Dockerfile
├─ frontend/
│  ├─ src/
│  ├─ package.json
│  └─ Dockerfile
├─ load-tests/
├─ docs/
├─ compose.yaml
├─ .env.example
└─ README.md
```

`compose.yaml` 啟動 frontend、backend、PostgreSQL、Redis、RabbitMQ；本機開發另提供 Mailpit 攔截測試郵件。正式 Gmail SMTP 密碼只從環境變數或部署平台 secret 注入，不寫入設定檔或 Git。

## 5. 架構與模組邊界

第一版採模組化單體，保留未來拆分服務的清楚邊界：

- Identity：使用者、角色、登入與 JWT
- Catalog：商品資料
- Flash Sale：活動規則、活動時間與購買資格
- Inventory：庫存預留、確認、釋放與對帳
- Order：訂單建立及狀態流轉
- Payment：模擬付款與付款結果
- Notification：通知排程、管道選擇與發送紀錄
- Admin：統計、API audit log 與營運查詢

每個模組區分 domain、application、adapter/infrastructure。Controller 只處理 HTTP 輸入輸出；application service 編排 use case；domain 保存業務規則；adapter 負責 PostgreSQL、Redis、RabbitMQ、SMTP 與 JWT。

只在有真實替換或測試邊界時建立 interface，例如 repository、message publisher、inventory reservation 與 notification sender。ArchUnit 負責驗證 domain 不依賴 infrastructure 等架構規則，以具體方式落實 SOLID，避免為每個類別建立沒有價值的抽象。

## 6. 核心資料模型

- `users`：帳號、密碼雜湊、Email、角色與狀態
- `products`：商品名稱、描述與基本資料
- `flash_sales`：商品、活動價格、開始／結束時間、限購數與狀態
- `inventory`：總庫存、可用量、保留量、已售量與版本號
- `purchase_requests`：request ID、idempotency key、使用者、活動與處理結果
- `orders`：訂單編號、使用者、總額、狀態與付款期限
- `order_items`：商品、數量與成交價
- `payment_records`：付款請求、結果與模擬交易編號
- `outbox_events`：待發布的 domain/integration event
- `consumed_messages`：consumer 去重紀錄
- `notification_deliveries`：管道、模板、收件人、狀態、嘗試次數與錯誤
- `api_audit_logs`：API metadata，不保存敏感 request body
- `order_status_history`：訂單狀態變更軌跡

訂單狀態流轉：

```text
PENDING_PAYMENT → PAID
       │
       ├─→ CANCELLED
       └─→ EXPIRED
```

## 7. 搶購資料流與一致性

1. 前端送出搶購請求及 `Idempotency-Key`。
2. 後端以伺服器時間驗證 JWT、活動期間、使用者資格與重複請求。
3. Redis Lua Script 原子檢查並預扣庫存。
4. 接受請求後回傳 `202 Accepted` 與 `requestId`，再透過 RabbitMQ 排隊。
5. Consumer 去重後，在 PostgreSQL transaction 中建立訂單並寫入 outbox event。
6. 前端每秒輪詢 purchase request，直到 `SUCCEEDED`、`SOLD_OUT`、`REJECTED` 或 `FAILED`。
7. 付款成功確認銷售；取消或付款逾時則發布補償事件並回補庫存。
8. PostgreSQL 是最終資料來源；Redis 是高併發入口的暫時狀態，系統提供定期對帳。

Producer 使用 transactional outbox 避免資料已提交但訊息未發布。Consumer 使用唯一鍵及 `consumed_messages` 保證重送不會重複建單。重試必須有上限與退避；超過上限的訊息進入 dead-letter queue，供後台查詢與人工處理。

## 8. Authentication and Authorization

- 使用 Spring Security 驗證帳密及授權。
- 密碼使用 `PasswordEncoder`，不自行實作雜湊。
- 登入後以 Spring Security `JwtEncoder` 簽發 access token。
- API 使用 OAuth2 Resource Server 驗證 JWT。
- 角色分為 `USER` 與 `ADMIN`。
- 後台路由及 API 同時做前端導頁保護與後端 method/request authorization；前端限制不視為安全邊界。
- JWT signing key、Gmail App Password 等秘密只透過環境變數或 secret 注入。

MVP 使用短效 access token；refresh token rotation 不列入首版，避免擴張認證範圍。

## 9. API 與錯誤格式

### User API

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/flash-sales
GET  /api/flash-sales/{id}
POST /api/flash-sales/{id}/purchase-requests
GET  /api/purchase-requests/{requestId}
GET  /api/orders/me
GET  /api/orders/{orderId}
POST /api/orders/{orderId}/payments
POST /api/orders/{orderId}/cancel
```

### Admin API

```text
GET  /api/admin/dashboard/summary
GET  /api/admin/dashboard/trends
GET  /api/admin/api-logs
GET  /api/admin/orders
GET  /api/admin/orders/{id}
```

第二階段增加活動管理與庫存對帳 API。

API 契約由 springdoc-openapi 產生並透過 Swagger UI 展示。錯誤採 Spring 原生 `ProblemDetail`，標準欄位包含 `type`、`title`、`status`、`detail`、`instance`，並以 properties 增加穩定的業務 `code` 和 `traceId`。

狀態碼原則：輸入錯誤 `400`、未登入 `401`、無權限 `403`、不存在 `404`、狀態或冪等衝突 `409`、依賴服務暫時不可用 `503`。

## 10. Frontend

使用者區包含：

- 註冊與登入
- 活動列表：商品、價格、庫存狀態與開賣倒數
- 活動詳情：活動狀態及搶購按鈕
- 排隊結果：處理中、成功、售罄、拒絕或失敗
- 我的訂單：訂單詳情、模擬付款及取消

管理區 `/admin` 包含：

- 儀表板：搶購數、成功率、各訂單狀態、銷售額與庫存摘要
- 趨勢圖：最近一小時及 24 小時的請求／訂單趨勢
- API 紀錄：依時間、path、status、user ID、trace ID 篩選
- 訂單查詢：訂單、purchase request、狀態歷程與 trace 關聯
- 通知查詢：依使用者、管道與狀態查詢，並可重新排程失敗通知

前端倒數只供顯示；活動有效性一律由後端判斷。前端使用 TanStack Query 管理 server state、輪詢與 cache，不自行建立資料快取框架。

## 11. API Audit 與統計

`OncePerRequestFilter` 擷取發生時間、method、path template、status、user ID、request ID、trace ID、執行時間、client IP、user agent 與錯誤碼。不得保存 Authorization header、密碼、JWT、Gmail App Password、付款敏感資料或未遮罩的完整 request body。

API audit 寫入不得阻塞或破壞主要業務請求；以事件或有界非同步方式持久化，失敗時記錄結構化系統日誌。資料需設定保留期限，避免無限制成長。

儀表板的業務統計從 PostgreSQL 聚合查詢；技術性 HTTP/JVM/connection pool 指標由 Micrometer 提供。MVP 不建立自製 metrics framework。

## 12. Notification

使用者註冊與 outbox event 寫入同一 PostgreSQL transaction。RabbitMQ consumer 接收 `UserRegistered` 事件後，依通知偏好交給 `NotificationSender`：

```text
NotificationSender
├─ EmailNotificationSender     MVP
├─ SmsNotificationSender       Future
└─ InAppNotificationSender     Future
```

MVP 以 Spring Mail 寄送 Thymeleaf HTML 註冊成功信。開發環境使用 Mailpit；demo 環境以 Gmail SMTP 寄送。SMTP 失敗不得回滾已完成的使用者註冊。發送採有限次數、指數退避重試，最後進入失敗狀態或 dead-letter queue；後台可重新排程。

環境變數至少包含：

```text
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
GMAIL_USERNAME=
GMAIL_APP_PASSWORD=
JWT_PRIVATE_KEY=
JWT_PUBLIC_KEY=
```

`.env.example` 僅列變數名稱與非敏感預設值。

## 13. 可觀測性與健康檢查

- Actuator 提供 health、liveness、readiness 與 metrics endpoint。
- Micrometer 記錄 HTTP、JVM、HikariCP 與自訂搶購指標。
- Micrometer Tracing 維持 HTTP、RabbitMQ 與背景工作的 trace correlation。
- Logback 輸出 JSON 結構化日誌。
- RabbitMQ Management UI 觀察 queue、consumer 與 dead-letter queue。
- Compose healthcheck 配合 `depends_on` 降低啟動順序問題。

Readiness 反映 PostgreSQL、Redis、RabbitMQ 等必要依賴；liveness 只反映應用程序是否需要重新啟動。

## 14. TDD 與測試策略

實作採 Red → Green → Refactor。每個 use case 先從可觀察行為撰寫失敗測試，再加入最小實作，最後重構。禁止為了追求覆蓋率而測試 framework 或 trivial getter。

- Domain unit tests：庫存規則、活動期間、限購及訂單狀態機
- Application tests：mock ports，驗證 use case 編排與補償行為
- Integration tests：Testcontainers 啟動 PostgreSQL、Redis、RabbitMQ
- API tests：MockMvc 驗證 JWT、授權、validation 與 Problem Details
- Architecture tests：ArchUnit 驗證模組依賴規則
- Frontend tests：核心畫面、表單與搶購狀態互動
- Load tests：k6 驗證吞吐量、延遲、不超賣及不重複建單

高併發測試的核心不變量：售出量不得超過初始庫存；同一使用者及活動最多一張有效訂單；相同 idempotency key 必須回到相同結果。

## 15. 交付節奏

### Week 1：同步 MVP

- Repository、Compose、前後端骨架
- JWT 註冊登入及註冊成功 Email
- 商品與活動查詢
- Flyway schema
- 同步訂單及庫存 transaction
- Swagger、ProblemDetail 與第一批 TDD tests

### Week 2：搶購核心

- Redis Lua 預扣
- RabbitMQ 非同步建單
- Idempotency、outbox、consumer 去重
- 付款逾時、補償、有限重試及 dead-letter queue

### Week 3：Web、後台與驗證

- 使用者操作頁面
- 後台 dashboard、API audit、訂單及通知查詢
- Testcontainers、ArchUnit 與 k6

### Week 4：作品集包裝

- Actuator、metrics、tracing 與結構化日誌
- GitHub Actions 及公開部署
- README、架構圖、API 範例與 demo 帳號
- 同步與非同步版本的效能比較
- 設計取捨、已知限制與後續改進

若時間不足，優先保留正確性、測試、可操作流程與 README；Prometheus/Grafana、活動管理及手動對帳延後。

## 16. 完成條件

- `docker compose up --build` 可啟動本機完整環境。
- 使用者可透過網頁註冊、登入、搶購、查詢結果及模擬付款。
- Gmail 收得到註冊成功信，寄信失敗不影響註冊交易。
- 管理者可查看簡易統計、API audit、訂單與通知結果。
- 併發測試證明不超賣、不重複建單。
- 核心規則、整合流程與架構邊界皆有自動化測試。
- Swagger UI 可查看及呼叫 API。
- GitHub Actions 能自動執行必要驗證。
- README 可讓面試官在數分鐘內理解架構、啟動方法、量測結果及設計取捨。

## 17. 關鍵取捨

- 模組化單體優先於微服務：在有限時間內完成端到端產品，同時保留清楚邊界。
- PostgreSQL 作為最終資料來源；Redis 提升入口吞吐量，但必須提供對帳與補償。
- 非同步郵件不參與註冊 transaction：優先保障核心業務可用性。
- API audit 只保存必要 metadata：兼顧除錯能力、效能、隱私與儲存成本。
- 套件優先但不濫用抽象：使用成熟安全、文件、郵件與監控元件，業務規則仍保持明確且可測試。
