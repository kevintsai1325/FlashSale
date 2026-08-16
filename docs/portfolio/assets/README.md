# 作品集截圖

本目錄收錄 FlashSale 作品集所使用的六張實機截圖,全部取自本機 Docker Compose 環境中
**真實執行**的系統,沒有任何合成、去背或事後修圖。

## 擷取資訊

| 項目 | 內容 |
| --- | --- |
| 擷取日期 | 2026-08-16 |
| 擷取當下的 commit | `ee01e0e251b16e88b635aca853619f9958f71fdd`(`ee01e0e`) |
| 瀏覽器 | Playwright driven Chromium(headless) |
| Viewport | 1440 x 900,`deviceScaleFactor: 1` |
| 裁切 | 只擷取頁面可視區域(viewport),不含瀏覽器 UI、不含 `fullPage` |
| 語系/時區 | `zh-TW` / `Asia/Taipei` |
| 資料來源 | `scripts/demo-data.sh seed` 建立的示範資料(`[DEMO] Portfolio Product`) |

## 六張截圖

| 檔名 | 路由 | 內容說明 |
| --- | --- | --- |
| `storefront.png` | `https://localhost:8443/` | 前台搶購活動列表。依 `現正開賣` / `即將開賣` / `已結束` 分組,示範商品 `[DEMO] Portfolio Product` 以 `$99.00`、`搶購中` 狀態顯示。 |
| `purchase-result.png` | `https://localhost:8443/purchase-requests/{requestId}` | 非同步搶購請求的最終結果頁。前端以輪詢方式等待請求由 `PENDING` 收斂到 `SUCCEEDED`,畫面顯示 `搶購成功` 印章與「查看訂單」連結。 |
| `my-orders.png` | `https://localhost:8443/orders` | 「我的訂單」列表,顯示訂單編號、`待付款` 狀態、訂購商品明細(`[DEMO] Portfolio Product` `1 × $99.00`)與訂單總額。 |
| `admin-dashboard.png` | `https://localhost:8443/admin` | 後台儀表板:搶購請求總數 / 成功請求數 / 已付款總額、訂單狀態分佈、近一小時與近 24 小時趨勢折線圖,以及各搶購活動的庫存摘要(總庫存、可購買、鎖定中、已售出)。 |
| `system-health.png` | `https://localhost:8443/admin/system-health` | 系統健康度頁,`整體狀態:UP`,並逐一列出 Backend、PostgreSQL、Redis、RabbitMQ、Mailpit、Zipkin、Frontend、Nginx 八個相依元件的即時狀態。 |
| `zipkin-trace.png` | `http://localhost:9411/zipkin/traces/{traceId}` | 一次搶購請求的完整分散式追蹤(9 個 span):`http post /api/flash-sales/{id}/purchase-requests` → `outbox publish` → `order.exchange/order.create send` → `order.create.queue receive`,可看出同步回應與非同步建單的時間分佈。 |

## 資訊安全處理

- 六張圖皆以 Playwright 的 page screenshot API 擷取,**只含頁面內容**,不含網址列、
  分頁列或任何瀏覽器警告(因此不會出現自簽憑證的警示畫面)。
- 圖中不存在 token、cookie、`Authorization` 標頭、`.env` 內容、終端機輸出或本機絕對路徑。
- 六個目標頁面的元件皆已逐一檢視,確認不會渲染使用者 Email;登入頁(含密碼欄位)
  完全不在擷取範圍內。
- 每張圖都經過人工目視檢查,確認預期文字可辨識且無敏感資訊外洩。
- PNG 已移除所有輔助區塊(僅保留 `IHDR` / `IDAT` / `IEND`),不含任何 metadata。
- 壓縮為**無損**處理:壓縮後的像素資料與原始擷取結果逐位元相同;每張圖皆遠低於 500 KiB
  (18.1 KiB ~ 80.7 KiB)。

## 重新產生步驟

以下步驟可完整重現這六張截圖。所有指令皆在 repo 根目錄執行。

1. 啟動本機環境,等待 8 個服務全部 healthy:

   ```bash
   docker compose -f compose.yaml --env-file .env -p flashsale up -d --build
   docker compose -p flashsale ps
   ```

2. 建立示範資料(密碼由你自行指定,清除步驟會一併刪除這兩個帳號):

   ```bash
   COMPOSE_PROJECT_NAME=flashsale \
   DEMO_USER_PASSWORD='<your-demo-user-password>' \
   DEMO_ADMIN_PASSWORD='<your-demo-admin-password>' \
     bash scripts/demo-data.sh seed
   ```

   指令結尾會印出示範活動的 flash sale id,後續步驟需要用到。

3. 在 **repo 之外**的暫存目錄安裝擷取工具(請勿把 Playwright 加進 `frontend/package.json`):

   ```bash
   npm init -y && npm install playwright sharp && npx playwright install chromium
   ```

4. 以 Playwright 依序擷取,瀏覽器 context 設定 `ignoreHTTPSErrors: true`
   (僅用於信任本機自簽憑證,**不要**因此修改 nginx 的 TLS 設定)、
   `viewport: { width: 1440, height: 900 }`、`locale: 'zh-TW'`、`timezoneId: 'Asia/Taipei'`:

   1. 以 `demo.user@example.test` 從 `/login` 登入(填寫 `#email` / `#password` 後送出)。
   2. 前往 `/` → `storefront.png`。
   3. 前往 `/flash-sales/{demoSaleId}` → 按下「搶購」→ 等待轉址到
      `/purchase-requests/{requestId}` 並出現「搶購成功!」→ `purchase-result.png`。
   4. 前往 `/orders` → `my-orders.png`。
   5. 清除 localStorage / cookies,改以 `demo.admin@example.test` 登入。
   6. 前往 `/admin` → `admin-dashboard.png`。
   7. 前往 `/admin/system-health` → `system-health.png`。
   8. 以 Zipkin API 找出剛才那次搶購的 trace:
      `GET http://localhost:9411/api/v2/traces?serviceName=flash-sale-backend&spanName=http%20post%20%2Fapi%2Fflash-sales%2F%7Bid%7D%2Fpurchase-requests&limit=5&lookback=3600000`,
      再前往 `http://localhost:9411/zipkin/traces/{traceId}` → `zipkin-trace.png`。

5. 無損壓縮並移除 metadata(sharp 的 `png({ compressionLevel: 9, effort: 10, palette: false })`,
   之後移除 `IHDR` / `IDAT` / `IEND` 以外的區塊;若壓縮後反而變大,就保留原始擷取檔)。
   請確認每張圖仍小於 500 KiB,且與原始擷取結果像素相同。

6. 目視檢查六張圖,確認預期文字可辨識、且不含任何機敏資訊。

7. 清除示範資料(可重複執行,只會刪除固定的示範識別碼):

   ```bash
   COMPOSE_PROJECT_NAME=flashsale bash scripts/demo-data.sh cleanup
   ```
