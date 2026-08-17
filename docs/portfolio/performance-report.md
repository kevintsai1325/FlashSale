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
| Git commit | `c943756cd391d337111ec1488ef5d152efeea8a7` | `environment.gitSha` |
| Git 分支 | `main` | `environment.gitBranch` |
| 工作區是否有未提交變更 | 是 | `environment.gitDirty` |
| 作業系統 | Microsoft Windows 11 專業版 10.0.26200 | `environment.os` |
| CPU | 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz,16 個邏輯核心 | `environment.cpu` |
| 記憶體 | 31.7 GB | `environment.memoryGb` |
| Docker Engine | 29.7.2 | `environment.dockerVersion` |
| Docker Compose | 5.3.1 | `environment.dockerComposeVersion` |
| k6 | v2.2.0 (go1.26.5, windows/amd64) | `environment.k6Version` |
| Compose 專案 | `flashsale-benchmark` | `environment.composeProject` |
| 壓力進入點 | `http://127.0.0.1:18080` | `environment.backendBaseUrl` |
| 開始 / 結束 | 2026-08-17T02:21:25Z / 2026-08-17T02:38:13Z(UTC) | `environment.startedAt`、`environment.finishedAt` |

共 16 次執行,全部完成,沒有任何一次失敗(`summary.expectedRuns` 為 `16`、
`summary.failedRuns` 為 `0`,`runs` 陣列長度為 16)。

**這次收集換了一台機器**,CPU 型號與核心數跟最早一版收集不同,所以不能把這份數字拿去跟更早的
版本逐項比對速度快慢——兩者本來就是不同硬體。

**工作區有未提交變更:** `gitDirty` 是 `是`。這份數字量的是四個尚未提交的改動一起生效之後的
行為,commit `c943756` 本身的原始碼並不包含它們:

- `server.tomcat.accept-count` / `threads.max`(見下方[離群值](#離群值與資料品質)第 2 點)
- `load-tests/benchmark/collect.ps1` 新增的 warm-up 階段(見第 1 點)
- `spring.datasource.hikari.maximum-pool-size`(10 → 30)
- `OutboxPublisher.BATCH_SIZE`(50 → 200)與 `spring.rabbitmq.listener.simple.concurrency`(1 → 5)

後兩項是排查 300 VU 為什麼「明明只有 300 人卻要等超過 1 秒」時加的,細節見
[瓶頸觀察](#瓶頸觀察)與[離群值](#離群值與資料品質)第 4 點。

**收集這份資料時沒有同機干擾。** 早期收集時這台機器上同時跑著一般的 `flashsale` Compose 專案
(8 個服務,閒置但在執行中),CPU 與 I/O 是共用的。這次收集前先把那套停掉了,所以這份數字沒有
那層干擾——這也是下面能拿到「300 VU 五次全部零失敗」這種乾淨結果的原因之一,細節見離群值
第 2 點。

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
| `contention-30x10-1` | 55.8 | 58.4 | 42.9 | 58.4 | 61.0 | 575.0 | 575.0 | 82.7 |
| `contention-30x10-2` | 56.0 | 62.7 | 46.2 | 63.9 | 61.5 | 581.0 | 581.0 | 81.5 |
| `contention-30x10-3` | 44.9 | 47.9 | 39.0 | 49.0 | 48.0 | 304.7 | 306.0 | 119.9 |
| `contention-30x10-4` | 52.8 | 66.7 | 45.4 | 68.4 | 56.0 | 580.0 | 582.0 | 81.6 |
| `contention-30x10-5` | 41.9 | 42.9 | 27.8 | 42.9 | 45.0 | 294.0 | 300.0 | 121.3 |
| **五次中位數** | **52.8** | **58.4** | **42.9** | **58.4** | **56.0** | **575.0** | **575.0** | **82.7** |

五次都是 10 筆成功、20 筆 `SOLD_OUT`、0 筆 5xx(`runs[0..4].outcomes`)。這次收集在正式量測前
加了 warm-up(見下方[離群值](#離群值與資料品質)第 1 點),第一次執行不再是 4-5 倍的離群值。

### 100 個買家搶 30 件(`runs[5]`–`runs[9]`)

| 執行 | accepted med | accepted p95 | accepted min | accepted max | completed med | completed p95 | completed max | req/s |
|---|---|---|---|---|---|---|---|---|
| `contention-100x30-1` | 74.5 | 109.6 | 31.8 | 120.6 | 104.5 | 829.0 | 837.0 | 209.6 |
| `contention-100x30-2` | 75.8 | 104.1 | 27.9 | 107.7 | 97.0 | 321.1 | 581.0 | 214.2 |
| `contention-100x30-3` | 57.4 | 80.1 | 33.5 | 85.2 | 83.0 | 821.0 | 834.0 | 191.5 |
| `contention-100x30-4` | 81.3 | 111.8 | 33.4 | 118.1 | 108.0 | 317.0 | 323.0 | 353.7 |
| `contention-100x30-5` | 64.1 | 83.3 | 33.1 | 85.9 | 80.0 | 557.0 | 569.0 | 251.9 |
| **五次中位數** | **74.5** | **104.1** | **33.1** | **107.7** | **97.0** | **557.0** | **581.0** | **214.2** |

五次都是 30 筆成功、70 筆 `SOLD_OUT`、0 筆 5xx。

### 300 個買家搶 100 件(`runs[10]`–`runs[14]`)

這五次執行的請求**全部送達**,沒有連線被拒(見下方[離群值](#離群值與資料品質)第 2 點——
這是調過三次才拿到的乾淨結果,過程並不平順)。

| 執行 | accepted med | accepted p95 | accepted min | accepted max | completed med | completed p95 | completed max | req/s |
|---|---|---|---|---|---|---|---|---|
| `contention-300x100-1` | 118.9 | 188.1 | 40.0 | 240.4 | 201.5 | 1145.0 | 1164.0 | 522.8 |
| `contention-300x100-2` | 124.3 | 183.9 | 33.9 | 229.7 | 197.5 | 867.1 | 912.0 | 540.9 |
| `contention-300x100-3` | 143.9 | 215.2 | 43.8 | 231.9 | 234.0 | 929.1 | 968.0 | 540.9 |
| `contention-300x100-4` | 131.6 | 188.0 | 32.0 | 232.8 | 185.5 | 1126.0 | 1143.0 | 479.7 |
| `contention-300x100-5` | 210.8 | 275.7 | 37.0 | 332.3 | 268.5 | 954.0 | 974.0 | 557.6 |
| **五次中位數** | **131.6** | **188.1** | **37.0** | **232.8** | **201.5** | **954.0** | **974.0** | **540.9** |

五次都是 100 筆成功、200 筆 `SOLD_OUT`、0 筆 5xx、300 筆 `accepted`(`runs[10..14].outcomes`)。
`contention-300x100-5` 明顯比其餘四次慢(accepted 中位數 210.8ms vs 118.9–143.9ms),原因
沒有查——300 個真實連線同時打進來時,單次執行之間的變異本來就比 30/100 VU 情境大,這裡沒有
再往下拆解去分辨是隨機變異還是有系統性原因。

## Soak:穩態十分鐘(`runs[15]`)

情境設定原封不動寫進結果文件並由驗證器複查(`runs[15].scenario`):`constant-arrival-rate`、
`rate: 10`、`timeUnit: '1s'`、`duration: '10m'`、`preAllocatedVUs: 50`、`maxVUs: 200`,
庫存 6,000 件,6,000 個不重複買家(`runs[15].users`)。

| 指標 | min | med | p90 | p95 | max | avg |
|---|---|---|---|---|---|---|
| accepted | 6.3 | 9.5 | 11.3 | 12.3 | 130.0 | 9.8 |
| completed | 259.0 | 515.0 | 521.0 | 769.0 | 882.0 | 432.7 |
| orderCreated | 259.0 | 515.0 | 521.0 | 769.0 | 882.0 | 432.7 |

其他觀測值:

- 6,001 次 iteration(`runs[15].metrics.iterations`),16,003 次 HTTP 請求
  (`runs[15].metrics.httpRequests`),平均 26.6 req/s(`runs[15].metrics.requestsPerSecond`,
  已包含輪詢請求)。
- 6,000 筆成功、0 筆 `SOLD_OUT`、0 筆 5xx、0 筆輪詢逾時(`runs[15].outcomes`)。庫存足夠,
  所以每個買家都買得到,這一段量的是穩態延遲而不是競爭。
- 因為所有人都搶得到,`completedLatencyMs` 與 `orderCreatedLatencyMs` 的樣本集合相同,
  所以兩列數字一致。

**關於「有沒有隨時間劣化」:** 這套工具只記錄整段期間的彙總統計,沒有留下分時間區間的
時序資料,所以這裡無法畫出趨勢線。把 Hikari 連線池從預設 10 調到 30 之後,這裡的極端離群值
明顯收斂了——accepted max 從沒調之前的 1411ms 降到 130ms,completed max 從 2173ms 降到
882ms,這個方向支持「連線池不夠、偶發排隊」是原本那個離群值的成因。但**沒有完全消失**:
completed 的 p95(769ms)幾乎沒動(沒調之前是 768ms),代表還是有一小撮 iteration 比中位數
慢了一大截,只是最壞情況不再那麼極端。細節與候選原因見下方[離群值](#離群值與資料品質)第 4 點。
同樣地,6,001 次 iteration 全部完成,沒有任何一次被中斷或逾時。

## 離群值與資料品質

驗證器只會用正確性不變量判定一次執行合格與否,所以「合格」不等於「乾淨」。以下四件事是逐筆
檢查 `outcomes`、`queues` 與不變量之後自己挑出來的,即使驗證器把每次執行都判為 `valid`。
前兩件是上一版收集時發現、這次已經處理掉的問題,後兩件是這次仍然存在或新出現的:

### 1. 第一次執行的暖機離群值,這次靠 warm-up 消掉了

上一版收集時 `contention-30x10-1` 的 accepted 中位數是 156.2ms,同情境其餘四次是
31.2–37.8ms,差四到五倍,推論是 JVM 尚未 JIT 暖機、連線池尚未建立。這次收集在
`collect.ps1` 裡加了兩次 warm-up run(20 VU 搶 5 件,`warmup-1`、`warmup-2`,結果**不進
`results.json`**),跑在 `Wait-BackendReady` 之後、第一個正式情境之前。

warm-up 的結果印證了原本的推論:`warmup-1` 的 accepted 中位數是 203.5ms(冷啟動代價全部
被它吸收),`warmup-2` 已經降到 42.9ms。緊接著的正式 `contention-30x10-1` 是 55.8ms,
跟同組其餘四次(41.9–56.0ms)同一個量級,不再是離群值。

### 2. 300 VU 情境的連線被拒絕,過程反覆試了三輪才穩定

最早一版收集時每次執行有 86–90 筆請求在 TCP 連線建立階段就被拒絕(`ECONNREFUSED`),
懷疑是 Spring Boot Tomcat 預設的 `accept-count=100` 撐不住 300 條瞬間新連線。中間試了三輪:

1. **把 `accept-count` 調到 300、`threads.max` 調到 400。** 5 次裡有 4 次乾淨,但有 1 次
   又出現 68 筆連線被拒——不是完全解決,只是機率降低。
2. **把 `accept-count` 再加大到 512(給更多餘裕)。** 結果**更差**:5 次裡有 2 次連線被拒
   (79、87 筆),soak 的尾端延遲也變得更不穩。這代表瓶頸不是(或不只是)Tomcat 自己的
   accept backlog——加大它沒有帶來線性的改善,懷疑是 Windows 上 Docker Desktop 的 port
   proxy 這一層有自己的佇列上限,跟 Tomcat 的設定是兩回事,單靠調 Tomcat 這邊碰不到它。
3. **退回 `accept-count: 300`,同時把同機閒置的 `flashsale` Compose 專案停掉,再重跑。**
   這次 5 次全部乾淨(見上表)。

| | 最早一版(預設值) | 調到 300(有同機干擾) | 調到 512(有同機干擾) | 這份報告(300,無同機干擾) |
|---|---|---|---|---|
| TCP 連線被拒的執行次數 | 5/5 | 1/5(68 筆) | 2/5(79、87 筆) | **0/5** |

**結論比原本想的複雜:** 這個問題看起來同時受 Tomcat backlog 大小**和**同機資源競爭影響,
把兩者都排除才拿到乾淨結果,單獨調大 `accept-count` 沒有可靠地解決它。也就是說,`accept-count`
從 100 調到 300 這個動作本身有沒有必要、還是只是同機干擾恰好那幾次沒踩到,**沒有做隔離實驗
分開驗證**——這次的乾淨結果不能簡單歸功於任何一個單一改動。

`acceptedLatencyMs.min` 這份報告的 300 VU 五次都不再是 0ms(這次落在 32.0–43.8ms 之間,見
上表),所以中位數可以直接跟 30/100 VU 比。副作用是**accepted 延遲本身比最早一版還高**——
最早一版中位數 61.2ms(混了一堆 0ms 的失敗樣本,是失真的),這次是 131.6ms,量到的才是 300
個真實連線同時打進應用程式時的實際同步延遲,細節見下方[瓶頸觀察](#瓶頸觀察)。

### 3. soak 有一次 iteration 沒有可用的帳號(`runs[15]`)

`runs[15].outcomes.missingToken` 是 `1`。原因是 `constant-arrival-rate` 實際跑出 6,001 次
iteration(`runs[15].metrics.iterations`),比預先準備的 6,000 個帳號多一次,第 6,001 次
找不到對應帳號就直接跳過,沒有送出請求。這是壓測工具本身的邊界問題,不是系統行為;
影響是 6,001 分之 1。

### 4. soak 的尾端延遲離群值,靠加大 Hikari pool 減輕了大半,但沒有完全消失

這個離群值第一次出現在只調了 Tomcat 設定的那一版:accepted `max` 衝到 1411.1ms(比對照組
的 35.0ms 高了 40 倍),completed `max` 到 2173ms(對照組 784ms)。懷疑是 `spring.datasource.
hikari.maximum-pool-size` 一直沿用 Spring Boot 預設值 10,300 VU 情境把連線池打滿之後,
soak 接著跑時偶爾還沒完全恢復。把它調到 30 之後(細節見[瓶頸觀察](#瓶頸觀察)),這份報告的
soak accepted `max` 降到 130.0ms、completed `max` 降到 882ms——極端值大幅收斂,支持原本的
推論方向。

但**沒有完全消失**,而且這個部分似乎跟 Hikari pool 大小無關:三個版本的 completed p95
幾乎是同一個數字——最早未調整版 523ms、只調 Tomcat 那版 768ms、這份加大 Hikari pool 之後的
版本 769ms。accepted 的 p95 三版也都貼在 12-13ms(12.6 / 12.8 / 12.3ms),p90 也都貼在
520ms 上下,代表不是全面變慢,而是極少數 iteration(遠少於 6,000 筆的 5%,否則會反映在
p95 上)被卡住,而且這個「極少數被卡住」的比例,加大連線池並沒有讓它變少——變小的只有
「卡住的時候最多卡多久」(max 從 2173ms 降到 882ms),不是「多常卡住」。

沒有查出原因。跟最早那版比,這次收集有兩個已知差異,都可能相關但都沒驗證:soak 是接在 15 次
競爭情境(含 5 次 300 VU 重負載)之後跑的,backend 這段期間沒有重新啟動;`server.tomcat.
threads.max` 這次是 400,是最早版本預設值 200 的兩倍。GC 暫停、連線池狀態、或單純是換了一台
機器的雜訊,都是候選,但沒有一個被證實。**要分辨原因需要另外設計實驗(例如 soak 前重啟
backend、或關掉 GC log 佐證),這次沒有做。**

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
| 30 買家 / 10 件 | 82.7 | 82.7、81.5、119.9、81.6、121.3 |
| 100 買家 / 30 件 | 214.2 | 209.6、214.2、191.5、353.7、251.9 |
| 300 買家 / 100 件 | 540.9 | 522.8、540.9、540.9、479.7、557.6 |

300 買家這一列是乾淨的:`outcomes.accepted + outcomes.failed` 五次都是 `300 + 0`,
`httpRequests` 全部來自真正跟 backend 完成往返的請求,沒有把被拒絕的連線算進分子
(對照見[離群值](#離群值與資料品質)第 2 點)。

soak 的 26.6 req/s 不能跟上面比較:那是刻意固定在 10 次搶購/秒的到達率下,加上輪詢流量之後
的結果,是設定值而不是量到的上限。**這次壓測沒有做飽和測試,所以沒有任何一個數字可以拿來
當作系統的吞吐量上限。**

## 瓶頸觀察

**同步那一段很便宜,非同步那一段由兩個輪詢間隔主導——但 300 VU 情境下兩段都各自有自己的瓶頸,
這次針對性地調了三個設定去驗證。**

### 穩態下的延遲組成

在 soak 的穩態下,accepted 中位數是 9.5ms,而 completed 中位數是 515ms——同一批請求,兩者
差了大約 505ms。這個差距的組成可以直接對上兩個設定常數:

- `OutboxPublisher` 以 `@Scheduled(fixedDelay = 500)` 撈未發佈事件(見
  [工程取捨](./trade-offs.md#transactional-outbox)),所以一筆事件平均要等約 250ms 才會被送進
  RabbitMQ,最多 500ms。
- 壓測腳本每 250ms 輪詢一次終態,所以平均再多約 125ms 才會「看到」完成。

兩者相加約 375ms,加上 accepted 的平均 9.8ms(`runs[15].metrics.acceptedLatencyMs.avg`),
約 384.8ms,比實際量到的 completed 平均 432.7ms
(`runs[15].metrics.completedLatencyMs.avg`)低一些,主因是少數尾端離群值拉高了平均
(見上方[離群值](#離群值與資料品質)第 4 點),不是輪詢模型本身失準。觀察到的最小值 259ms
(`runs[15].metrics.completedLatencyMs.min`)仍然符合「outbox 幾乎沒等到 + 一個輪詢週期」的
下限,中位數與 p90 也還是緊貼這個模型。

這代表**在這些負載下,搶購完成延遲的主要成分是可設定的輪詢間隔,不是資料庫、Redis 或
RabbitMQ 的處理能力**。要縮短它,調 `fixedDelay` 或改用 CDC 會比擴充硬體有效
(取捨見[工程取捨](./trade-offs.md#transactional-outbox));但同時也要記得,量測本身的
250ms 輪詢是壓測工具的產物,真實用戶端可以用不同的輪詢策略。

至於 Redis 預扣本身,`runs[15].actuator["purchase.reservation.latency"]` 顯示 soak 結束時
累計 8,190 次預扣(含這次收集裡 warm-up 與 15 次競爭情境留下的累計基數)、總耗時 27.22 秒
(平均約 3.3ms/次),單次最大值 0.0023 秒。預扣不是瓶頸。(這是累計值,見
[量測方法](#三個必須知道的量測偏差)第 3 點。)

### 300 VU 為什麼特別慢:三個沒配置過的預設值

最早那版(只調 Tomcat)量到 300 VU 的 completed 中位數 197.5ms、p95 到 1110ms、accepted
中位數 131.3ms,遠比 30/100 VU 不成比例地慢。追查之後,發現三個地方全部套用 Spring Boot
的預設值,沒有人針對 300 併發的規模調過:

1. **HikariCP 連線池只有 10 條**(`spring.datasource.hikari.maximum-pool-size` 沒設定過,
   套用預設值 10)。`CreatePurchaseRequestService.createPurchaseRequest`
   (`backend/src/main/java/com/flashsale/order/application/CreatePurchaseRequestService.java:34-69`)
   整個方法在一個 `@Transactional` 裡,連呼叫 Redis Lua 預扣(第 58 行)都握著同一條 DB 連線,
   300 個併發請求只能排隊搶這 10 條連線。
2. **outbox 一批只發 50 筆**(`OutboxPublisher.BATCH_SIZE`,`OutboxPublisher.java:24`)搭配
   `fixedDelay = 500ms`。300 VU / 100 件庫存的情境有 100 筆成功訂單,超過一批的上限,保證要
   跑滿 2 輪 scheduler tick 才能全部發佈完,這正好解釋 completed p95/max 為什麼卡在
   1100ms 以上這個量級。
3. **RabbitMQ consumer 沒設定併發數**(`spring.rabbitmq.listener.simple.concurrency` 沒設定,
   `OrderPurchaseConsumer.java:53` 的 `@RabbitListener` 也沒指定),預設只有 1 條執行緒依序
   消費 `order.create.queue`,100 筆訂單建立要序列處理,而且同樣要搶前面那 10 條 DB 連線。

把 Hikari pool 開到 30、`BATCH_SIZE` 提到 200、consumer `concurrency` 開到 5 之後重跑:

| | 最早一版(全部預設值) | 這份報告(調整後) |
|---|---|---|
| 300 VU completed 中位數 | 197.5ms | 201.5ms(持平) |
| 300 VU completed p95 | 1110.0ms | **954.0ms** |
| 300 VU accepted 中位數 | 131.3ms | 131.6ms(**幾乎沒變**) |
| soak accepted max | 1411.1ms | **130.0ms** |
| soak completed max | 2173ms | **882ms** |

completed 的尾端(p95/max)跟 soak 的極端離群值改善明顯,支持「連線池/batch size 太小」是
其中一部分原因。但**300 VU 的 accepted 中位數幾乎沒變**——把連線池從 10 條開到 30 條(3 倍),
理論上排隊時間應該明顯縮短,結果卻幾乎持平。這代表 DB 連線池不是 accepted 延遲的唯一或主要
瓶頸,下一個候選是應用層本身的序列化:Redis Lua 對同一個 flash sale key 的原子預扣本來就是
逐筆序列化執行,或者單純是 300 個併發執行緒本身的 CPU/JSON 解析/JWT 驗證開銷,在共用核心的
筆電上已經逼近某個上限。這次沒有做進一步拆解量測(例如單獨量 Lua 執行時間 vs. 排隊等待時間、
或用 profiler 抓 CPU 熱點),無法確定是哪一個,或兩者都有。

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
這一項目前的驗證器並沒有檢查,是額外做的複查。這次收集用 `-KeepStack` 以外的預設流程跑完就
自動 `down -v` 拆掉了隔離環境,沒有像上一版那樣在拆掉之前額外連進資料庫手動查
`consumed_messages`、Redis key 等結果文件 schema 之外的表——這次的複查完全來自
`results.json` 裡已經擷取的欄位,沒有額外的即時查詢佐證。

### 兩套獨立計數互相對帳

壓測工具自己數的筆數,和 backend 自己的 Micrometer 指標可以對上:

- 15 次競爭情境的 `outcomes.accepted` 加總為 150 + 500 + 1,500 = **2,150**,加上這次收集在
  正式量測前跑的 2 次 warm-up(20 VU、結果不進 `results.json`,每次 accepted 20 筆)共 40 筆,
  等於 `runs[14].actuator["purchase.reservation"].COUNT` 的 **2,190**。
- 同樣 15 次的訂單數加總為 50 + 150 + 500 = **700**,加上 2 次 warm-up 各 5 筆訂單共 10 筆,
  等於 `runs[14].actuator["purchase.order.created"].COUNT` 的 **710**。
- soak 之後兩者分別變成 **8,190**(= 2,190 + 6,000)與 **6,710**(= 710 + 6,000),
  對應 `runs[15].actuator`。

k6 在外面數的與 backend 在裡面數的完全一致(連同被丟棄的 warm-up 流量也對得上),沒有請求被
默默吞掉,也沒有訂單被重複建立。

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

工具會啟動隔離的 `flashsale-benchmark` 專案、驗證隔離、跑 2 次 warm-up(結果丟棄)、依序跑完
15 次競爭情境與 soak、擷取不變量,最後產生一份結果文件並自動驗證。任何時候都可以重新驗證這份
文件:

```powershell
node load-tests/benchmark/verify-results.mjs docs/portfolio/data/benchmark-results.json
```

前置需求、隔離機制與失敗執行如何被保留,見
[壓測工具說明](../../load-tests/benchmark/README.md)。
