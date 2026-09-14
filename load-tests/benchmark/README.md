# 可重現的壓測工具

這是一套針對目前 FlashSale 搶購流程的壓測工具。它會用獨立的 Docker Compose 專案、獨立的
volume、獨立的 port 再跑一次整套服務,用 k6 施壓,並在每次測試產生一份 JSON 文件,同時記錄
量測數字與量測當下成立的正確性不變量。

重點是可重現,不是刷分數。重跑與複查所需要的一切(Git SHA、Docker 版本、作業系統、CPU、
RAM、時間戳記、每次執行的設定)都寫進結果文件,而這份文件能不能被引用,由
`verify-results.mjs` 決定。

## 這裡量的是什麼(以及不宣稱什麼)

量的是非同步搶購流程的兩段(細節見[架構深入說明](../../docs/portfolio/architecture.md)):

| 指標 | 意義 |
|---|---|
| `acceptedLatencyMs` | 同步的那一段:`POST /api/flash-sales/{id}/purchase-requests` 回 `202` 的耗時,也就是限購檢查加上 Redis Lua 預扣。當買家沒搶到(`SOLD_OUT`)或已經買過(`REJECTED`)時,這個回應本身就是終態。 |
| `completedLatencyMs` | 從送出上述 POST 起算,到輪詢 `GET /api/purchase-requests/{requestId}` 觀察到終態為止的實際時間。 |
| `orderCreatedLatencyMs` | 上一項中「有搶到」的子集合:outbox 發佈 → RabbitMQ → consumer → 訂單資料列。 |

每次執行結束後,工具還會記錄資料庫與佇列的狀態:建立的訂單數、`SUCCEEDED`/`SOLD_OUT`/
`REJECTED`/`FAILED` 各自的數量、殘留的 `PENDING`、同一使用者的重複訂單、庫存計數、未發佈的
outbox 資料列、RabbitMQ 各佇列深度(含兩條 DLQ)、Actuator 的搶購指標,以及健康檢查結果。

以下是刻意畫出的邊界,好讓這些數字被正確解讀:

- **這是目前系統在一台開發機上的負載特性**,不是正式環境的容量數字,也沒有跟任何早期版本或
  其他實作做比較。
- **壓力來源直接打 backend,不經過 Nginx。** Nginx 對搶購請求有以每個 client IP 為單位的
  限流(50r/s,burst 100)。單機壓測工具同時送出 300 個請求時,量到的主要會是限流器在丟棄
  流量,所以這套工具把 Nginx(連同 TLS 終止與 frontend)排除在請求路徑外。要走完整端到端
  路徑(含 Nginx)的腳本是 `load-tests/purchase-flow.js`。
- **這裡沒有任何效能門檻。** 只有正確性不變量會讓一份結果被判定為不合格;延遲與吞吐量都只是
  照實記錄。

## 前置需求

- 能跑 Compose v2 的容器引擎(Docker Desktop 或 Rancher Desktop 皆可)。
- **k6 不需要裝在本機。** `collect.ps1` 以容器形式執行 k6(`grafana/k6:2.2.0`),把它接進
  compose 網路,直接連 `backend:8080`。施壓流量**不會**經過發布到 Windows 的 host port。

  這不是為了省事,是正確性問題:從 Windows 打 `127.0.0.1:18080` 時,300 條瞬間到達的新連線
  中約有 25–30% 會在 TCP 握手階段被 host port 發布層拒絕(`ECONNREFUSED`)。Tomcat 從來沒
  看到那些連線,但 k6 會把它們記成請求失敗——量測工具自己的限制被當成受測系統的行為。
  同一個 backend 容器、同一支腳本的對照:從容器網路內打 0/300 失敗,從 Windows 打 75/300 失敗。
- [Node.js](https://nodejs.org/),需在 `PATH` 上(用來跑驗證器)。不需要安裝任何 npm 套件,
  驗證器沒有相依套件,測試也是用 Node 內建的 test runner。
- repo 根目錄要有 `.env`,內含 `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`,跟一般啟動整套服務時
  一樣,設定方式見[根目錄 README](../../README.md)。壓測用的 backend 讀的是同一個檔案,沒有
  這兩把金鑰就不會啟動。

Windows PowerShell 5.1 即可,`collect.ps1` 不需要 PowerShell 7。

## 隔離

壓測會清空資料庫、清掉 Redis。以下所有機制的存在,都是為了確保它只可能對壓測環境做這些事:

- Compose 專案名稱固定是 **`flashsale-benchmark`** 這個字串。`collect.ps1` 會區分大小寫比對,
  不符就拒絕啟動;若 `COMPOSE_PROJECT_NAME`、`COMPOSE_FILE`、`DOCKER_HOST` 或非本機的
  `DOCKER_CONTEXT` 有可能讓它指向別的環境,同樣拒絕執行——這跟
  [`scripts/demo-data.sh`](../../scripts/demo-data.sh) 是同一套 fail-closed 的作法。
- 在第一個具破壞性的操作之前,它會 inspect 執行中的容器,要求容器上的 Compose label 同時指向
  這個專案**與**這個 compose 檔。
- volume 名稱是 `benchmark_postgres_data`(完整名稱為
  `flashsale-benchmark_benchmark_postgres_data`),所以這裡的 `down -v` 碰不到
  `flashsale_postgres_data`。
- 對外開的 port 跟一般那套服務不會衝突:backend `18080`(`BENCHMARK_BACKEND_PORT`)、
  postgres `15432`(`BENCHMARK_POSTGRES_PORT`)、nginx `8444`(`BENCHMARK_NGINX_PORT`)、
  zipkin `9412`(`BENCHMARK_ZIPKIN_PORT`)。一般那套服務開的是 `8443` 與 `9411`,兩套可以
  同時跑。
- 清理時一律只指名 `flashsale-benchmark`。

## 怎麼跑

在 repo 根目錄執行:

```powershell
# 完整矩陣:30/10、100/30、300/100 各跑五次,接著跑 soak。會花不少時間。
powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode full

# 冒煙測試:只跑一次 300 VU / 100 件庫存,並保留環境方便事後檢查。
powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode smoke -SmokeVus 300 -SmokeStock 100 -KeepStack
```

其他常用參數:`-OutDir <path>`(預設 `load-tests/benchmark/results`)、`-KeepStack`(跑完保留
環境,方便檢查,見下方「保留環境看畫面」)。

`collect.ps1` 會先把環境啟動起來、驗證隔離,接著對每一次執行依序做:重置並重新灌入資料
(`fixtures.sql`)、清掉 Redis 的預扣計數、清空佇列、建立這次要用的買家帳號(`prepare.js`)、
跑要量測的情境(`purchase-load.js` / `soak.js`)、等待非同步流程消化完畢、擷取各項不變量,
最後把整個專案關掉並執行驗證器。

### 手動操作 Compose

```powershell
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml config --quiet
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml up -d --build --wait
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml down -v
```

`--project-directory .` 是必要的,不是可選的:Compose 會以 project directory 為基準解析
backend 的相對 build context 與 `.env` 的位置,少了它就會從錯誤的路徑建置,也找不到 JWT
金鑰。上面這些指令一律要在 repo 根目錄執行。

## 保留環境看畫面

`compose.benchmark.yaml` 也帶了 frontend 與 nginx,但**量測本身完全不受影響**:k6 一律直接打
backend 的 `18080`,不會經過 nginx,frontend/nginx 純粹是給人事後瀏覽用的,不在被量測的路徑上
(nginx 對搶購請求有限流,量測刻意排除它,見上方「這裡量的是什麼」)。

用 `-KeepStack` 跑完,收集流程會在所有 run 結束、確定要保留之後,自動建立一組固定帳密的
ADMIN 帳號並印在主控台:

```
default admin ready: admin@test.com / admin1234
```

拿這組去 `https://localhost:8444/` 登入(自簽憑證,瀏覽器會跳警告,點過去即可),就能進
`/admin` 看這次執行留下的訂單、庫存、健康度等畫面。這個帳號是在所有 run 跑完後才建立的——每個
run 開始前都會 truncate `users` 表(`fixtures.sql`),提早建立會被下一個 run 洗掉;重跑同一個
保留的環境也沒事,會重新註冊/重新升級成 ADMIN,冪等的。

想直接查資料庫,DB 開在 `localhost:15432`(帳密都是 `flashsale`);想看 trace,Zipkin 在
`http://localhost:9412/zipkin/`。

看完別忘了清掉,不然這套隔離環境會一直占資源:

```powershell
docker compose -p flashsale-benchmark --project-directory . -f load-tests/benchmark/compose.benchmark.yaml down -v
```

## 執行的內容

| 執行 | 規模 | 用意 |
|---|---|---|
| `contention-30x10-*` | 30 個買家,10 件庫存 | 輕度超額競爭,3:1。 |
| `contention-100x30-*` | 100 個買家,30 件庫存 | 中度超額競爭。 |
| `contention-300x100-*` | 300 個買家,100 件庫存 | 大量買家同時湧入。 |
| `soak-1` | `constant-arrival-rate`、`rate: 10`、`timeUnit: '1s'`、`duration: '10m'`、`preAllocatedVUs: 50`、`maxVUs: 200`,6,000 個不重複買家 | 看的是穩態而非瞬間尖峰:延遲能不能持平十分鐘。 |

每種競爭情境各跑五次,才能報出分佈而不是單一次的好運結果。每個買家都是在量測開始前就建立好的
獨立帳號,所以不會有 VU 撞到每人限購上限,也不會有登入流量混進數字裡。soak 的 scenario 區塊
在 `soak.js` 裡是寫死的常數,會原封不動寫進結果文件,再由驗證器重新檢查一次——三者不可能悄悄
走鐘。

## 結果與驗證

每次執行會產生 `results/<mode>-<timestamp>/`:

- `results.json` — 結果文件(格式見下)。
- `k6-<runId>.json` — 該次執行的 k6 summary,包含原始的 k6 metrics。
- `tokens-<runId>.json` — `prepare.js` 建立的帳號與 access token。

```jsonc
{
  "schemaVersion": 1,
  "mode": "full",                 // 或 "smoke"
  "environment": { "gitSha": "...", "gitDirty": false, "k6Version": "...",
                   "dockerVersion": "...", "dockerComposeVersion": "...",
                   "os": "...", "cpu": { "model": "...", "logicalCores": 16 },
                   "memoryGb": 32, "composeProject": "flashsale-benchmark",
                   "startedAt": "...", "finishedAt": "..." },
  "summary": { "expectedRuns": 16, "failedRuns": 0 },
  "runs": [ { "runId": "contention-30x10-1", "kind": "contention",
              "status": "valid",            // 或 "failed"
              "vus": 30, "stock": 10,
              "k6": { "exitCode": 0, "checksFailed": 0, "summaryFile": "..." },
              "metrics": { ... }, "outcomes": { ... }, "invariants": { ... },
              "queues": [ ... ], "health": { ... }, "actuator": { ... },
              "errors": [] } ]
}
```

隨時可以驗證一份結果文件:

```powershell
node load-tests/benchmark/verify-results.mjs load-tests/benchmark/results/<session>/results.json
```

文件乾淨時 exit code 為 `0`;有問題則為 `1`,並且每一項違規印一行。它會拒絕:

- **超賣** — 訂單數(或 `SUCCEEDED` 的請求數)超過灌入的庫存;
- **重複訂單** — 同一次執行中,任何使用者持有超過一張訂單;
- **負庫存** — `available`/`reserved`/`sold` 任何一項小於零;
- **非預期的 5xx** — 搶購 API 不該回任何原始 5xx,這也是 `PurchaseConcurrencyIT` 在後端測試
  層級所主張的不變量;
- **殘留 `PENDING`** — 任何請求沒有走到終態;
- **缺少環境 metadata**,或這份結果不是在 `flashsale-benchmark` 專案下收集的;
- **漏掉執行紀錄** — 紀錄筆數少於 `summary.expectedRuns`、`failedRuns` 的數字對不上失敗的
  紀錄筆數、失敗的執行被抽掉了錯誤細節,或是 k6 exit code 非 0 卻被標記為 `valid`;
- **soak 設定錯誤** — 只要偏離 `constant-arrival-rate`、`10/s`、`10m`、`50`/`200` VU、
  `6000` 個不重複使用者的任何一項。

### 失敗的執行會被保留

失敗的執行一樣會寫進文件,`status` 為 `"failed"`,失敗原因放在 `errors` 陣列,計入
`summary.failedRuns`,而且各項不變量照樣擷取。只要筆數或失敗數暗示有紀錄消失,驗證器就會拒絕
整份文件,所以不可能靠悄悄丟掉一次失敗來讓結果變好看。離群值同樣不會被修剪——每個情境的五次
執行全都會記錄下來。

## 檔案

| 檔案 | 職責 |
|---|---|
| `compose.benchmark.yaml` | 獨立環境:Postgres、Redis、RabbitMQ、Mailpit、Zipkin、backend、frontend、nginx(後兩者只給人瀏覽用,不在量測路徑上)。 |
| `fixtures.sql` | 每次執行前的破壞性重置,並灌入一場帶有 `:stock` 件庫存的進行中活動。 |
| `prepare.js` | 在量測範圍外建立該次執行專用的買家帳號與 token。 |
| `purchase-load.js` | 競爭情境(`VUS` 個買家、`STOCK` 件庫存,每人送出一次)。 |
| `soak.js` | soak 情境,10/s 持續 10 分鐘,涵蓋 6,000 個買家。 |
| `collect.ps1` | 流程調度、隔離防護、各項探測、結果文件、清理。 |
| `verify-results.mjs` | `validateBenchmarkResults(value)` 與 CLI 驗證閘門。 |
| `verify-results.test.mjs` | `node --test load-tests/benchmark/verify-results.test.mjs`。 |

## 測試

```powershell
node --test load-tests/benchmark/verify-results.test.mjs
```
