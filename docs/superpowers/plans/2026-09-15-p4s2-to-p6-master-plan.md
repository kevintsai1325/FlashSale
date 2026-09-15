# P4 步驟 2 → P6 總體計畫與決策紀錄

**背景：** 2026-09-14 完成 P4 步驟 1（purchase-service 拆成獨立服務、共用資料庫）。
本計畫涵蓋剩下的全部路線：獨立資料庫、Kafka、服務拆分完成、Flink 即時大屏。

**授權方式：** 使用者要求「一路做到 P6，有問題依業界常用做法自行決定並記錄」。
因此本文件的第一節就是決策紀錄 —— 每個決定都寫下選項、選擇、理由，以及**判斷錯了的代價**。
使用者的目的是事後學整套做法，所以「為什麼不選另一個」跟「選了什麼」一樣重要。

---

## 一、決策紀錄

### D1 — 每個服務一個 Postgres instance，而不是同一個 instance 開多個 database

| 選項 | 代價 |
|---|---|
| (a) 同一個 Postgres、不同 database | 省資源；但跨庫查詢只要改一行連線字串就寫得出來，編譯器與 DBA 都不會擋 |
| (b) **每個服務一個 StatefulSet** ← 選這個 | 多耗記憶體；但「不能跨庫 JOIN」變成物理事實而非團隊紀律 |

**理由：** database per service 的價值不在於「資料放在不同檔案裡」，而在於**移除跨服務直接讀寫的
可能性**。共用 instance 只是把耦合藏進連線設定，第一個趕工的人就會把它加回來。
雲端上這個選擇對應的是「每個服務一個 RDS instance」，是主流做法。

**判斷錯了的代價：** 單節點記憶體不夠，Pod 進入 Pending。緩解手段已備妥（見 D8）。

**命名：** `postgres-platform`（原本的 `postgres`，保留 PVC 不動）、`postgres-purchase`、
`postgres-order`、`postgres-analytics`。原本的 StatefulSet **不改名**，避免 PVC 重建與資料遺失；
只在文件與 ConfigMap 上以 platform 稱呼它。

### D2 — Kafka 用手寫的 KRaft 單節點 StatefulSet，不用 Strimzi

| 選項 | 代價 |
|---|---|
| Strimzi operator | K8s 上 Kafka 的事實標準；但引入 CRD、operator、webhook 與非同步 reconcile，`kubectl diff` 不再能判斷「有沒有漂移」 |
| Bitnami Helm chart | 專案目前沒有 Helm，引入它等於多一套模板語言 |
| **手寫 KRaft StatefulSet（apache/kafka 官方映像）** ← 選這個 | 沒有 rebalance / 滾動升級 / 憑證輪替的自動化 |

**理由：** Strimzi 的價值集中在多 broker 的維運自動化，而單節點 k3s 上**展示不出那些價值**，
只會多一層看不見的非同步行為。本專案的既有驗證流程（`verify.ps1` + `kubectl diff` 無漂移）
是刻意建立的資產，不該為了一個展示不出來的功能放棄它。

**判斷錯了的代價：** 若之後要多 broker 或做 topic 的宣告式管理，得整段換成 Strimzi。
升級路徑寫進 manifest 的註解。

### D3 — Kafka 與 RabbitMQ 共存，各司其職；不是「Kafka 取代 RabbitMQ」

規格原文把 Kafka 寫成「服務間事件匯流排」，並主張「拖到 P6 等於先用 RabbitMQ 搭一座之後要拆掉的橋」。
**這句話只對了一半，本計畫據實修正：**

| 訊息性質 | 走哪裡 | 為什麼 |
|---|---|---|
| **命令**（order.create、stock.release、通知寄送） | RabbitMQ | 需要 per-message ack、重試、毒訊息隔離（DLQ）。這三件事 Kafka 都要自己刻 |
| **領域事件**（OrderCreated、OrderCancelled、PurchaseRequested…） | Kafka | 需要保留期、重放、多個互不影響的消費者。這三件事 RabbitMQ 都做不到 |

**理由：** 現有的 `OrderCreateDlqHandler` 補償路徑是承重結構，靠的正是 RabbitMQ 的 DLQ 語意；
把它改寫成 Kafka 的 retry topic + DLT 是一筆沒有回報的改寫風險。反過來說，analytics 與 Flink
需要的是可重放的事件日誌，RabbitMQ 餵不了 —— 訊息被消費就不見了，重算聚合只能重跑資料庫。
**兩者的職責不同，共存是主流架構，不是妥協。**

**橋沒有被拆掉：** 既有的 RabbitMQ 路徑一條都不動，Kafka 是新增的一層。

**判斷錯了的代價：** 維運兩套訊息中介的複雜度。這個代價要寫進作品集文件，不能假裝沒有。

### D4 — 領域事件經同一張 outbox 表送出，由 event type 決定 transport

新增事件不另建機制：`OutboxPublisher` 依 event type 路由到 RabbitMQ 或 Kafka。

**理由：** outbox 模式解的是「資料庫交易與訊息發佈的原子性」，這個問題與 transport 無關。
為 Kafka 另做一套 outbox 等於把同一個問題解兩次，而且兩套的「至少一次」保證會有不同的漏洞。

### D5 — 事件分區鍵用 `flashSaleId`

規格的驗收條件是「同一 `activityId` 的事件落在同一分區且順序正確」。

**理由：** Kafka 只保證**單一分區內**的順序。秒殺場景需要順序的是「同一場活動的事件流」
（Flink 要算每場活動的 GMV 與熱門排行），跨活動之間無序無所謂。用 `userId` 當鍵會把同一場活動
打散到所有分區，Flink 算每場活動的視窗聚合就得跨分區 shuffle。

**判斷錯了的代價：** 單一熱門活動會讓一個分區變熱點。這是秒殺的本質 —— 流量本來就集中在一場活動，
換任何鍵都改變不了總量，只會犧牲順序。

### D6 — Saga 用編排式（choreography），不引入編排器（orchestrator）

現有流程已經是編排式 saga：預扣 Redis → 事件 → 建單 → 事件回寫終態 → 失敗則 DLQ 補償。
拆庫後這個形狀不變，只是每一步落在不同的資料庫。

**理由：** 這條 saga 只有三個參與者、一條主線、一個補償分支。引入 orchestrator（Temporal、
Camunda、或自己刻狀態機）要付出一個新的單點與一套新的維運，換來的可觀測性在三步流程上看不出價值。
**主流的判準是步驟數與分支數，不是「微服務就要有 orchestrator」。**

**判斷錯了的代價：** 若之後加入金流、物流等更多參與者，補償邏輯會散落在各服務的 DLQ handler 裡，
難以看出全貌。屆時再換，事件流已經在 Kafka 上，改寫成 orchestrator 不需要重做資料流。

### D7 — Flink 用 standalone session cluster（手寫 manifest），不用 Flink Kubernetes Operator

理由與 D2 同構：operator 的價值在 job 的自動 failover 與版本升級，單節點展示不出來，
卻要多裝 cert-manager 與一組 CRD。升級路徑寫進 manifest 註解。

**判斷錯了的代價：** JobManager 掛掉時 job 不會自動重新提交。P6 的驗收項目之一是
「重啟 TaskManager 後聚合仍正確」—— 那一項用 TaskManager 驗證，不需要 operator。

### D8 — 單節點資源不夠時，降的是副本數，不是 requests

節點是 16 vCPU / 15.5 GiB。P6 完成時的工作負載遠多於現在的 13 個 Pod。

**理由：** 把 requests 調到低於實際用量會讓 scheduler 超賣，症狀是隨機的 OOMKilled 與
liveness 誤判 —— 也就是 P3 已經踩過一次的坑。降副本數是誠實的：系統會變慢，但不會變成
「看起來會動、壓一下就垮」。

**執行方式：** P5／P6 開發期間 backend 與 purchase-service 降到 2 副本；
需要跑水平擴展相關量測時再臨時調回。這件事要寫進文件，避免之後有人拿 2 副本的數字
去跟 P3 的 3 副本數字比較。

### D9 — 即時大屏的推送用 SSE，不用 WebSocket

**理由：** 大屏是單向推送（伺服器 → 瀏覽器），SSE 在瀏覽器端有內建的自動重連，
在 Nginx 端只要關掉 buffering，在 Spring 端是一個 `SseEmitter`。WebSocket 要多一套握手、
心跳與重連邏輯，換來的雙向能力用不到。

**判斷錯了的代價：** 若之後大屏要做互動（切換活動、下鑽），SSE 只能靠額外的 HTTP 請求。
對一個「看數字」的大屏，這個限制不會撞到。

---

## 二、階段切分

每個階段結束都是可部署、可驗收、可展示的系統，並各自留下 commit 與文件。

| 階段 | 內容 | 驗收 |
|---|---|---|
| **P4-2** | purchase-service 自帶 `postgres-purchase`，backend 不再讀 `purchase_requests` | 完整搶購流程通過；backend 的程式碼裡沒有任何 `purchase_requests` 的參照 |
| **P4-3** | Kafka 進叢集；領域事件經 outbox 送上 Kafka；分區順序驗證 | 同一 `flashSaleId` 的事件落在同一分區且有序 |
| **P5-1** | order-service 從單體拆出（含 inventory），自帶 `postgres-order` | 建單流程跨三個服務；混沌測試（刪 order-service Pod）後最終一致 |
| **P5-2** | analytics-service 新增，自帶 `postgres-analytics`，消費 Kafka 建讀取模型 | 後台儀表板改由 analytics 提供，數字與拆分前一致 |
| **P6** | Flink session cluster + 三個作業 + 即時大屏頁面 | 秒級 GMV／每秒訂單數／熱門 Top N；與 `order_db` 的 `SUM` 對得起來 |

### 每個階段的不變量（全程不得退步）

1. 不超賣、不重複下單、不殘留 `PENDING`、outbox 全部發佈完成、DLQ 全空
2. 一條 trace 串起整條搶購鏈路，跨全部服務
3. `scripts/k8s/verify.ps1` 通過，`kubectl diff` 對 `k8s/base` 無漂移
4. CI 全綠（**含 purchase-service 的測試 —— 目前 CI 沒跑，P4-2 順手補上**）

### 全程約束（沿用 P4 步驟 1）

- 不得在同一個 commit 裡同時改變行為與結構
- 不抽共用 library；服務之間共用的是契約，不是型別
- 含非 ASCII 的 `.ps1` 必須是 UTF-8 with BOM
- 不用 PowerShell 讀寫含非 ASCII 的檔案
- `kubectl` 一律帶 `--context rancher-desktop -n flashsale`

---

## 三、P4-2 的具體工作

### 三個必須先解掉的跨庫讀取

backend 目前有三處讀 `purchase_requests`，拆庫後全部會斷：

| 位置 | 用途 | 解法 |
|---|---|---|
| `OrderCompensationService:57` | 從 orderId 反查 `flashSaleId` 以釋放庫存 | **把 `flash_sale_id` 存進 `orders`**。訂單本來就該知道自己來自哪場活動，這是遺漏的欄位，不是為了拆分硬加的 |
| `AdminOrderQueryService:80` | 後台訂單詳情顯示搶購請求資訊 | 同上：`orders` 有了 `flash_sale_id` 與 `purchase_request_id` 就夠用；狀態欄位改為由訂單狀態推導 |
| `DashboardQueryService:46,47,72` | 儀表板的搶購請求總數／成功數／趨勢 | P4-2 先改為呼叫 purchase-service 的內部統計 API；P5-2 再換成 analytics 的讀取模型 |

前兩項是**去正規化**，第三項是**跨服務查詢**。兩種手段都用上，正好對照它們各自的適用時機：
訂單需要的是「當下這筆的事實」，適合在事件裡帶著走並存下來；儀表板需要的是「別人那邊的當前統計」，
沒有適合存下來的時點。

### 任務順序

1. `orders` 加 `flash_sale_id`、`purchase_request_id` 兩欄（Flyway `V4`），`CreateOrderRequestedEvent`
   已經帶著這兩個值，建單時一併寫入；既有資料用 `purchase_requests` 回填（拆庫前的最後機會）
2. 改掉上表的三處讀取，backend 刪除 `PurchaseRequest` 唯讀投影與其 repository
3. purchase-service 接管 schema：自己的 Flyway migration（`purchase_requests`、`outbox_events`、
   `consumed_messages`），`ddl-auto: validate` 不變
4. 新增 `postgres-purchase` StatefulSet 與對應的 ConfigMap／Secret 鍵，purchase-service 改連它
5. backend 的 Flyway 刪除 `purchase_requests`（`V5`），outbox 表留著 —— 兩邊各有一份，互不相干
6. 補回 purchase-service 的整合測試（現在它有自己的 schema 了，這是步驟 1 刻意留下的缺口）
7. CI 新增 purchase-service 的測試 job
8. 部署、驗收、文件

### 一個必須明講的風險

步驟 5 之後，**兩個服務各自有一張 `outbox_events` 表與各自的發佈器**。
步驟 1 那條「兩邊都必須認得全部 event type」的限制隨之消失，`RabbitConfig` 與 `EventTypes`
裡為此保留的常數要跟著清掉 —— 留著會讓下一個人以為表還是共用的。

---

## P4-2 完成紀錄（2026-09-15）

### 驗收結果（Rancher Desktop 的 k3s 上實測）

| # | 條件 | 結果 |
|---|---|---|
| 1 | 兩個資料庫各自 migrate | platform 6 個 migration、purchase 2 個，全部 success |
| 2 | 完整搶購流程 | 下單 202 PENDING → **1 秒內** `SUCCEEDED` + `orderId=5`；訂單出現在 `GET /api/orders/me`，商品名稱快照正確；同一把冪等鍵重送回同一筆 |
| 3 | 正確性不變量 | `orders=1`、庫存 5→4、`purchase_requests=SUCCEEDED`、兩個資料庫的未發佈 outbox 皆為 0、三條 DLQ 全空 |
| 4 | 跨服務的一條 trace | 30 個 span、兩個 service name、父子關係正確 |
| 5 | backend 掛掉時的行為 | 已快取的活動 → `202 REJECTED`（每人限購規則有被套用，代表決策流程真的走完了）；沒被搶過的活動 → `503 FLASH_SALE_LOOKUP_UNAVAILABLE`；**輪詢端點照常回應**（那份資料現在完全屬於 purchase-service） |
| 6 | `verify.ps1` 與無漂移 | 兩者皆通過，14 個 Pod 全部 Running、0 重啟 |
| 7 | backend 的程式碼裡沒有 `purchase_requests` | 只剩註解裡的歷史說明 |

### 部署時抓到一個只有真跑才會出現的錯誤

第一次跑完整流程，搶購請求卡在 `PENDING`，而佇列與 DLQ 全空、outbox 顯示已發佈。
原因是**去重鍵**：`consumed_messages` 存的是發佈端 outbox 表的 BIGSERIAL id。
共用一張表時那個數字唯一；拆庫之後 purchase-service 的 outbox 從 1 重新開始，而 backend
那張表裡早就有 `message_id='1'`（共用時代留下的），第一筆建單事件被認成重複投遞、丟掉。

改成事件自己帶 UUID（CloudEvents 的 `id` 在做的事）。**三種測試都抓不到它**：單元測試各自
挑自己的 id、整合測試每次清空資料庫、契約測試不碰資料。只有在一個有歷史資料的環境上
真的部署才會撞到 —— 與 P4 步驟 1 那兩個錯誤同一類。

### 順手觀察到的既有行為（不是這次引入的）

那條 30 span 的 trace 裡，同一筆事件出現**兩次** `outbox publish → order.create send → receive`。
這不是 bug，是 `OutboxPublisher` 刻意的形狀：`fetchBatch()` 的列鎖必須在
`publishEvent()` 標記已發佈之前釋放（否則自己鎖自己），於是另一個副本可能在這個空隙撈到
同一列。系統因此是**至少一次**投遞，而消費端的去重正是為此存在 —— 這次的 bug 也證明了
少了它會發生什麼。三副本時這個重複會比單副本明顯得多。

### 與計畫不同的地方

- **跨服務參照改用 UUID 而非 BIGSERIAL**（計畫沒寫）。事件契約與 `orders.purchase_request_id`
  都改成 purchase-service 公開的 `request_id`。理由：本地代理鍵是那個資料庫的實作細節，
  而後台要顯示、使用者要查詢的識別碼本來就是那個 UUID。
- **儀表板的分桶錨點由呼叫端傳給 purchase-service**。兩個行程各自取 `now()` 再截到分鐘，
  跨越分鐘邊界時桶起點會差一格，對得起來的資料看起來像消失了。
- **順手補上 P4 步驟 1 的四個缺口**：purchase-service 的整合測試（現在它有自己的 schema 了）、
  CI 從未跑過它的測試、`deploy.ps1` 沒重啟它（`imagePullPolicy: Never` 吃不到新映像）、
  `verify.ps1` 沒等它的 rollout。

### 刻意留下的缺口

- **Compose 的舊 benchmark 工具（`load-tests/benchmark/`）不修**，只標記為不可執行。
  它的 stack 沒有 purchase-service，而且用的是 P3 已證明不能回答擴展問題的封閉模型。
- **示範資料與壓測的清理要打兩個資料庫，中間沒有交易。** 順序必須先 purchase 後 platform，
  否則 platform 的 id 清單先消失，purchase 那邊會留下永遠清不掉的孤兒。
- **儀表板的兩半是最終一致的**：搶購請求數與訂單數取自兩個時點，高併發時會差幾筆。
  這是跨服務儀表板的本質；要嚴格對齊得等 P5-2 的讀取模型（同一條事件流算出來）。
