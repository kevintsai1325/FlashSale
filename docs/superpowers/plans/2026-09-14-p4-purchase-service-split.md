# P4：把 purchase-service 拆成獨立服務（步驟 1：共用資料庫）

**Goal:** 把「承接搶購請求」那一段從模組化單體拆成獨立部署的服務，與單體共用同一個
PostgreSQL。產出是兩個 deployable、一條跨服務的 trace，以及一個真實的同步服務間呼叫
（含它的失效行為）。**不碰跨資料庫一致性** —— 那是步驟 2。

**Non-goal:** 效能。這次拆分不預期任何延遲或吞吐改善，多一次 HTTP 呼叫只會更慢。
任何宣稱效能的說法都必須先量測，而本計畫不量測。

---

## 為什麼做這件事（以及原本的理由已經不成立）

P3 的文件把 P4 的動機寫成「所有副本共用同一個資料庫連線池，副本數 × 連線池會撞上
`max_connections`」。**這個理由在 2026-09-14 之後不成立了**：HPA 的上限依專案範圍收到
3 副本，3 × 30 = 90 條連線，連 Postgres 預設的 100 都撐得住。

所以現在的理由只有一個，而且要誠實寫出來：**練習跨服務通訊與它的失效模式**。
不是為了效能，不是為了解決現有瓶頸 —— 現在沒有那個瓶頸。

這件事會影響作品集文件的寫法：P4 的成果文件不能宣稱「拆分解決了什麼問題」，
只能宣稱「拆分之後系統長什麼樣、多了哪些失效模式、怎麼處理」。

---

## Global Constraints

- **不得同時改變行為與結構**。搬移程式碼的 commit 不改邏輯；改邏輯的 commit 不搬程式碼。
  兩者混在一起時，任何測試失敗都無法判斷是搬壞了還是改壞了。
- **正確性不變量不得退步**：不超賣、不重複下單、不殘留 `PENDING`、outbox 全部發佈完成、
  兩條 DLQ 全空。這是既有壓測的驗收條件，拆分後必須仍然成立。
- **不抽共用 library**。兩個服務需要的相同程式碼用複製的（見 Task 1 的理由）。
- **所有含非 ASCII 的 `.ps1` 必須是 UTF-8 with BOM**；CI 的 `scripts` job 會擋。
- **不要用 PowerShell 讀寫含非 ASCII 的檔案**；改檔用 Node 或 Python 並明確指定 encoding。
- **kubectl 一律帶 `--context rancher-desktop -n flashsale`**。
- **每個階段結束要讓 `scripts/k8s/verify.ps1` 通過**，並且 `kubectl diff` 對 `k8s/base` 無漂移。

---

## 接縫在哪裡

搶購流程已經被 RabbitMQ 切成兩半，拆分就沿著這條既有的縫：

```
【purchase-service】                        【monolith】
POST /api/flash-sales/{id}/purchase-requests
  → 查冪等鍵、活動狀態、每人限購
  → Redis Lua 預扣
  → 寫 purchase_requests(PENDING) + outbox   ──order.create──▶  OrderPurchaseConsumer
GET /api/purchase-requests/{requestId}                            → 鎖庫存列、扣 Postgres 庫存
  ← 讀自己的 purchase_requests                                    → 建立訂單
                                            ◀──order.created──   → 發事件（新增）
  PurchaseRequestConsumer（新增）
  → markSucceeded(orderId)
```

**寫入路徑不需要新的同步呼叫** —— 它本來就是非同步的。這是選這條縫的主因。

### 三個要處理的耦合點

| 耦合 | 現況 | 步驟 1 的處理 |
|---|---|---|
| `FlashSaleRepository.findById` | 同行程讀 DB | **改成 HTTP 呼叫 monolith 的內部 API**（見 Task 3） |
| `InventoryStockGateway.reserve` | 同行程打 Redis | purchase-service 直接連 Redis，不經 monolith |
| `purchase_requests` 的終態更新 | consumer 同行程寫 | **改成 `order.created` 事件**，purchase-service 自己更新（見 Task 5） |

第三點特別重要：共用資料庫時最容易的做法是讓 monolith 的 consumer 直接寫
`purchase_requests`，但那會變成**兩個服務寫同一張表**，步驟 2 拆庫時一定要重做。
用事件從一開始就把寫入權責收在單一服務，步驟 2 只要換掉儲存位置。

---

## File Structure

| 檔案 | 責任 |
|---|---|
| `purchase-service/build.gradle.kts` | 獨立的 Gradle 專案，不是 backend 的子模組 |
| `purchase-service/Dockerfile` | 與 `backend/Dockerfile` 同樣的多階段建置 |
| `purchase-service/src/main/java/com/flashsale/purchase/` | 搬過來的 controller / service / domain / repository |
| `purchase-service/src/main/resources/application.yml` | 自己的資料來源、Redis、RabbitMQ、JWT 公鑰設定 |
| `k8s/base/application.yaml` | 新增 purchase-service 的 Deployment 與 Service |
| `nginx/nginx.conf` | 兩條 location 改指向 purchase-service |
| `docker-compose.yml` | 新增 purchase-service |
| `scripts/tests/k8s-manifests-test.ps1` | 資源數、stage 標籤、探測、映像檔的契約跟著更新 |
| `docs/portfolio/architecture.md` | 服務邊界圖與責任說明 |

---

## Task 1: 建立 purchase-service 的骨架

新增一個**獨立的 Gradle 專案**（不是 `backend` 的子模組），只放它自己需要的東西。

**為什麼不抽一個 `shared` module**：拆服務的目的是解除耦合。抽共用 library 只是把耦合
換個地方藏 —— 之後任何一方要改共用型別，另一方都被迫跟著動，而且兩個服務會被綁在同一個
建置與版本上。服務之間共用的應該是**契約**（事件的 JSON schema、HTTP 的 API），不是型別。
代價是要複製一些程式碼（`OutboxWriter`、queue/exchange 名稱常數、錯誤回應格式、JWT 驗證
設定），這個代價是刻意付的，要寫進文件而不是假裝沒有。

**驗證**：`./gradlew build` 在兩個專案都綠；purchase-service 起得來，`/actuator/health`
回 `UP`（此時還沒有任何業務端點）。

---

## Task 2: 搬移程式碼（純搬移，不改邏輯）

搬 `PurchaseController`、`CreatePurchaseRequestService`、`PurchaseRequest`、
`PurchaseRequestRepository` 與其 JPA 實作、Redis 預扣 gateway 與 Lua 腳本、`OutboxWriter`
與 `OutboxPublisher` 排程（含它的分散式鎖）。

`FlashSaleRepository` 的呼叫先留一個介面、用假實作頂著，讓這個 Task 能單獨編譯與測試 ——
真正的 HTTP 實作在 Task 3。

**這個 Task 不刪 monolith 的舊程式碼**，兩邊同時存在，避免搬到一半兩邊都不能跑。
刪除在 Task 6。

**驗證**：搬過去的單元測試在 purchase-service 全綠；monolith 的測試不受影響。

---

## Task 3: FlashSale 查詢改成同步 HTTP 呼叫

這是整個步驟 1 唯一新增的同步跨服務依賴，也是最值得練的部分。它在搶購的熱路徑上。

monolith 新增內部 API（不經 Nginx 對外暴露），purchase-service 用它取得活動狀態、
每人限購、`productId`、`salePrice`。

必須明確決定並寫下理由的四件事：

1. **逾時預算**：這個呼叫慢的時候，搶購 API 應該等多久才放棄
2. **失敗時的行為**：拒絕請求（保守）還是用快取的活動資料放行（可用但可能賣錯價）
3. **要不要快取**：活動資料在活動期間幾乎不變，快取能把熱路徑上的跨服務呼叫降到趨近於零
4. **monolith 掛掉時搶購還能不能開**：這題的答案決定了這次拆分是提高還是降低了可用性

**驗證**：注入延遲與失敗，觀察搶購 API 的行為符合上面寫下的決定 ——
不是「它沒壞」，而是「它照我們決定的方式壞」。

---

## Task 4: 追蹤要能跨服務串起來

現況是一條 trace 串起「HTTP 搶購 → outbox 發佈 → consumer 建單」（README 有截圖）。
拆分後這條 trace 跨兩個行程，必須仍然是一條。

**驗證**：Zipkin 上一條 trace 同時出現兩個 service name，且 span 的父子關係正確。
這是拆分後可觀測性沒有退步的證據，也是新的截圖素材。

---

## Task 5: 終態更新改成事件

monolith 建單成功後發 `order.created`，purchase-service 消費它、更新自己的
`purchase_requests` 為 `SUCCEEDED` 並記下 `orderId`。

消費必須冪等（既有的 `ConsumedMessageGuard` 是同一套做法），並且要有 DLQ。
建單失敗的補償路徑（釋放 Redis 預扣）留在 monolith，不變。

**注意**：這一步讓「搶購結果」變成最終一致 —— 使用者輪詢到終態的時間會多一段訊息延遲。
既有的前端輪詢已經是為此設計的，但延遲分佈會改變，`completedLatencyMs` 的數字不能跟
拆分前直接比較。

**驗證**：既有的正確性不變量壓測全過；重複投遞同一個 `order.created` 不會產生第二次更新。

---

## Task 6: 刪掉 monolith 裡已經搬走的程式碼

Task 2 刻意留下的重複，在這裡一次刪除。ArchUnit 的模組邊界規則跟著更新。

**驗證**：monolith 測試全綠；`order` 模組對 `inventory` / `flashsale` / `catalog` 的
import 數量下降（現況是 9 個），剩下的每一個都要能說明為什麼還在。

---

## Task 7: 部署與路由

- `docker-compose.yml` 新增 purchase-service
- `k8s/base/application.yaml` 新增 Deployment 與 Service（探測、資源、`preStop`、
  `terminationGracePeriodSeconds` 比照 backend；**`livenessProbe` 的 `timeoutSeconds` 要明寫**，
  不要重蹈 backend 吃 1 秒預設值那個坑）
- `nginx/nginx.conf` 把兩條 purchase 相關的 location 指向 purchase-service，
  限流設定跟著搬
- `scripts/tests/k8s-manifests-test.ps1` 的資源數、工作負載清單、stage 標籤、
  本地映像檔契約全部更新

**驗證**：`scripts/k8s/verify.ps1` 通過；`kubectl diff` 對 `k8s/base` 無漂移；
從 `https://localhost:8443` 走完一次完整搶購流程。

---

## 完成的定義

下列全部成立才算完成：

1. 兩個服務各自獨立建置、獨立部署、獨立滾動更新
2. 從 `https://localhost:8443` 完成一次完整搶購（下單 → 輪詢到終態 → 訂單出現在「我的訂單」）
3. 既有的正確性不變量壓測全過：不超賣、不重複下單、不殘留 `PENDING`、outbox 清空、DLQ 全空
4. Zipkin 上一條 trace 跨兩個服務、父子關係正確
5. monolith 停掉時，搶購 API 的行為符合 Task 3 寫下的決定（而不是任意壞掉）
6. `verify.ps1` 通過、`kubectl diff` 無漂移
7. 作品集文件寫明：這次拆分**沒有**解決任何效能問題，以及它新增了哪些失效模式

---

## 步驟 2 預告（不在本計畫範圍）

purchase-service 自帶 PostgreSQL 之後才會遇到的問題，屆時另立計畫：

- 庫存扣減與建單落在兩個資料庫 → 需要 Saga 或最終一致性加補償
- 「我的訂單」要顯示商品名稱 → 跨服務查詢或資料複製
- 兩套 Flyway migration 的版本管理
- 失敗補償本身的冪等與重試

步驟 1 的三個處理（事件更新終態、不共用型別、單一寫入權責）都是為了讓步驟 2
只需要換儲存位置，而不是重做一次架構。
