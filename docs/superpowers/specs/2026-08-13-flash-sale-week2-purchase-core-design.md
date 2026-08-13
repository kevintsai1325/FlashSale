# FlashSale Week 2(搶購核心)設計規格

## 0. 關聯文件

本文件是 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`(以下稱「主規格」)§7、§15 Week 2 範圍的細部設計,取代 Week 1(`docs/superpowers/plans/2026-08-08-flash-sale-week1-mvp.md`)的同步悲觀鎖搶購流程。主規格§6 的資料表(`purchase_requests`、`outbox_events`、`consumed_messages`、`payment_records` 等)在 Week 1 已建立,本次不需新增 migration 欄位以外的結構性變更。

## 1. 範圍

**本輪只做後端**,API 契約(`POST /api/flash-sales/{id}/purchase-requests` 回 `202` + `requestId`、`GET /api/purchase-requests/{requestId}` 輪詢)維持 Week 1 已定案的樣子不變,只換內部實作。前端搶購輪詢頁、我的訂單頁、付款/取消 UI 留給 Week 3(主規格 §15)。

包含:
- Redis Lua 原子預扣庫存(含 lazy-load 初始化)
- RabbitMQ 非同步建單、outbox publisher、consumer 去重
- 模擬付款 API(`POST /api/orders/{orderId}/payments`)與取消 API(`POST /api/orders/{orderId}/cancel`)
- 付款逾時排程、失敗/取消/逾時的統一補償(釋放 Postgres 庫存 + Redis 庫存)
- Redis/Postgres 庫存基本自動對帳排程
- Nginx 對搶購端點限流

不包含(留給主規格 §2 第二階段或 Week 3+):
- 後台建立/編輯商品、活動
- 手動觸發對帳的 API/UI
- 前端頁面與 k6 壓測(k6 在 Week 4)

## 2. 整體資料流

```
POST /api/flash-sales/{id}/purchase-requests  (Header: Idempotency-Key)
  1. 冪等檢查:existing = findByUserIdAndFlashSaleIdAndIdempotencyKey → 若存在直接回傳(既有邏輯不變)
  2. 活動時間檢查:flashSale.isPurchasableAt(now)(既有邏輯不變)
  3. 重複購買檢查:existsSucceededForUserAndFlashSale → 是則存一筆 REJECTED 並回傳(既有邏輯不變)
  4. 建立並持久化 PurchaseRequest(status=PENDING),取得 requestId
  5. Redis 原子預扣(見 §3)
       不足庫存 → PurchaseRequest.status=SOLD_OUT,回傳
       扣成功   → 寫入 outbox_events(event_type=CreateOrderRequested,同一個 Postgres transaction 內)
  6. 回傳 202 Accepted + { requestId, status=PENDING }

(背景) OutboxPublisher 排程輪詢 outbox_events(published_at IS NULL)→ 發布到 RabbitMQ → 標記 published_at

(背景) OrderPurchaseConsumer 消費 CreateOrderRequested
  1. consumed_messages 去重(message_id = outbox_events.id)
  2. Postgres transaction:
     - 鎖 Inventory 列(沿用既有 findByFlashSaleIdForUpdate + sell()),防禦性複核庫存
       (正常情況不會不足,因為 Redis 已把關;若真的不足視為資料不一致 → 導向補償,見 §5)
     - 建立 Order + OrderItem
     - PurchaseRequest.status=SUCCEEDED,寫入 orderId
  3. 例外時交給 Spring AMQP 內建 retry;超過重試上限進 DLQ(見 §4)

GET /api/purchase-requests/{requestId} 輪詢回傳目前 status,SUCCEEDED 時帶 orderId(既有邏輯不變)。
```

Postgres 仍是最終真相;Redis 只在前門做速率閘門與初步庫存判斷,避免尖峰直接對 Postgres 行鎖造成鎖等待。

## 3. Redis 預扣設計

Key 命名:`stock:{flashSaleId}`,值為剩餘可售數量(整數字串),不設 TTL(活動期間需要持續存在,新鮮度交給 §7 的對帳排程維護)。

App 端流程(`InventoryStockGateway`):
1. `EXISTS stock:{flashSaleId}`
2. 若不存在:讀 Postgres `inventory.available_quantity`,以 `SET stock:{flashSaleId} <value> NX` 寫入(NX 避免並發 lazy-load 互相覆寫)
3. 執行 Lua script 做原子扣減:

```lua
-- KEYS[1] = stock key, ARGV[1] = quantity
local stock = redis.call('GET', KEYS[1])
if stock == false then
  return -2  -- key 消失(極端 race 或剛好被對帳排程清過),呼叫端回第 1 步重試一次
end
stock = tonumber(stock)
local qty = tonumber(ARGV[1])
if stock >= qty then
  redis.call('DECRBY', KEYS[1], qty)
  return stock - qty
end
return -1  -- 庫存不足
```

回傳 `-1` → `PurchaseRequest.SOLD_OUT`;回傳 `-2` → 重跑一次第 1–3 步,若仍是 `-2` 視為依賴服務異常,回 `503`。

`quantity` 沿用既有 `flashSale.getPurchaseLimitPerUser()`(單筆訂單購買數量,[[purchase-limit-spec-conflict]] 已修正過的語意,本次不變)。

## 4. RabbitMQ 拓撲

- Exchange:`order.exchange`(direct)
- Queue:`order.create.queue`,routing key `order.create`
- DLX:`order.create.dlx`,DLQ:`order.create.queue.dlq`
- 訊息內容:`{ outboxEventId, purchaseRequestId, userId, flashSaleId, quantity, unitPrice }`,JSON
- 去重鍵:`consumed_messages.message_id = outboxEventId`(字串化的 `outbox_events.id`),`consumer_name` 區分不同 consumer(如 `order-purchase-consumer`)

重試設定(`spring.rabbitmq.listener.simple.retry`):`max-attempts=3`、`initial-interval=1000ms`、`multiplier=2.0`、`max-interval=10000ms`,搭配 `RepublishMessageRecoverer` 在重試耗盡後把訊息連同例外資訊發到 DLQ,不使用預設的 requeue-forever。

**DLQ 處理**(`OrderCreateDlqHandler`):消費 DLQ 訊息,單一 Postgres transaction 內:`PurchaseRequest.status=FAILED`,並寫入一筆 `outbox_events(event_type=StockReleaseRequested)`(見 §5)釋放 Redis 端已預扣的庫存。DLQ 訊息本身不再重試,僅記錄供人工查詢(後台查詢留給 Week 4)。

## 5. 補償(取消/付款失敗/逾時/DLQ)統一走 outbox

觸發來源有四種,但收斂到同一機制,避免各自重複實作「改狀態 + 釋放庫存」:

- 使用者取消(`POST /api/orders/{orderId}/cancel`,僅限 `PENDING_PAYMENT`)
- 模擬付款失敗(`POST /api/orders/{orderId}/payments` 選擇失敗)
- 付款逾時排程(見下)
- Order-create DLQ(§4,此路徑 Postgres 庫存從未扣過,只需釋放 Redis,見下方差異說明)

**一般路徑**(訂單已在 Postgres 建立過,即取消/付款失敗/逾時三種)在同一個 Postgres transaction 內:
1. `Order` 狀態轉換(`cancel()`/`markExpired()`,付款失敗沿用 `cancel()` 的狀態機分支)並寫入 `order_status_history`
2. `Inventory.release(quantity)`:`available_quantity += qty`、`sold_quantity -= qty`
3. 寫入 `outbox_events(event_type=StockReleaseRequested, payload={flashSaleId, quantity})`

**DLQ 路徑**差異:Postgres 端從未建立 Order、也從未扣減 `inventory` 列(consumer transaction 失敗已整個 rollback),所以只需要第 3 步(寫 `StockReleaseRequested`),不做第 2 步。

`StockReleaseConsumer`(獨立 queue `stock.release.queue`,同一個 `order.exchange`、routing key `stock.release`)消費後對 Redis 做 `INCRBY stock:{flashSaleId} quantity`,一樣透過 `consumed_messages` 去重避免重複入帳。

付款逾時排程(`PaymentTimeoutScheduler`):`@Scheduled(fixedDelay = 30000)`,查詢 `status='PENDING_PAYMENT' AND payment_due_at < now()`,逐筆套用上述一般補償路徑。`payment_due_at` 維持既有 `createPendingPayment()` 的 15 分鐘,本次不改。

## 6. Payment 模組

新增 `com.flashsale.payment`(domain/application/adapter,比照其他模組分層)。

- `POST /api/orders/{orderId}/payments`,body 由前端指定模擬結果(`{ result: "SUCCESS" | "FAILURE" }`),僅限訂單擁有者、僅限 `PENDING_PAYMENT` 狀態(否則 `409`)
  - `SUCCESS`:寫入 `payment_records`(`result=SUCCESS`,`simulated_transaction_id` 用亂數/UUID 產生),`Order.pay()` → `PAID`
  - `FAILURE`:寫入 `payment_records`(`result=FAILURE`),走 §5 一般補償路徑
- `POST /api/orders/{orderId}/cancel`:僅限擁有者、僅限 `PENDING_PAYMENT`,走 §5 一般補償路徑

`Order` 新增狀態轉換方法,依主規格 §6 狀態圖(`PENDING_PAYMENT → PAID / CANCELLED / EXPIRED`):非法轉換(如對已 `PAID` 訂單呼叫 `cancel()`)拋 `ConflictException` → `409`。

## 7. Redis/Postgres 對帳排程

`@Scheduled(fixedDelay = 60000)`(`InventoryReconciliationScheduler`),只處理目前在售(`now()` 落在 `start_time`/`end_time` 之間)的 `flash_sales`:讀 Postgres `inventory.available_quantity`,與 Redis `stock:{flashSaleId}` 比對,不一致時記結構化警告 log(帶 flashSaleId、pg 值、redis 值)並以 Postgres 值 `SET` 回 Redis(Postgres 為準)。這是主規格 §7「系統提供定期對帳」的 MVP 落地,不含手動觸發 API/後台(留待第二階段)。

## 8. Nginx 限流

比照 Week 1 `/api/auth/*` 的 `limit_req_zone` 模式,在 `nginx/nginx.conf` 新增一個獨立 zone 給搶購端點:

```
limit_req_zone $binary_remote_addr zone=purchase_limit:10m rate=50r/s;
...
location ~ ^/api/flash-sales/\d+/purchase-requests$ {
    limit_req zone=purchase_limit burst=100 nodelay;
    ...
}
```

數值先取一般值,之後 Week 4 k6 壓測若有需要再調整,不在本輪深究。

## 9. 對既有程式碼的影響

`CreatePurchaseRequestService` 整個改寫為 §2 的非同步流程,移除單一 transaction 內完成扣庫存+建單的悲觀鎖版本。既有 `PurchaseConcurrencyIT`(證明悲觀鎖不超賣)與 `PurchaseControllerIT` 的部分斷言基於同步回應,需要跟著改寫:
- 新的並發測試改為對非同步端點發併發請求,輪詢 `purchase-requests` 到終態,驗證「成功數 = 庫存、不超賣、無重複建單」不變量,Testcontainers 需同時啟動 Postgres、Redis、RabbitMQ
- 新增:相同 `Idempotency-Key` 重放測試、consumer 訊息重複投遞測試(驗證 `consumed_messages` 去重生效)、DLQ 補償測試、付款逾時補償測試、對帳排程修正 drift 的測試

`Inventory` 新增 `release(quantity)` 領域方法(對稱於既有 `sell(quantity)`)。

## 10. 新增設定/環境變數

`compose.yaml` 已含 Redis、RabbitMQ 服務(Week 1 建立),本次新增 backend 對應的 Spring 設定:

```
SPRING_DATA_REDIS_HOST / SPRING_DATA_REDIS_PORT
SPRING_RABBITMQ_HOST / SPRING_RABBITMQ_PORT / SPRING_RABBITMQ_USERNAME / SPRING_RABBITMQ_PASSWORD
```

沿用 Docker Compose 內部服務名稱與既有帳密機制,非公開機敏資訊(本機 demo 用途),不需要額外加進 `.env.example` 的機敏區塊。若 Week 1 README 尚未提及 Redis/RabbitMQ 服務啟動說明,實作時順手補上一段。

## 11. 測試策略(對應主規格 §14)

- Domain unit tests:`Inventory.release()`、`Order.pay()/cancel()/markExpired()` 的合法/非法轉換
- Application tests:mock `InventoryStockGateway`/`OutboxRepository`,驗證 `CreatePurchaseRequestService` 各分支(冪等命中、活動未開賣、重複購買、庫存不足、成功寫 outbox)
- Integration tests(Testcontainers Postgres+Redis+RabbitMQ):§9 列出的並發、冪等重放、consumer 去重、DLQ 補償、付款逾時補償、對帳修正
- API tests:payment/cancel 端點的授權(僅擁有者)、狀態衝突(`409`)
