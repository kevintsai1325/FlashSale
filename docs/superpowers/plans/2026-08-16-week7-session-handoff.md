# Week 7 作品集包裝 — Session 交接文件 (2026-08-16)

> **用途**:記錄 Week 7「作品集包裝」這個 session 實際做了什麼、產出落在哪些檔案、
> 證據怎麼查證,以及交接時還剩下什麼。接手的人(或 AI)讀完這份就不必重爬整條 git log。
> 專案本身的背景、技術棧與開發慣例寫在
> [`2026-08-16-session-handoff.md`](./2026-08-16-session-handoff.md),這裡不重複。

## Week 7 的範圍

把 FlashSale 包裝成可以直接給人看的作品集:安全可重複的 demo 資料工具、架構/API/取捨/Demo 四份
深入文件、可重現的負載量測與實測證據、去識別化的實機截圖、以證據為先的 README,以及這份交接文件。
設計與 implementation plan 分別在
[`../specs/2026-08-16-flash-sale-week7-portfolio-design.md`](../specs/2026-08-16-flash-sale-week7-portfolio-design.md)
與 [`./2026-08-16-flash-sale-week7-portfolio.md`](./2026-08-16-flash-sale-week7-portfolio.md)。

**貫穿整個 Week 7 的三條紅線**(每一份產出都受它約束):

1. 不提交任何密碼、JWT、Cookie、私鑰、`.env` 值、真實 email 或本機絕對路徑。
2. 只描述目前這套系統的負載特性,不跟任何早期實作比較,不宣稱 production 容量或 SLA。
3. 文件敘述用繁體中文,穩定識別碼、指令、路徑與 API 欄位保留英文。

## 這個 session 的 commit(分支 `codex/week7-portfolio`,由舊到新)

| Commit | 主題 | 對應任務 |
|---|---|---|
| `f7afaa3` | plan Week 7 portfolio packaging | 規劃 |
| `7701629` | synchronize Week 7 project history | Task 1 |
| `4934003` | clarify completed integration test isolation | Task 1 |
| `da8dc14` | add safe portfolio demo data tooling | Task 2 |
| `0d89ec8` | harden demo data safety boundaries | Task 2 |
| `e74f30c` | anchor demo cleanup to canonical stack | Task 2 |
| `0b335b4` | hand off Week 7 to Claude Code | 交接 |
| `d697661` | harden demo audit barrier and Docker/API target binding | Task 2 |
| `9551e99` | close fresh-review fast-follows on the audit barrier fix | Task 2 |
| `29ebee5` | add FlashSale portfolio deep dives | Task 3 |
| `ebf6ad1` | fix traceId/Zipkin correlation claim in api-examples.md | Task 3 |
| `81ec6f1` | add reproducible current-system load harness | Task 4 |
| `7541d47` | translate benchmark README to Traditional Chinese | Task 4 |
| `aba1209` | gitignore benchmark results dir, fix compose invocation example | Task 4 |
| `df51bdd` | record FlashSale load characteristics | Task 5 |
| `ee01e0e` | caveat the 300-VU throughput number for connection loss | Task 5 |
| `693075d` | add sanitized portfolio screenshots | Task 6 |
| (本次) `docs: complete FlashSale portfolio packaging` | README 改寫 + 這份交接文件 | Task 7 |

## 各任務產出的檔案

### Task 1:同步專案歷史

- `docs/superpowers/plans/2026-08-16-session-handoff.md`(更新)
- `docs/superpowers/specs/2026-08-16-technical-debt-roadmap-design.md`(新增)

把「六批技術債已完成」寫進歷史文件,避免後續文件把已解決的項目再列成限制。

### Task 2:安全的 demo 資料工具

- `scripts/demo-data.sh`、`scripts/lib/demo-data-lib.sh`
- `scripts/tests/demo-data-lib-test.sh`、`scripts/tests/demo-data-test.sh`
- `.env.example`(更新)
- backend:`ApiAuditPersistence`、`AsyncApiAuditPersistence`、`DemoDataAuditBarrierController`、
  `ApiAuditWriter`/`ApiAuditFilter`/`SecurityConfig`(更新),以及 `ApiAuditWriterTest`、
  `DemoDataAuditBarrierControllerIT`、`ApiAuditFilterIT`

`./scripts/demo-data.sh seed|cleanup`。密碼由執行者以 `DEMO_USER_PASSWORD` / `DEMO_ADMIN_PASSWORD`
提供,不寫死也不進版控。安全邊界(這幾點是刻意設計,改動前請先讀腳本):只接受本機 Docker
endpoint、拒絕非 `flashsale` 的 Compose 專案覆寫、拒絕非正規的 `compose.yaml` / `.env` 目標、
要求 8 個服務全部 healthy 才動手,而且 cleanup 只刪除固定的示範識別碼、可重複執行。
稽核紀錄是非同步寫入的,所以 cleanup 前會先透過 `internal` 屏障端點等待寫入排空,避免刪不乾淨。

### Task 3:四份深入文件

- `docs/portfolio/architecture.md`、`api-examples.md`、`trade-offs.md`、`demo-script.md`
- `scripts/tests/portfolio-docs-test.ps1`(契約測試)

契約測試會直接從 `*Controller.java` 與 `nginx/nginx.conf` 解析真實路由,驗證文件裡寫的每一條 API
路徑真的存在;同時檢查必要標題、code fence 平衡、相對連結可解析、metric 名稱存在於
`PurchaseMetrics.java`、沒有機密樣式、沒有非示範網域的 email,以及一份「已失效敘述」黑名單。

### Task 4:可重現的壓測工具

- `load-tests/benchmark/`:`collect.ps1`、`compose.benchmark.yaml`、`fixtures.sql`、`prepare.js`、
  `purchase-load.js`、`soak.js`、`verify-results.mjs`、`verify-results.test.mjs`、`README.md`
- `.gitignore`(忽略 `load-tests/benchmark/results/`)

壓測跑在完全隔離的 `flashsale-benchmark` Compose 專案(獨立 volume 與 port),不會污染日常開發用的
`flashsale` 專案。`verify-results.mjs` 可以隨時對既有結果文件重跑驗證。

### Task 5:實測證據與報告

- `docs/portfolio/data/benchmark-results.json`(16 次執行的原始結果)
- `docs/portfolio/performance-report.md`

### Task 6:實機截圖

- `docs/portfolio/assets/{storefront,purchase-result,my-orders,admin-dashboard,system-health,zipkin-trace}.png`
- `docs/portfolio/assets/README.md`

### Task 7:README 與交接(本次)

- `README.md`(改寫)
- `scripts/tests/portfolio-docs-test.ps1`(擴充 README 契約)
- `docs/superpowers/plans/2026-08-16-week7-session-handoff.md`(本檔)

README 改成「證據先行」:第一屏是定位、CI badge 與一張現況/證據表,接著是六張截圖、兩張 Mermaid 圖
(系統全貌 flowchart 與搶購 sequenceDiagram)、快速開始、demo 指令、深入文件索引,最後才是收在
`<details>` 裡的詳細設定與誠實的已知限制。

**這次同時修掉 README 兩個過期且錯誤的敘述**:

1. 舊 README 說 nginx 沒有 `/actuator/` 的反向代理規則。實際上 `nginx/nginx.conf` 反代一份白名單
   (`/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`、`/actuator/metrics`、
   `/actuator/metrics/{name}`),其餘 `/actuator/` 路徑才回 404。
2. 舊 README 說 outbox 沒有持久化 trace context、訊息 trace 與 HTTP 請求 trace 是分開的兩條。
   實際上 `outbox_events.trace_context` 已經在技術債批次 4 完成,`OutboxPublisher` 會續上 parent
   context,整條鏈落在同一條 trace 上(Task 6 的 `zipkin-trace.png` 就是證據)。

## 壓測環境與結果摘要

完整內容在 [`docs/portfolio/performance-report.md`](../../portfolio/performance-report.md);
以下只是給接手者的快速索引,每個數字都可以在
[`docs/portfolio/data/benchmark-results.json`](../../portfolio/data/benchmark-results.json) 找到對應路徑。

**環境**(全部來自 `environment`):Windows 11 家用版 10.0.26200、Intel Core i7-14650HX(24 邏輯核心)、
31.6 GB RAM、Docker Engine 29.6.2、Docker Compose 5.3.1、k6 v2.2.0;commit `aba1209`、分支
`codex/week7-portfolio`、工作區乾淨;壓力進入點 `http://127.0.0.1:18080`(直接打 backend,不經 Nginx);
2026-08-16 12:16:45Z ~ 12:33:57Z。收集當下同一台機器還跑著閒置的 `flashsale` 專案,CPU/IO 共用,
影響未量化。

**結果**:16 次執行、0 次失敗(15 次競爭情境 + 1 次 10 分鐘 soak)。

| 情境 | accepted p95(五次中位數) | completed 中位數 | 備註 |
|---|---|---|---|
| 30 買家 / 10 件 | 45.0 ms | 47.0 ms | `runs[0]` 是 JVM 暖機離群值,未剔除 |
| 100 買家 / 30 件 | 80.9 ms | 74.0 ms | — |
| 300 買家 / 100 件 | 137.5 ms | 149.0 ms | 每次約 88 筆請求在建立 TCP 連線階段被拒,實際只有約 212 個有效買家;比較不同規模時請用 p95,不要用它的中位數 |
| soak(10 分鐘,6,000 件) | 12.6 ms(單次執行;accepted 中位數 7.6 ms) | 511 ms | 6,000 筆訂單全部成立 |

**不變量**:16 次執行都沒有超賣、沒有重複下單、沒有殘留 `PENDING`、沒有原始 5xx、沒有輪詢逾時、
outbox 全部發佈完成、四條佇列(含兩條 DLQ)深度全為 0;庫存守恆逐筆人工複查過。

**重跑方式**:

```powershell
powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode full
node load-tests/benchmark/verify-results.mjs docs/portfolio/data/benchmark-results.json
```

## 截圖重新產生

六張截圖的擷取條件(commit、瀏覽器、viewport、語系時區)、資安檢查與**完整的重新產生步驟**寫在
[`docs/portfolio/assets/README.md`](../../portfolio/assets/README.md)。重點:用 Playwright 在
repo 之外的暫存目錄擷取(不要把 Playwright 加進 `frontend/package.json`)、資料來自
`scripts/demo-data.sh seed`、擷取後做無損壓縮並移除 PNG 的非必要區塊,最後人工目視檢查。

## 測試證據(以及 Task 8 的權威複查)

- **後端**:169 個測試通過。本 session 內驗證過兩次,最後一次完整重跑是在 Task 2 的最後一輪修正
  (commit `9551e99`);之後 Task 3~7 都沒有再動過 backend 程式碼
  (`git diff --name-only 9551e99..HEAD -- backend frontend` 為空)。
- **前端**:70 個測試通過,是 Week 7 開始前的**最後已知**基線;Task 4~7 沒有動過前端程式碼。
- **文件契約測試**:`powershell.exe -ExecutionPolicy Bypass -File scripts/tests/portfolio-docs-test.ps1`
  → PASS(5 份文件:四份 `docs/portfolio/*.md` 加上根目錄 `README.md`)。
- **壓測結果驗證**:`node load-tests/benchmark/verify-results.mjs docs/portfolio/data/benchmark-results.json`
  → 16 次執行全部 `valid`。

> **Task 8 才是權威的最終複查。** 上面的後端/前端數字是本 session 已取得的證據,不是在寫這份文件時
> 重新執行的。Week 7 的 Task 8 會做一次完整的端到端回歸(後端完整套件、前端套件、文件契約測試、
> 壓測結果驗證),以那一次的輸出為最終數字;如果 Task 8 的結果與這裡不同,以 Task 8 為準。

## 目前的工作區狀態

- 分支 `codex/week7-portfolio`(位於 worktree `.worktrees/week7-portfolio`),尚未 merge 回 `main`,
  也尚未 push。**commit 與 push 都需要使用者明確同意**。
- `git stash list` 有一筆 `stash@{0}: On main: pre-merge untracked docs 2026-08-16`,內容是 13 份
  技術債批次的 spec/plan 文件。這些檔案後來都已經被正式 commit 進版控(例如
  `2026-08-16-technical-debt-roadmap-design.md` 在 `7701629`),所以這筆 stash 已被取代,可以安全
  `git stash drop`——但既然是使用者的 stash,留給使用者自己決定,不要代為刪除。
- `.superpowers/` 被 gitignore,裡面的任務 brief 與 report 不進版控。

## 仍然存在的限制(對外要照實揭露)

與 [`docs/portfolio/trade-offs.md`](../../portfolio/trade-offs.md) 和 README 的「已知限制」一致:

1. **本機自簽 TLS**:沒有正式憑證鏈、自動續期、HSTS 或 OCSP stapling。
2. **只交付 Docker Compose**:沒有公開部署、沒有水平擴充/滾動更新/自動修復,沒有 Prometheus 或
   Grafana 這類集中式監控與告警。
3. **量測邊界**:單機、單次收集、壓力不經 Nginx 與 TLS、沒有做飽和測試,所以沒有任何數字是吞吐量
   上限或 production 容量/SLA;300 VU 那一組另有連線被拒的資料品質問題。
4. **付款是模擬的**,通知只有 email 一種通道(本機由 Mailpit 攔截)。
5. **排程假設單一 backend 實例**,沒有分散式鎖;DLQ 沒有自動重放工具。

**不要再把這些列成待處理限制**:401/403 稽核、前端 401/403 全域處理、整合測試背景任務隔離、
outbox trace context、Redis 預扣失敗 metric、後台編輯與視覺缺口、服務健康度 dashboard、
訂單商品快照——這八項都已經完成並有對應 commit(見
[`../specs/2026-08-16-technical-debt-roadmap-design.md`](../specs/2026-08-16-technical-debt-roadmap-design.md))。

## 之後可以做、但不是必要的事

這些都是「有餘力再做」,沒有任何一項阻擋作品集交付:

- **縮短搶購完成延遲**:目前完成延遲的主要成分是 `OutboxPublisher` 的 `fixedDelay = 500` 與用戶端
  輪詢間隔,不是資料庫或 RabbitMQ 的處理能力。調小 `fixedDelay`、改用 CDC,或把終態改成 SSE/WebSocket
  推播,都比擴充硬體有效(取捨見 `trade-offs.md`)。
- **量到飽和點**:設計一組遞增負載直到系統開始退化的實驗,才有辦法回答「這套系統能撐多少」。
- **釐清 300 VU 的連線被拒**:兩個候選(Docker Desktop 的 port proxy accept backlog、Tomcat 預設
  listen backlog)都還沒被證實,需要另外設計實驗。
- **分時間區間的壓測時序資料**:目前只有整段期間的彙總統計,無法畫趨勢線。
- **DLQ 重放工具**與**排程的分散式鎖**:這兩項是「要支援多實例才需要」的前置條件。
- **把文件契約測試接進 CI**:目前 `portfolio-docs-test.ps1` 只在本機跑;要進 CI 需要一個 Windows
  runner,或把它改寫成跨平台的實作。
- **環境備註**:這台機器沒有 `pwsh`,只有 Windows PowerShell 5.1,所以 repo 內的 `.ps1` 一律以
  `powershell.exe` 執行,並且只使用 5.1 相容語法(不用 ternary、`??`、`?.`、`&&`/`||` 串接);
  `node`/`npm` 也不在 PATH 上,前端相關指令要透過 Docker 執行。
