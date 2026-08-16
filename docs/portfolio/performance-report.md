# FlashSale 負載特性報告

這份報告記錄 FlashSale 目前的搶購流程在一台開發機上跑出來的負載特性。所有數字都來自同一次
壓測、同一份結果文件 [`data/benchmark-results.json`](./data/benchmark-results.json),
由 [`load-tests/benchmark/`](../../load-tests/benchmark/README.md) 這套工具收集,並通過
`verify-results.mjs` 的驗證。

**這份報告不宣稱什麼:**

- 不是正式環境的容量數字,也不是任何形式的 SLA。這是一台筆電上的 Docker Compose。
- 沒有跟任何早期版本、其他實作或其他系統做比較。這裡只有「目前這套系統在這些條件下量到什麼」。
- 沒有設定任何效能門檻。只有正確性不變量會讓一次執行被判定為不合格,延遲與吞吐量都只是照實記錄。

搭配閱讀:[架構深入說明](./architecture.md)、[工程取捨](./trade-offs.md)、
[壓測工具說明](../../load-tests/benchmark/README.md)。

## 怎麼查證這份報告的每個數字

報告裡每個數字都對應到結果文件中的一個路徑。表格中的 `runs[n]` 就是 `runs` 陣列的索引:
`runs[0]`…`runs[14]` 是 15 次競爭情境,`runs[15]` 是 soak。常用路徑:

| 報告中的欄位 | JSON 路徑 |
|---|---|
| accepted 延遲 | `runs[n].metrics.acceptedLatencyMs.{min,med,p90,p95,max,avg}` |
| completed 延遲 | `runs[n].metrics.completedLatencyMs.{min,med,p90,p95,max,avg}` |
| orderCreated 延遲 | `runs[n].metrics.orderCreatedLatencyMs.{min,med,p90,p95,max,avg}` |
| 每秒請求數 | `runs[n].metrics.requestsPerSecond` |
| 各種結果的筆數 | `runs[n].outcomes.{accepted,succeeded,soldOut,rejected,failed,unexpected5xx,pendingTimeout,missingToken}` |
| 資料庫不變量 | `runs[n].invariants.*` |
| 佇列深度(含兩條 DLQ) | `runs[n].queues[]` |
| 健康檢查 | `runs[n].health` |
| Actuator 指標 | `runs[n].actuator` |

延遲一律以毫秒表示,表格中四捨五入到小數點後一位;完整精度在 JSON 裡。「中位數」欄位是該情境
五次執行各自數值的中位數,五個原始值都列在同一張表中,可以自行核對。

## 量測環境

以下全部來自 `environment`:

| 項目 | 值 | JSON 路徑 |
|---|---|---|
| Git commit | `aba12097c3e76b6dbbb366d7bc48bdc1154d610e` | `environment.gitSha` |
| Git 分支 | `codex/week7-portfolio` | `environment.gitBranch` |
| 工作區是否有未提交變更 | 否 | `environment.gitDirty` |
| 作業系統 | Microsoft Windows 11 家用版 10.0.26200 | `environment.os` |
| CPU | Intel(R) Core(TM) i7-14650HX,24 個邏輯核心 | `environment.cpu` |
| 記憶體 | 31.6 GB | `environment.memoryGb` |
| Docker Engine | 29.6.2 | `environment.dockerVersion` |
| Docker Compose | 5.3.1 | `environment.dockerComposeVersion` |
| k6 | v2.2.0 (go1.26.5, windows/amd64) | `environment.k6Version` |
| Compose 專案 | `flashsale-benchmark` | `environment.composeProject` |
| 壓力進入點 | `http://127.0.0.1:18080` | `environment.backendBaseUrl` |
| 開始 / 結束 | 2026-08-16T12:16:45Z / 2026-08-16T12:33:57Z(UTC) | `environment.startedAt`、`environment.finishedAt` |

共 16 次執行,全部完成,沒有任何一次失敗(`summary.expectedRuns` 為 `16`、
`summary.failedRuns` 為 `0`,`runs` 陣列長度為 16)。

**同機干擾:** 收集這份資料時,這台機器上同時還跑著一般的 `flashsale` Compose 專案(8 個
服務,閒置但在執行中)。壓測用的是完全隔離的 `flashsale-benchmark` 專案(獨立 volume、
獨立 port),資料不會互相污染,但 CPU 與 I/O 是共用的。這對延遲數字的影響沒有被量化。

## 量測方法

### 量的是哪兩段

搶購 API 是「接受後非同步完成」的設計(細節見[架構深入說明](./architecture.md#核心搶購資料流)),
所以延遲分成兩段記錄:

| 指標 | 意義 |
|---|---|
| `acceptedLatencyMs` | 同步的那一段:`POST /api/flash-sales/{id}/purchase-requests` 回應的耗時,涵蓋限購檢查與 Redis Lua 預扣。買家沒搶到(`SOLD_OUT`)時,這個回應本身就是終態。 |
| `completedLatencyMs` | 從送出上述 POST 起算,到輪詢 `GET /api/purchase-requests/{requestId}` 觀察到終態為止。 |
| `orderCreatedLatencyMs` | 上一項中「有搶到」的子集合,也就是 outbox 發佈 → RabbitMQ → consumer → 訂單資料列這條非同步路徑的成本。 |

每個買家都是在量測開始前就用 `prepare.js` 建立好的獨立帳號,註冊與登入(兩者都受 BCrypt
牽制)都在量測範圍外,所以認證流量不會混進上面的數字。

### 三個必須知道的量測偏差

這三點會實際影響怎麼解讀數字,不是免責聲明:

1. **壓力直接打 backend,不經過 Nginx。** 這些數字**不包含** TLS 終止、Nginx 反向代理與
   Nginx 限流的成本。正式的對外入口是 Nginx 的 `8443`,而 Nginx 對搶購請求設有以 client IP
   為單位的限流(`purchase_limit`,50r/s、burst 100)。單機壓測工具在 300 VU 情境下同時送出
   請求時,量到的主要會是那個限流器在丟棄流量,而不是應用程式的行為,所以壓測路徑刻意排除了
   Nginx(連同 TLS 與 frontend)。要走完整端到端路徑的腳本是
   [`load-tests/purchase-flow.js`](../../load-tests/purchase-flow.js)。
   **只讀這份報告的人請記得:真實用戶端看到的延遲會比這裡高,高多少沒有量。**
2. **`completedLatencyMs` 與 `orderCreatedLatencyMs` 有最多 +250ms 的量測偏差。** 壓測腳本
   是以固定 250ms 的間隔輪詢終態(`purchase-load.js` / `soak.js` 的 `POLL_INTERVAL_MS`),
   所以「實際完成」與「觀察到完成」之間平均會多出約 125ms、最多 250ms。這兩個指標量的是
   *可觀察到*的完成時間,不是系統內部的完成時間。`acceptedLatencyMs` 不受這個偏差影響。
3. **Actuator 指標是累計值,不是單次執行的值。** backend 在 16 次執行之間沒有重啟,所以
   `runs[n].actuator` 裡的 `COUNT` 是從第一次執行累加到第 n 次的總數。要看單次執行的筆數請用
   `runs[n].outcomes.*` 或 `runs[n].invariants.*`。

## 競爭情境:所有人同時湧入

每個情境都是 N 個買家在同一瞬間各送出一次搶購請求,搶 M 件庫存;每個情境跑五次,五次全部
列出,沒有修剪離群值。

### 30 個買家搶 10 件(`runs[0]`–`runs[4]`)

| 執行 | accepted med | accepted p95 | accepted min | accepted max | completed med | completed p95 | completed max | req/s |
|---|---|---|---|---|---|---|---|---|
| `contention-30x10-1` | 156.2 | 174.9 | 145.4 | 176.5 | 171.5 | 682.0 | 684.0 | 70.9 |
| `contention-30x10-2` | 37.8 | 51.3 | 22.5 | 53.5 | 52.5 | 293.0 | 293.0 | 131.5 |
| `contention-30x10-3` | 37.0 | 44.8 | 28.5 | 44.8 | 47.0 | 551.6 | 552.0 | 84.0 |
| `contention-30x10-4` | 36.0 | 45.0 | 25.0 | 45.0 | 46.0 | 541.0 | 541.0 | 89.4 |
| `contention-30x10-5` | 31.2 | 38.7 | 23.0 | 39.0 | 39.0 | 538.6 | 540.0 | 78.9 |
| **五次中位數** | **37.0** | **45.0** | **25.0** | **45.0** | **47.0** | **541.0** | **541.0** | **84.0** |

五次都是 10 筆成功、20 筆 `SOLD_OUT`、0 筆 5xx(`runs[0..4].outcomes`)。
第一次執行明顯偏慢,見下方[離群值](#離群值與資料品質)。

### 100 個買家搶 30 件(`runs[5]`–`runs[9]`)

| 執行 | accepted med | accepted p95 | accepted min | accepted max | completed med | completed p95 | completed max | req/s |
|---|---|---|---|---|---|---|---|---|
| `contention-100x30-1` | 69.3 | 110.6 | 21.5 | 117.7 | 101.0 | 812.0 | 816.0 | 224.1 |
| `contention-100x30-2` | 54.5 | 80.7 | 14.9 | 84.4 | 74.0 | 549.0 | 551.0 | 273.2 |
| `contention-100x30-3` | 81.5 | 108.4 | 18.2 | 112.9 | 100.0 | 556.1 | 561.0 | 276.4 |
| `contention-100x30-4` | 51.9 | 80.9 | 15.7 | 85.9 | 71.5 | 545.0 | 547.0 | 261.7 |
| `contention-100x30-5` | 43.1 | 63.1 | 15.6 | 71.4 | 57.5 | 286.0 | 301.0 | 407.3 |
| **五次中位數** | **54.5** | **80.9** | **15.7** | **85.9** | **74.0** | **549.0** | **551.0** | **273.2** |

五次都是 30 筆成功、70 筆 `SOLD_OUT`、0 筆 5xx。

### 300 個買家搶 100 件(`runs[10]`–`runs[14]`)

**這五次執行的數字有已知污染,請先讀[離群值](#離群值與資料品質)再看這張表。**

| 執行 | accepted med | accepted p95 | accepted max | completed med | completed p95 | completed max | req/s | 送達 | 連線被拒 |
|---|---|---|---|---|---|---|---|---|---|
| `contention-300x100-1` | 66.5 | 175.8 | 190.4 | 185.5 | 1373.0 | 1635.0 | 400.2 | 210 | 90 |
| `contention-300x100-2` | 61.2 | 137.5 | 151.9 | 149.0 | 1101.6 | 1107.0 | 474.1 | 210 | 90 |
| `contention-300x100-3` | 57.0 | 136.7 | 147.7 | 147.0 | 1091.9 | 1099.0 | 534.4 | 212 | 88 |
| `contention-300x100-4` | 82.8 | 181.1 | 205.8 | 198.0 | 1124.7 | 1389.0 | 440.7 | 214 | 86 |
| `contention-300x100-5` | 55.4 | 130.0 | 142.0 | 139.0 | 1348.0 | 1358.0 | 492.6 | 212 | 88 |
| **五次中位數** | **61.2** | **137.5** | **151.9** | **149.0** | **1124.7** | **1358.0** | **474.1** | **212** | **88** |

「送達」是 `runs[n].outcomes.accepted`,「連線被拒」是 `runs[n].outcomes.failed`。五次都是
100 筆成功、0 筆 5xx。

## Soak:穩態十分鐘(`runs[15]`)

情境設定原封不動寫進結果文件並由驗證器複查(`runs[15].scenario`):`constant-arrival-rate`、
`rate: 10`、`timeUnit: '1s'`、`duration: '10m'`、`preAllocatedVUs: 50`、`maxVUs: 200`,
庫存 6,000 件,6,000 個不重複買家(`runs[15].users`)。

| 指標 | min | med | p90 | p95 | max | avg |
|---|---|---|---|---|---|---|
| accepted | 0.5 | 7.6 | 11.8 | 12.6 | 35.0 | 8.1 |
| completed | 254.0 | 511.0 | 520.0 | 523.0 | 784.0 | 405.2 |
| orderCreated | 254.0 | 511.0 | 520.0 | 523.0 | 784.0 | 405.2 |

其他觀測值:

- 6,001 次 iteration(`runs[15].metrics.iterations`),15,369 次 HTTP 請求
  (`runs[15].metrics.httpRequests`),平均 25.6 req/s(`runs[15].metrics.requestsPerSecond`,
  已包含輪詢請求)。
- 6,000 筆成功、0 筆 `SOLD_OUT`、0 筆 5xx、0 筆輪詢逾時(`runs[15].outcomes`)。庫存足夠,
  所以每個買家都買得到,這一段量的是穩態延遲而不是競爭。
- 因為所有人都搶得到,`completedLatencyMs` 與 `orderCreatedLatencyMs` 的樣本集合相同,
  所以兩列數字一致。

**關於「有沒有隨時間劣化」:** 這套工具只記錄整段期間的彙總統計,沒有留下分時間區間的
時序資料,所以這裡無法畫出趨勢線。能說的是分佈很窄——中位數 511ms、p95 523ms、最大值
784ms,p95 只比中位數高 12ms——如果延遲在十分鐘內持續往上漂,尾端不會這麼貼近中位數。
這是從分佈形狀做的推論,不是直接觀察到的時序證據。同樣地,6,001 次 iteration 全部完成,
沒有任何一次被中斷。

## 離群值與資料品質

驗證器只會用正確性不變量判定一次執行合格與否,所以「合格」不等於「乾淨」。以下三件事是逐筆
檢查 `outcomes`、`queues` 與不變量之後自己挑出來的,即使驗證器把這三次執行都判為 `valid`:

### 1. 第一次執行是暖機離群值(`runs[0]`)

`contention-30x10-1` 的 accepted 中位數是 156.2ms,同情境其餘四次是 31.2–37.8ms,大約差
四到五倍;completed p95 682ms 也是五次裡最高。這一次是 backend 啟動後第一個被量測的情境
(`environment.startedAt` 12:16:45Z、`runs[0].startedAt` 12:17:12Z,相隔 27 秒)。
最可能的原因是 JVM 尚未 JIT 暖機與各種連線池尚未建立——**這是推論,沒有額外證據佐證**。
這一次執行照樣留在資料裡並計入上表的中位數,沒有被剔除。若只看第 2–5 次,accepted 中位數的
中位數是 36.5ms。

### 2. 300 VU 情境有約三成的請求根本沒送達(`runs[10]`–`runs[14]`)

每次執行有 86–90 筆(`runs[n].outcomes.failed`,五次中位數 88)請求沒有拿到任何 HTTP 回應。
從壓測當下的 k6 主控台輸出可以看到這些請求的失敗原因是
`dial tcp 127.0.0.1:18080: connectex: No connection could be made because the target machine actively refused it.`
——也就是 TCP 連線在建立階段就被拒絕(`ECONNREFUSED`),不是應用程式回了錯誤碼。
佐證:`runs[n].outcomes.unexpected5xx` 全部為 `0`,而這些請求在 k6 的紀錄裡狀態碼是 `0`。
(失敗筆數本身在 JSON 裡;失敗原因的字串來自該次執行的主控台輸出,不在結果文件中。)

這件事有兩個後果:

- **這不是「300 個買家的行為」,而是「約 212 個買家的行為」。** 上表的 accepted / completed
  延遲只涵蓋真正建立連線的那些請求。
- **`acceptedLatencyMs.min` 在這五次都是 `0`**,因為連線失敗的樣本以 0ms 記進了同一條
  trend。這代表 300 VU 那張表的 accepted `min`、`avg` 與 `med` 都被往下拉;
  `p90`/`p95`/`max` 受影響較小。**要比較不同規模的 accepted 延遲時,請用 300 VU 的 p95,
  不要用它的中位數。**

被拒絕的是誰,沒有直接證據。兩個尚未驗證的候選:Windows 上 Docker Desktop 的 port proxy
在瞬間 300 條新連線下的 accept backlog,或是 Tomcat 的預設 listen backlog
(專案沒有調整任何 `server.tomcat.*` 設定,所以套用 Spring Boot 預設的
`threads.max=200`、`accept-count=100`)。兩者都會表現成連線建立階段的 `ECONNREFUSED`。
**要分辨是哪一個需要另外設計實驗,這次沒有做。**

### 3. soak 有一次 iteration 沒有可用的帳號(`runs[15]`)

`runs[15].outcomes.missingToken` 是 `1`。原因是 `constant-arrival-rate` 實際跑出 6,001 次
iteration(`runs[15].metrics.iterations`),比預先準備的 6,000 個帳號多一次,第 6,001 次
找不到對應帳號就直接跳過,沒有送出請求。這是壓測工具本身的邊界問題,不是系統行為;
影響是 6,001 分之 1。

### 佇列深度:16 次執行全部為零

每次執行結束後都會擷取四條佇列的深度(`runs[n].queues`),包含兩條 DLQ。**16 次執行、
每次 4 條佇列,`messages`、`messagesReady`、`messagesUnacknowledged` 全部是 `0`。**
沒有任何訊息進過死信佇列,也沒有訊息堆積。同樣地,
`runs[n].invariants.unpublishedOutboxEvents` 在 16 次執行中都是 `0`,代表 outbox 每次都
完整發佈完畢。`runs[n].health` 的 `readiness`、`liveness`、`overall` 在 16 次執行中都是 `UP`。

## 吞吐量

`runs[n].metrics.requestsPerSecond` 是 k6 對該次執行所有 HTTP 請求(搶購 POST 加上狀態
輪詢 GET)算出來的速率,分母是該次執行的實際持續時間。競爭情境很短,所以這個值反映的是
瞬間尖峰而不是可持續速率:

| 情境 | req/s 五次中位數 | 五次原始值 |
|---|---|---|
| 30 買家 / 10 件 | 84.0 | 70.9、131.5、84.0、89.4、78.9 |
| 100 買家 / 30 件 | 273.2 | 224.1、273.2、276.4、261.7、407.3 |
| 300 買家 / 100 件 ⚠️ 見下方說明 | 474.1 | 400.2、474.1、534.4、440.7、492.6 |

⚠️ **300 VU 這一列同時有兩個問題,不能當成「300 個買家打出 474.1 req/s」來讀**
(背景見[離群值](#離群值與資料品質)第 2 點):

1. **有效買家只有約 212 個,不是 300 個。** 每次執行有 86–90 筆請求在建立 TCP 連線階段就被
   拒絕,從來沒有抵達應用程式(`runs[10..14].outcomes.failed` 為 90、90、88、86、88)。
2. **這一列的 req/s 本身是被灌水的。** `requestsPerSecond` 是從 `httpRequests` 算出來的,
   而 `httpRequests`(五次分別為 666、537、600、633、683)**把那些被拒絕的連線也算成了請求**
   ——五次執行的 `outcomes.accepted + outcomes.failed` 都恰好等於 300,也就是 300 次 POST
   嘗試裡有 86–90 次其實沒有跟 backend 完成任何往返。這些失敗的連線幾乎不花時間就結束
   (它們正是把 `runs[10..14].metrics.acceptedLatencyMs.min` 壓成 `0` 的那批樣本),卻照樣
   進了分子,所以**這個數字比 backend 實際服務掉的請求速率要高**,高多少沒有換算。

soak 的 25.6 req/s 不能跟上面比較:那是刻意固定在 10 次搶購/秒的到達率下,加上輪詢流量之後
的結果,是設定值而不是量到的上限。**這次壓測沒有做飽和測試,所以沒有任何一個數字可以拿來
當作系統的吞吐量上限。**

## 瓶頸觀察

**同步那一段很便宜,非同步那一段由兩個輪詢間隔主導。**

在 soak 的穩態下,accepted 中位數是 7.6ms,而 completed 中位數是 511ms——同一批請求,兩者
差了大約 500ms。這個差距的組成可以直接對上兩個設定常數:

- `OutboxPublisher` 以 `@Scheduled(fixedDelay = 500)` 撈未發佈事件(見
  [工程取捨](./trade-offs.md#transactional-outbox)),所以一筆事件平均要等約 250ms 才會被送進
  RabbitMQ,最多 500ms。
- 壓測腳本每 250ms 輪詢一次終態,所以平均再多約 125ms 才會「看到」完成。

兩者相加約 375ms,加上 accepted 的平均 8.1ms(`runs[15].metrics.acceptedLatencyMs.avg`),
約 383ms,與實際量到的 completed 平均 405.2ms
(`runs[15].metrics.completedLatencyMs.avg`)同一個量級。觀察到的最小值 254ms
(`runs[15].metrics.completedLatencyMs.min`)也符合「outbox 幾乎沒等到 + 一個輪詢週期」的
下限。

這代表**在這些負載下,搶購完成延遲的主要成分是可設定的輪詢間隔,不是資料庫、Redis 或
RabbitMQ 的處理能力**。要縮短它,調 `fixedDelay` 或改用 CDC 會比擴充硬體有效
(取捨見[工程取捨](./trade-offs.md#transactional-outbox));但同時也要記得,量測本身的
250ms 輪詢是壓測工具的產物,真實用戶端可以用不同的輪詢策略。

至於 Redis 預扣本身,`runs[15].actuator["purchase.reservation.latency"]` 顯示 soak 結束時
累計 7,708 次預扣、總耗時 9.23 秒(平均約 1.2ms/次),單次最大值 0.0027 秒。
預扣不是瓶頸。(這是累計值,見[量測方法](#三個必須知道的量測偏差)第 3 點。)

在 300 VU 情境下,最先撐不住的不是應用程式,而是**連線建立**——見上方離群值第 2 點。
在那之前,應用程式沒有回過任何一個 5xx。

## 正確性

延遲可以慢,但不變量不能破。16 次執行的每一次都擷取了資料庫狀態,以下全部成立:

| 不變量 | 結果 | JSON 路徑 |
|---|---|---|
| 沒有超賣 | 每次執行的訂單數都等於灌入的庫存(10 / 30 / 100 / 6000),沒有一次超過 | `runs[n].invariants.ordersCreated` vs `runs[n].stock` |
| 沒有重複下單 | 16 次都是 `0` | `runs[n].invariants.duplicateOrderUsers` |
| 沒有重複成功 | 16 次都是 `0` | `runs[n].invariants.duplicateSucceededUsers` |
| 沒有卡住的請求 | 16 次都是 `0` | `runs[n].invariants.residualPending` |
| 沒有原始 5xx | 16 次都是 `0` | `runs[n].outcomes.unexpected5xx` |
| 沒有輪詢逾時 | 16 次都是 `0` | `runs[n].outcomes.pendingTimeout` |
| 沒有沒有明細的訂單 | 16 次都是 `0` | `runs[n].invariants.ordersWithoutItems` |
| outbox 全部發佈完成 | 16 次都是 `0` | `runs[n].invariants.unpublishedOutboxEvents` |
| DLQ 全空 | 16 次 × 2 條 DLQ 都是 `0` | `runs[n].queues[]` |

### 庫存守恆的人工複查

除了上表,還逐筆核對了 `available + reserved + sold` 是否等於該次執行灌入的庫存
(`runs[n].invariants.inventory` 對 `runs[n].stock`)。**16 次執行全部守恆**,例如
`runs[15]`(soak)是 `available=0`、`reserved=0`、`sold=6000`,合計 6,000,等於灌入的 6,000 件。
這一項目前的驗證器並沒有檢查,是額外做的複查。

在拆掉壓測環境之前,也直接連進隔離的資料庫再查了一次 soak 結束時的狀態,結果與結果文件一致:
庫存 `6000 / 0 / 0 / 6000`(total / available / reserved / sold)且守恆為真;
`orders` 6,000 筆、來自 6,000 個不重複使用者、每人最多 1 筆;`order_items` 6,000 筆;
`purchase_requests` 全部 6,000 筆都是 `SUCCEEDED`;`outbox_events` 6,000 筆且未發佈者為 0;
`consumed_messages` 6,000 筆(消費端去重表與訂單數一致,代表沒有重複消費)。
同時 Redis 的 `stock:1` 為 `0`,與 Postgres 的 `sold=6000` 一致,四條 RabbitMQ 佇列
(含兩條 DLQ)深度皆為 0。

### 兩套獨立計數互相對帳

壓測工具自己數的筆數,和 backend 自己的 Micrometer 指標可以對上:

- 15 次競爭情境的 `outcomes.accepted` 加總為 150 + 500 + 1,058 = **1,708**,
  等於 `runs[14].actuator["purchase.reservation"].COUNT` 的 **1,708**。
- 同樣 15 次的訂單數加總為 50 + 150 + 500 = **700**,
  等於 `runs[14].actuator["purchase.order.created"].COUNT` 的 **700**。
- soak 之後兩者分別變成 **7,708**(= 1,708 + 6,000)與 **6,700**(= 700 + 6,000),
  對應 `runs[15].actuator`。

k6 在外面數的與 backend 在裡面數的完全一致,沒有請求被默默吞掉,也沒有訂單被重複建立。

## 這些數字的適用邊界

除了[量測方法](#三個必須知道的量測偏差)裡的三個偏差,以下限制同樣適用,與
[工程取捨](./trade-offs.md#已知限制)裡寫的一致:

- **不是正式環境。** 單機 Docker Compose,沒有水平擴充、沒有滾動更新、沒有自動修復、
  沒有高可用。所有「多實例才會遇到」的問題(排程重複執行、節點層級限流)在這個交付形式下
  無法驗證,也就沒有被量到。
- **對外入口是自簽 TLS。** Nginx 用自簽憑證在 `8443` 終止 TLS,沒有正式憑證鏈、自動續期、
  HSTS 或 OCSP stapling;而如前所述,壓測根本沒有走這條路徑,所以連自簽 TLS 的成本都不在
  數字裡。
- **只有一次收集。** 每個競爭情境有五次執行,但整份資料只來自單一一次 session、單一台機器、
  單一組 Docker 資源設定。換一台機器就會是完全不同的數字。
- **沒有量到飽和點。** 沒有做遞增負載直到系統崩潰的測試,所以無法回答「這套系統能撐多少」。
- **沒有時序資料。** 只有整段期間的彙總統計,沒有分時間區間的時序,所以無法分析尖峰內的
  延遲分佈變化。
- **同機還跑著另一套 Compose 專案**,CPU 與 I/O 是共用的,影響未量化。

## 重現這次量測

```powershell
powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode full
```

工具會啟動隔離的 `flashsale-benchmark` 專案、驗證隔離、依序跑完 15 次競爭情境與 soak、
擷取不變量,最後產生一份結果文件並自動驗證。任何時候都可以重新驗證這份文件:

```powershell
node load-tests/benchmark/verify-results.mjs docs/portfolio/data/benchmark-results.json
```

前置需求、隔離機制與失敗執行如何被保留,見
[壓測工具說明](../../load-tests/benchmark/README.md)。
