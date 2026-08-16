# FlashSale 工程取捨

這份文件記錄 FlashSale 目前實作背後的主要選擇:為什麼這樣做、付出什麼代價、以及在什麼條件下
這個選擇會失效。每一節都對應到現在 `main` 上跑得起來的程式碼,沒有「未來會做」的承諾;
已知的限制直接寫在各節的「代價」與最後的[已知限制](#已知限制)。

搭配閱讀:[架構深入說明](./architecture.md)、[API 操作範例](./api-examples.md)。

## Redis Lua 預扣庫存

**決策**:搶購瞬間的庫存扣減放在 Redis,用一支 Lua 腳本在單次呼叫內完成 `GET` 與 `DECRBY`;
PostgreSQL 的 `inventory` 資料表仍然是庫存的權威來源。

**理由**:限量搶購的本質是「極短時間內大量請求打同一列資料」。若直接對 Postgres 同一列
加鎖,連線池會被排隊的交易吃光,而且行鎖競爭會把延遲推高。Lua 腳本在 Redis 端單執行緒執行,
天然是原子的,不需要 `WATCH`/`MULTI` 的重試迴圈,也不需要在應用層做分散式鎖。

**代價**:

- 這是雙寫,Redis 與 Postgres 可能漂移。系統用兩道防線收斂:`InventoryReconciliationScheduler`
  每 60 秒以 Postgres 為準覆寫 Redis;`OrderPurchaseConsumer` 建單前會 `SELECT ... FOR UPDATE`
  鎖住庫存列再驗證一次,真的不足就丟例外走重試與 DLQ,不會超賣。
- Redis key 不存在時(冷啟動、被清掉)要先從 Postgres 灌回去再重試,這個回填有極短的競態視窗,
  目前用 `SETNX` 降低影響,連續失敗則回 `503 STOCK_GATEWAY_UNAVAILABLE`。
- Compose 裡的 Redis 沒有掛 volume,也沒有特別調整持久化設定;容器重建後庫存計數會由
  「key 不存在就從 Postgres 回填」與對帳排程補回,期間可能短暫偏保守或偏樂觀。

**邊界**:這套做法假設「單一 Redis 實例 + 單一 backend 實例」。要水平擴充 backend 不需要改
預扣邏輯,但 Redis 必須是共用實例,而且排程類工作需要額外的分散式鎖(目前沒有)。

## Transactional Outbox

**決策**:`purchase_requests` 與 `outbox_events` 在同一個資料庫交易寫入,再由
`OutboxPublisher` 以 `@Scheduled(fixedDelay = 500)` 撈出未發佈事件送進 RabbitMQ。

**理由**:如果在交易內直接呼叫 RabbitMQ,就會出現「訊息送出但交易回滾」或「交易提交但訊息遺失」
的經典雙寫問題。outbox 把「決定要做」與「實際送出」拆開,兩者都只依賴一個可回滾的資料庫交易。

**代價**:

- 多一張表、一個輪詢器,以及最多約 0.5 秒的發佈延遲(`fixedDelay` 值)。
- 語意是 at-least-once,消費端必須自己去重——這就是 `consumed_messages` 與
  `ConsumedMessageGuard` 存在的理由。
- 輪詢器用 `FOR UPDATE` 撈批次,所以撈取與標記已發佈刻意拆成兩個交易,避免自己鎖住自己;
  這段順序在 `OutboxPublisher` 內有註解說明,改動時要特別小心。

**替代方案**:CDC(例如 Debezium)可以省掉輪詢延遲,但會多一個必須維運的元件,與「整包
Docker Compose 一鍵跑起來」的交付目標衝突,因此沒有採用。

## 補償機制而非分散式交易

**決策**:跨 Redis / PostgreSQL / RabbitMQ 的一致性靠「本地交易 + 事件 + 補償」達成,
不使用 2PC,也沒有引入 Saga 框架。取消、付款失敗、付款逾時三條路徑共用
`OrderCompensationService`:先在 Postgres 釋放庫存並寫入 `order_status_history`,
再送一筆 `STOCK_RELEASE_REQUESTED` 事件,由 `StockReleaseConsumer` 把 Redis 計數器加回去。

**理由**:三個異質儲存體沒有共同的交易協調者;引入協調者的複雜度與運維成本遠高於本專案
規模能得到的好處。補償路徑數量有限(三條),而且都能用整合測試覆蓋。

**代價**:

- 補償是最終一致的:訂單狀態立刻變更,但 Redis 的可售數量要等事件被消費後才回補,
  中間會有短暫的「顯示偏少」視窗。
- 補償事件如果連續失敗會進入 `stock.release.queue.dlq`,目前沒有自動重放工具,
  需要人工處理(對帳排程仍會把 Redis 拉回 Postgres 的值,所以不會永久錯誤)。
- 付款失敗與使用者主動取消共用 `CANCELLED` 終態,兩者的差異只保留在 `payment_records`。

## 付款逾時掃描

**決策**:未付款訂單的逾時處理用 `PaymentTimeoutScheduler`,每 30 秒查詢一次超過
`payment_due_at`(建單後 15 分鐘)的 `PENDING_PAYMENT` 訂單並標記 `EXPIRED`。

**理由**:比起延遲佇列或 Redis key TTL 事件,掃描表的做法可觀測、可重跑、可測試:狀態就寫在
資料庫裡,重啟不會遺失待處理的逾時,也不依賴任何一種訊息中介的延遲投遞外掛。

**代價**:

- 精度受限於掃描週期,實際過期時間最多比 `payment_due_at` 晚 30 秒。
- 掃描是全表條件查詢,訂單量非常大時需要額外的索引與批次上限,目前沒有做分批。
- 排程假設只有一個 backend 實例。多實例部署時會重複掃描同一批訂單;雖然狀態機的
  `requirePendingPayment` 會擋掉重複轉移,但仍應該加上分散式鎖或改用單一 leader。

## 關閉 OSIV

**決策**:[`application.yml`](../../backend/src/main/resources/application.yml) 明確設定
`spring.jpa.open-in-view: false`。

**理由**:OSIV 預設會把 Hibernate session 開到 view 渲染結束,讓資料庫連線的持有時間跟著
HTTP 回應時間走。在搶購這種尖峰場景,這等於把連線池的壽命綁在最慢的序列化上,而且會讓
lazy loading 在 controller 之外悄悄觸發額外查詢——問題會在壓測時才炸開,而不是在測試時。

**代價**:必須在 application 層就把需要的資料抓齊。實作上的具體對應是 `OrderDtoMapper`
在交易內把 `Order` 及其 `items` 映射成 record DTO 回傳,而不是把 entity 丟給序列化器;
新增欄位時如果忘了在交易內取用,會直接得到 `LazyInitializationException` 而不是靜默的 N+1。
這個「早點壞掉」正是選擇它的原因。

## Actuator 暴露範圍

**決策**:Actuator 只暴露 `health`、`info`、`metrics` 三個端點群。`/actuator/health` 與
`/actuator/health/liveness`、`/actuator/health/readiness` 為公開;`/actuator/metrics` 與
`/actuator/metrics/{name}` 需要 `ROLE_ADMIN`;health 明細 `show-details: when-authorized`
同樣限定 `ADMIN` 角色。Nginx 只反向代理上述端點,其餘 `/actuator/` 路徑直接回 404。

**理由**:容器編排需要探針,面試展示需要看得到自訂搶購指標,但 `env`、`configprops`、
`heapdump`、`threaddump` 這類端點會洩漏設定與記憶體內容,不應該出現在唯一對外的入口後面。
授權與路由兩層都設限,是為了讓「應用程式設定被改壞」時 Nginx 仍然是一道防線。

**代價**:真的需要那些除錯端點時,得進容器內部操作(例如
`docker compose exec backend wget -qO- http://localhost:8080/actuator/health`),
本機除錯多一個步驟。另外,把管理端點放在與業務 API 同一個埠上是刻意的簡化,
正式環境通常會改用獨立的 management port 並只綁內網。

## 本機自簽 TLS

**決策**:Nginx 用 `nginx/certs/generate-cert.sh` 產生的自簽憑證在 `8443` 終止 TLS,
憑證與私鑰都被 gitignore,不進版控;所有 host 流量只走這個埠。

**理由**:讓 demo 的行為貼近真實(HTTPS、`Secure` Cookie、安全標頭如 CSP、
`X-Frame-Options`、`X-Content-Type-Options`、`Referrer-Policy` 都真的生效),同時不需要任何
外部憑證頒發流程就能重現。

**代價**:瀏覽器會顯示警告,`curl` 必須加 `-k`。這只是本機開發用的 TLS 終止示範,
不包含正式憑證鏈、自動續期、HSTS 或 OCSP stapling,也不宣稱等同於正式環境的 TLS 設定。

## 只交付 Docker Compose

**決策**:整個專案以 [`compose.yaml`](../../compose.yaml) 的 8 個服務交付,不提供 Kubernetes
manifest,也沒有公開部署環境。

**理由**:作品集的重點是「任何人 clone 之後能在本機重現同一套系統」。Compose 讓依賴版本與
健康檢查全部寫在版控裡,backend 更以 `depends_on: condition: service_healthy` 等待 Postgres、
Redis、RabbitMQ、Mailpit 真的就緒才啟動,審閱者不需要雲端帳號就能重現。

**代價**:

- 沒有水平擴充、滾動更新與自動修復;所有「多實例才會遇到」的問題(排程重複執行、
  節點層級限流)在這個交付形式下無法驗證。
- 沒有 Prometheus / Grafana 這類集中式指標與告警,指標只能透過 `/actuator/metrics/{name}` 逐一查詢。
- 本機量到的數字反映的是這台機器與這組 Docker 資源設定下的行為,不是任何形式的
  production 容量或 SLA 承諾。

## 已知限制

- 排程工作(outbox 發佈、付款逾時、庫存對帳、通知重試、稽核清理)都假設單一 backend 實例,
  沒有分散式鎖。
- DLQ 沒有自動重放工具,進到死信佇列的訊息需要人工處理。
- 稽核紀錄保留 30 天後由排程刪除,沒有冷儲存或匯出機制。
- 付款是模擬的:由請求指定 `SUCCESS` / `FAILURE`,沒有串接任何金流服務。
- 通知只有 email 一種通道,本機由 Mailpit 攔截,不會真的寄出。
