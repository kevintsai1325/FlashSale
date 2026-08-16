# FlashSale API 操作範例

以下指令可以直接貼進 Git Bash 或任何 POSIX shell 執行,對象是本機 Docker Compose 跑起來的
FlashSale。所有範例都用 shell 變數帶入憑證,文件本身不含任何真實 token、密碼或金鑰。

架構背景請看[架構深入說明](./architecture.md);面試展示的順序請看[Demo 腳本](./demo-script.md)。

## 前置準備

1. 依 [README](../../README.md) 的步驟建立 `.env`、產生本機自簽憑證,並啟動整套服務。
2. 產生可重複使用的 demo 資料(固定帳號、商品、活動與 1000 件庫存):

```bash
export DEMO_USER_PASSWORD='<在本機自訂的密碼>'
export DEMO_ADMIN_PASSWORD='<在本機自訂的另一組密碼>'
./scripts/demo-data.sh seed
```

`seed` 會建立兩個固定的 demo 帳號:`demo.user@example.test`(一般使用者)與
`demo.admin@example.test`(自動升級為 `ADMIN`)。展示結束後用 `./scripts/demo-data.sh cleanup`
清掉這批資料。工具細節見 [`scripts/demo-data.sh`](../../scripts/demo-data.sh)。

3. 設定共用變數。憑證是自簽的,所以 `curl` 一律加 `-k`;`jq` 只是為了好讀,不是必需品。

```bash
BASE_URL='https://localhost:8443'
```

> 全部 host 流量只走 `https://localhost:8443`,backend 沒有對外開埠。
> 互動式的 API 文件在 `https://localhost:8443/swagger-ui/index.html`。

## 註冊與登入

註冊(公開端點,成功回 `201`,回應只有 `id` 與 `email`):

```bash
curl -k -X POST "$BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo.user@example.test","password":"'"$DEMO_USER_PASSWORD"'"}'
```

```json
{ "id": 1, "email": "demo.user@example.test" }
```

登入。access token 放在回應 body,refresh token 則以 `HttpOnly`、`Secure`、
`Path=/api/auth` 的 `refresh_token` Cookie 下發(有效期 30 天),access token 有效期 15 分鐘,
內含 `userId` 與 `role` 兩個 claim:

```bash
ACCESS_TOKEN=$(curl -k -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -c cookies.txt \
  -d '{"email":"demo.user@example.test","password":"'"$DEMO_USER_PASSWORD"'"}' \
  | jq -r '.accessToken')
```

access token 過期後,用同一個 Cookie 換新的:

```bash
ACCESS_TOKEN=$(curl -k -s -X POST "$BASE_URL/api/auth/refresh" -b cookies.txt | jq -r '.accessToken')
```

登出會撤銷 refresh token 並清掉 Cookie,回 `204`:

```bash
curl -k -i -X POST "$BASE_URL/api/auth/logout" -b cookies.txt
```

> 若把某個帳號改成 `ADMIN`,必須重新登入才會拿到帶 `ROLE_ADMIN` 的新 token——角色是簽發當下寫進 JWT 的。

## 瀏覽搶購活動

活動查詢是公開的 `GET`,不需要 token:

```bash
curl -k -s "$BASE_URL/api/flash-sales" | jq
```

```json
[
  {
    "id": 1,
    "productId": 1,
    "productName": "[DEMO] Portfolio Product",
    "salePrice": 99.00,
    "startsAt": "2026-08-15T09:00:00Z",
    "endsAt": "2026-09-15T09:00:00Z",
    "purchaseLimitPerUser": 2,
    "totalQuantity": 1000,
    "status": "ACTIVE"
  }
]
```

單一活動另外回傳商品描述與目前 `availableQuantity`:

```bash
FLASH_SALE_ID=1
curl -k -s "$BASE_URL/api/flash-sales/$FLASH_SALE_ID" | jq
```

## 送出搶購請求

搶購是非同步的:API 只做限購檢查與 Redis 預扣,成功會回 `202 Accepted`。
`Idempotency-Key` 是必填標頭,重送同一把鍵不會重複預扣、也不會重複建單。

```bash
IDEMPOTENCY_KEY="DEMO-PORTFOLIO-$(date +%s)"
REQUEST_ID=$(curl -k -s -X POST "$BASE_URL/api/flash-sales/$FLASH_SALE_ID/purchase-requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{"quantity":1}' \
  | jq -r '.requestId')
```

```json
{ "requestId": "0f0b7c14-1f3f-4a52-8a0d-2f9d1f4b7a11", "status": "PENDING", "orderId": null }
```

`status` 在這一步就可能是終態:庫存不足會直接回 `SOLD_OUT`,已經買過的使用者會拿到 `REJECTED`。

## 查詢搶購請求狀態

建單由 RabbitMQ consumer 完成,所以要輪詢終態。只能查自己的請求,查別人的會得到 `404`:

```bash
curl -k -s "$BASE_URL/api/purchase-requests/$REQUEST_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

```json
{ "requestId": "0f0b7c14-1f3f-4a52-8a0d-2f9d1f4b7a11", "status": "SUCCEEDED", "orderId": 12 }
```

| `status` | 意義 |
|---|---|
| `PENDING` | 已預扣庫存,等待 consumer 建立訂單 |
| `SUCCEEDED` | 訂單已建立,`orderId` 有值 |
| `SOLD_OUT` | Redis 預扣時庫存不足 |
| `REJECTED` | 同一使用者在同一活動已有成功紀錄 |
| `FAILED` | 建單流程失敗 |

輪詢到終態的簡單寫法:

```bash
for _ in $(seq 1 20); do
  STATUS=$(curl -k -s "$BASE_URL/api/purchase-requests/$REQUEST_ID" \
    -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.status')
  echo "$STATUS"
  [ "$STATUS" = 'PENDING' ] || break
  sleep 1
done
```

## 我的訂單

訂單列表與明細都只回傳登入者自己的訂單,並附上下單當下的商品名稱快照:

```bash
curl -k -s "$BASE_URL/api/orders/me" -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

```json
[
  {
    "id": 12,
    "orderNo": "ORD-6a1f0d5e-6f0a-4a45-9f4d-6f8e0f1c2b33",
    "totalAmount": 99.00,
    "status": "PENDING_PAYMENT",
    "items": [
      { "productId": 1, "productName": "[DEMO] Portfolio Product", "quantity": 1, "unitPrice": 99.00 }
    ]
  }
]
```

單筆明細會多一個付款期限 `paymentDueAt`(建單後 15 分鐘):

```bash
ORDER_ID=12
curl -k -s "$BASE_URL/api/orders/$ORDER_ID" -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

## 付款與取消

付款是模擬的,由請求指定結果。`SUCCESS` 會把訂單轉成 `PAID`:

```bash
curl -k -s -X POST "$BASE_URL/api/orders/$ORDER_ID/payments" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"result":"SUCCESS"}' | jq
```

`FAILURE` 會走補償流程:訂單轉 `CANCELLED`、Postgres 庫存釋放,並發一筆 `stock.release`
事件把 Redis 計數器加回去。使用者主動取消也是同一條補償路徑:

```bash
curl -k -s -X POST "$BASE_URL/api/orders/$ORDER_ID/cancel" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

> 只有 `PENDING_PAYMENT` 的訂單能付款或取消;其他狀態會回 `409` 並帶 `ORDER_NOT_PAYABLE`
> 或 `ORDER_NOT_CANCELLABLE`。逾時未付款的訂單會被排程掃成 `EXPIRED`,同樣釋放庫存。

## 管理者 API

`/api/admin/**` 全部要求 `ROLE_ADMIN`。先用 demo 管理者帳號登入:

```bash
ADMIN_TOKEN=$(curl -k -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo.admin@example.test","password":"'"$DEMO_ADMIN_PASSWORD"'"}' \
  | jq -r '.accessToken')
```

儀表板摘要與趨勢:

```bash
curl -k -s "$BASE_URL/api/admin/dashboard/summary" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/api/admin/dashboard/trends" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

訂單與 API 稽核紀錄都支援分頁與條件過濾(`page` 預設 0、`size` 預設 20):

```bash
curl -k -s "$BASE_URL/api/admin/orders?status=PENDING_PAYMENT&page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/api/admin/api-logs?pathTemplate=/api/orders/me&status=200&size=5" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

八項服務健康度快照(Backend、PostgreSQL、Redis、RabbitMQ、Mailpit、Zipkin、Frontend、Nginx):

```bash
curl -k -s "$BASE_URL/api/admin/system-health" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

商品、活動與通知的後台端點:

```bash
curl -k -s "$BASE_URL/api/admin/products" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/api/admin/flash-sales" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/api/admin/notifications?read=false&size=5" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/api/admin/notifications/unread-count" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

搶購指標同樣需要 `ADMIN` 權限(`/actuator/metrics` 與 `/actuator/metrics/{name}` 有透過 Nginx 反代):

```bash
curl -k -s "$BASE_URL/actuator/metrics/purchase.reservation" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/actuator/metrics/purchase.reservation.latency" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
curl -k -s "$BASE_URL/actuator/metrics/purchase.order.created" -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

健康度端點則是公開的,不需要 token:

```bash
curl -k -s "$BASE_URL/actuator/health/readiness" | jq
```

## 錯誤格式

所有由應用程式丟出的錯誤都是 RFC 7807 `application/problem+json`,並額外帶 `code` 與 `traceId`
兩個屬性。注意 body 裡的 `traceId` 只是這一筆回應各自產生的隨機識別碼,並不是 tracing 的 trace id,
無法拿去 Zipkin 查詢;要跟 Zipkin 或後台的 API 稽核頁比對,請用每個回應都會帶的 `X-Trace-Id` 標頭。

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "數量 5 超過此活動每人限購 2 件的上限",
  "instance": "/api/flash-sales/1/purchase-requests",
  "code": "PURCHASE_QUANTITY_EXCEEDS_LIMIT",
  "traceId": "8f3a1c6d-6f2f-4f0f-9a45-1a2b3c4d5e6f"
}
```

常見錯誤碼:

| HTTP | `code` | 情境 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 請求欄位驗證失敗(例如 `quantity` 不是正整數) |
| 401 | `UNAUTHENTICATED` | 未帶或帶了無效的 access token |
| 401 | `INVALID_CREDENTIALS` | 登入帳號或密碼錯誤 |
| 401 | `INVALID_REFRESH_TOKEN` | refresh token 失效或已撤銷 |
| 403 | `ACCESS_DENIED` | 一般使用者存取管理者端點 |
| 404 | `FLASH_SALE_NOT_FOUND` / `ORDER_NOT_FOUND` / `PURCHASE_REQUEST_NOT_FOUND` | 資源不存在或不屬於自己 |
| 404 | `INVENTORY_NOT_FOUND` | 活動沒有對應的庫存資料 |
| 409 | `EMAIL_ALREADY_REGISTERED` | 註冊時 email 重複 |
| 409 | `FLASH_SALE_NOT_ACTIVE` | 活動尚未開始或已結束 |
| 409 | `PURCHASE_QUANTITY_EXCEEDS_LIMIT` | 超過每人限購數量 |
| 409 | `ORDER_NOT_PAYABLE` / `ORDER_NOT_CANCELLABLE` | 訂單已不在 `PENDING_PAYMENT` |
| 503 | `STOCK_GATEWAY_UNAVAILABLE` | Redis 預扣連續失敗 |
