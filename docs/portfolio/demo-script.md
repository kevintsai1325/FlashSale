# FlashSale Demo 腳本

一份 3～5 分鐘、可以照著念的展示流程。目標是在有限時間內證明三件事:**不會超賣**、
**尖峰請求被非同步削峰**、**每一步都留得下證據**。指令細節在
[API 操作範例](./api-examples.md),背後的設計理由在[架構深入說明](./architecture.md)與
[工程取捨](./trade-offs.md)。

## 展示前置檢查

開始前 10 分鐘做完,不要在對方面前等 build:

```bash
docker compose up --build -d
docker compose ps
```

1. 確認 8 個服務(`postgres`、`redis`、`rabbitmq`、`mailpit`、`zipkin`、`backend`、`frontend`、`nginx`)
   全部是 `healthy`。
2. 建立 demo 資料,並確認輸出的 `flashSale` 編號:

```bash
export DEMO_USER_PASSWORD='<在本機自訂的密碼>'
export DEMO_ADMIN_PASSWORD='<在本機自訂的另一組密碼>'
./scripts/demo-data.sh seed
```

3. 瀏覽器先開好四個分頁,並且**先點過一次自簽憑證的警告**:
   - `https://localhost:8443/`(前台)
   - `https://localhost:8443/admin`(後台,用 `demo.admin@example.test` 登入)
   - `http://localhost:9411/zipkin/`(Zipkin)
   - GitHub Actions 的 CI 頁面
4. 準備一個已登入 `demo.user@example.test` 的終端機視窗,變數(`BASE_URL`、`ACCESS_TOKEN`、
   `FLASH_SALE_ID`)先設好。
5. 把畫面字級調大,關掉會跳通知的程式。

## 分段腳本

| 時間 | 畫面 | 要說的重點 |
|---|---|---|
| 0:00–0:30 | 架構圖 | 8 個 Compose 服務,對外只有 Nginx 的 8443;backend 沒有 host port |
| 0:30–1:15 | 前台活動頁 → 搶購 | 搶購 API 回 `202 Accepted`,不是同步建單 |
| 1:15–1:50 | 搶購狀態頁 | 輪詢到 `SUCCEEDED`,訂單由 RabbitMQ consumer 建立 |
| 1:50–2:30 | 我的訂單 | 商品名稱是下單當下的快照,付款期限 15 分鐘 |
| 2:30–3:10 | 管理後台儀表板與 API 稽核 | 每個請求一列稽核,可用 `X-Trace-Id` 直接對到 Zipkin |
| 3:10–3:40 | 服務健康度頁 | 8 項健康度:4 個核心指標 + 4 個 HTTP 探針 |
| 3:40–4:30 | Zipkin trace | 一條 trace 串起 HTTP 請求 → outbox 發佈 → consumer 建單 |
| 4:30–5:00 | CI 與收尾 | CI 全綠、測試分層,並指向深入文件 |

### 0:00–0:30 開場

> 「這是一套限量搶購系統,用 Docker Compose 交付 8 個服務。對外只開 Nginx 的 8443,
> backend 完全不對 host 開埠。今天我想證明三件事:不超賣、尖峰用非同步削峰、每一步都留得下證據。」

### 0:30–1:50 前台搶購

在活動頁按下搶購。重點台詞:

> 「這個請求回的是 `202 Accepted` 加一個 `requestId`,不是訂單。HTTP 這一段只做三件事:
> 檢查活動與每人限購、用一支 Redis Lua 腳本原子預扣庫存、把 purchase request 和 outbox 事件
> 寫在同一個資料庫交易裡。真正建立訂單的是 RabbitMQ 的 consumer。」

如果想同時展示 API,可以在終端機補一次:

```bash
curl -k -i -X POST "$BASE_URL/api/flash-sales/$FLASH_SALE_ID/purchase-requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: DEMO-PORTFOLIO-$(date +%s)" \
  -d '{"quantity":1}'
```

指出回應標頭裡的 `X-Trace-Id`——等一下要用它把稽核紀錄和 Zipkin 串起來。
接著切到搶購狀態頁,秀出狀態從 `PENDING` 變成 `SUCCEEDED`。

### 1:50–2:30 我的訂單

> 「訂單明細裡的商品名稱是下單當下寫進 `order_items` 的快照,之後後台改商品名不會竄改歷史訂單。
> 訂單是 `PENDING_PAYMENT`,付款期限 15 分鐘,逾時會被排程掃成 `EXPIRED` 並自動把庫存還回去。」

有時間的話,現場示範一次取消,並說明補償路徑:Postgres 先釋放庫存,再發一筆 `stock.release`
事件把 Redis 計數器加回去。

### 2:30–3:10 管理後台

先看儀表板,再切到 API 稽核紀錄頁,用剛才的 trace id 過濾:

> 「每一個 API 請求都會寫一列稽核,包含方法、路徑樣板、狀態碼、使用者、耗時與 trace id。
> 這個 filter 掛在 Spring Security 的過濾鏈裡,所以連 401、403 都記得到,而且它從不碰
> Authorization 標頭或請求本體。寫入是非同步的,不佔用請求執行緒。」

### 3:10–3:40 服務健康度

> 「這頁把 Actuator 的 readiness、db、redis、rabbit 四個核心指標,加上 Mailpit、Zipkin、
> Frontend、Nginx 四個 HTTP 探針,合成一張 8 項快照,四個探針是併發打的。」

### 3:40–4:30 Zipkin

用同一個 trace id 在 Zipkin 搜尋:

> 「同一條 trace 從 HTTP 搶購請求開始,接到 outbox 發佈的 span,再接到 consumer 的建單。
> 能串起來是因為 outbox 事件把 trace context 存進 `outbox_events.trace_context`,發佈時再
> 還原成 parent span。日誌也是 JSON,每行都有同一個 traceId。」

補一刀日誌對照(可選):

```bash
docker compose logs --tail 50 backend | grep <trace-id>
```

### 4:30–5:00 CI 與收尾

> 「CI 在每次 push 到 `main` 與每個 PR 上平行跑兩個 job:backend 的 `./gradlew test`
> (涵蓋 unit、integration、API 與 ArchUnit 測試),以及 frontend 的 lint、型別檢查、build 與 vitest。
> 架構分層不是靠自律,是 ArchUnit 測試擋著:domain 不能依賴 adapter,模組之間也不能直接
> 依賴別人的 adapter 層。」

最後把三份深入文件的位置講出來:架構、工程取捨、API 範例。

## 備援方案

| 狀況 | 備援 |
|---|---|
| 前端頁面打不開或樣式壞掉 | 直接用[API 操作範例](./api-examples.md)的 curl 走完同一條流程 |
| 搶購一直停在 `PENDING` | 先看 `docker compose ps` 的 `rabbitmq` 與 `backend`;consumer 若掛掉,重啟 backend 後 outbox 會重送 |
| 活動已售罄或使用者已買過 | `./scripts/demo-data.sh cleanup` 之後重新 `seed`,庫存會回到 1000 |
| Zipkin 查不到 trace | 改用後台 API 稽核頁以 trace id 過濾,或 `docker compose logs backend` 看 JSON 日誌 |
| Redis 與畫面上的庫存對不上 | 等最多 60 秒的庫存對帳排程,或重新 `seed` |
| 想看註冊通知信 | `docker compose exec mailpit sh -c "wget -qO- http://localhost:8025/api/v1/messages"` |
| 8443 被其他程式占用 | 先停掉占用的程式再 `docker compose up -d`;不要臨時改埠,demo 工具只接受本機的 8443 |
| 對方想看程式碼而不是畫面 | 直接翻 `CreatePurchaseRequestService`、`OutboxPublisher`、`OrderPurchaseConsumer` 三個檔案 |

若時間只剩 2 分鐘,砍掉「我的訂單」與「CI」兩段,保留搶購 → 稽核 → Zipkin 這條主線。

## 收尾

```bash
./scripts/demo-data.sh cleanup
docker compose down
```

`cleanup` 只刪除本工具建立的 demo 資料(固定的兩個帳號、demo 商品與其活動、庫存,
以及由它們衍生的訂單與稽核紀錄),不會清空資料庫,重複執行也是安全的。
需要連同資料庫 volume 一起清掉時再用 `docker compose down -v`。
