# FlashSale Week 7 作品集包裝設計規格

**日期：** 2026-08-16  
**狀態：** 已確認設計

## 1. 目標

Week 7 將已完成的 FlashSale 系統整理成可供面試官快速理解、實際啟動與深入追問的後端工程作品集。本輪不再擴張核心業務功能，重點是以可重現的證據呈現系統架構、正確性、負載特性、可觀測性與工程取捨。

主要閱讀者為繁體中文使用者，所有對外作品集文件使用完整繁體中文；穩定的程式識別碼、技術名稱與 API 欄位維持英文。

## 2. 敘事策略

採「證據導向作品集」：README 先回答系統解決什麼問題、有哪些可驗證成果，再進入架構與操作。詳細技術內容拆到 `docs/portfolio/`，避免 README 變成無法快速掃讀的長篇規格。

README 視覺與資訊層級採下列決定：

1. 首屏使用「成果優先」版型：高併發限量搶購、不超賣、非同步削峰、可觀測性與實測摘要。
2. 首屏下方提供測試數、服務健康度、CI 與不超賣等可信度數字。
3. 第一張主圖呈現系統全貌與服務邊界。
4. 第二張圖使用 sequence diagram 解釋核心搶購資料流。
5. README 收錄 4～6 張精選截圖，其餘細節由獨立文件承接。

## 3. 文件結構

### 3.1 `README.md`

- 專案價值與核心成果
- CI badge、技術棧與實測數字
- 系統全貌架構圖
- 核心搶購流程摘要與時序圖
- 5 分鐘快速啟動
- demo 資料建立與 USER／ADMIN 操作流程
- 精選畫面截圖
- 負載特性實測摘要
- 測試、可觀測性與服務健康度
- 深入文件索引

### 3.2 `docs/portfolio/architecture.md`

說明 Nginx、React、Spring Boot、PostgreSQL、Redis、RabbitMQ、Mailpit、Zipkin 與 Actuator 的責任邊界，並描述 Redis Lua 預扣、transactional outbox、非同步建單、冪等消費、失敗補償與 trace context 傳遞。

### 3.3 `docs/portfolio/api-examples.md`

提供可直接執行的 API 範例，涵蓋註冊／登入、活動查詢、搶購請求、狀態輪詢、我的訂單、付款／取消與 ADMIN 健康度。範例不得包含真實 token、密碼或私鑰。

### 3.4 `docs/portfolio/performance-report.md`

記錄目前非同步系統的負載特性、測試環境、方法、結果、正確性驗證、瓶頸與限制。本輪不再比較歷史同步版本，也不宣稱本機數據等於 production capacity。

### 3.5 `docs/portfolio/trade-offs.md`

正式說明 Redis Lua、outbox、RabbitMQ、補償策略、付款逾時掃描、OSIV 關閉、Actuator 暴露、Docker Compose 展示方式與其他主要設計取捨。

### 3.6 `docs/portfolio/demo-script.md`

提供 3～5 分鐘的面試展示流程，依序涵蓋前台搶購、訂單商品明細、管理後台、服務健康度、Zipkin trace 與 CI。

### 3.7 歷史與交接文件

- 更新 `docs/superpowers/plans/2026-08-16-session-handoff.md` 的測試數量、已知問題與下一步。
- 將 stash 中唯一未進入 `main` 的 technical-debt roadmap 恢復為正式歷史文件。
- 已完成的技術債不得繼續列為未處理限制。
- 新增 Week 7 handoff，記錄交付內容、負載環境、結果與後續維護方式。
- stash 中與 `main` 不同的其他文件逐份判斷，不直接覆蓋新版。

## 4. 圖表與截圖

架構圖與時序圖使用 Mermaid，使內容可 review、可版本控制並可隨程式演進。圖中元件、端點與資料流必須與實際 Compose 及程式碼一致。

精選截圖保存於 `docs/portfolio/assets/`，統一尺寸並壓縮，不使用外部圖床。目標畫面為：

1. 前台活動列表與票根視覺。
2. 搶購成功或狀態輪詢結果。
3. 我的訂單商品名稱、數量與單價。
4. 管理儀表板。
5. 8/8 服務健康度頁面。
6. Zipkin 完整 outbox trace。GitHub Actions 使用即時 CI badge 與 workflow 連結，不再額外提交 CI 截圖，避免成功畫面快速過期。

截圖只使用 demo fixture，不顯示 access token、Cookie、密碼、私鑰、真實 email 或本機絕對路徑。

## 5. Demo 資料工具

新增跨 Git Bash／Docker Compose 工作流可用的 `scripts/demo-data.sh`：

```text
scripts/demo-data.sh seed
scripts/demo-data.sh cleanup
```

密碼由 `DEMO_USER_PASSWORD` 與 `DEMO_ADMIN_PASSWORD` 環境變數傳入，不提供 Git 內建預設值。工具建立固定且可辨識的 USER、ADMIN、商品、活動與庫存；可重複執行而不產生重複資料。

所有資料帶明確 demo 識別值。cleanup 必須先確認 Compose project、資料庫與目標識別值，只刪除本工具建立的資料，不使用無條件 `TRUNCATE`，也不修改 production 自動啟動流程。

## 6. 目前系統負載能力驗證

### 6.1 原則

本輪只量測目前 `main` 的非同步系統，不比較歷史同步版本。報告名稱使用「負載特性實測」，說明這是特定本機與 Docker 資源下的觀察結果，不是正式容量承諾。

新增專用 benchmark harness，與既有 `load-tests/purchase-flow.js` 正確性腳本分離。benchmark 在測試前預先建立使用者與 access token，壓測期間只量測搶購與終態完成流程，避免註冊／登入限流與刻意 stagger 主導結果。

### 6.2 測試矩陣

固定三個競爭級距：

| 級距 | 使用者 | 庫存 | 重複次數 |
|---|---:|---:|---:|
| 小 | 30 | 10 | 5 |
| 中 | 100 | 30 | 5 |
| 大 | 300 | 100 | 5 |

另執行一輪 10 分鐘持續負載：使用 k6 `constant-arrival-rate` 以每秒 10 次搶購嘗試送出 6,000 次請求，預先建立 6,000 個唯一使用者並提供足夠庫存；`preAllocatedVUs=50`、`maxVUs=200`。此輪觀察 queue backlog、錯誤率、記憶體與終態處理是否隨時間惡化，不和 30／100／300 人同時競爭有限庫存的矩陣混算。所有測試固定相同 Docker CPU／memory 限制，並記錄測試機、作業系統、Docker、commit、日期與資源設定。

### 6.3 指標

- `202 Accepted` 的 API 接受延遲。
- 從送出搶購到 `SUCCEEDED`／`SOLD_OUT` 等終態的業務完成延遲。
- throughput。
- p50、p95、max、min 與五次結果的中位數。
- 非預期 5xx 與 timeout。
- Redis reservation outcome／latency。
- RabbitMQ backlog 與 consumer 完成狀況。
- 訂單、purchase request 與庫存聚合結果。

不刪除離群值，也不預設非同步架構在所有指標上都較快。

### 6.4 正確性驗證

每輪都必須確認：

- 訂單數等於成功售出的庫存。
- 庫存不為負數。
- 同一使用者／活動不重複建單。
- 無非預期 5xx。
- 無殘留 `PENDING`。
- purchase request 全部到達預期終態。
- Redis／PostgreSQL 庫存結果一致。

### 6.5 產物

- `load-tests/benchmark/`：可重現的準備、執行、收集與清理工具。
- `docs/portfolio/data/benchmark-results.json`：結構化摘要與環境 metadata。
- `docs/portfolio/performance-report.md`：結果、圖表、瓶頸、限制與解讀。
- README：只放精簡表格與結論。

benchmark 使用獨立 Compose project 與 volume，避免污染使用者目前資料。完成後清理 benchmark fixture 與暫時環境，但保留結構化結果。

## 7. 失敗處理與安全

- 每輪開始前確認 8/8 服務 healthy。
- 終態超過明確 timeout 即停止該輪，不無限等待。
- 若出現 5xx、殘留 `PENDING`、資料不一致或服務 unhealthy，將該輪標記失敗並保留診斷資料，不以其他成功輪覆蓋。
- 持續負載若錯誤率持續上升或服務 unhealthy，提前停止並如實記錄。
- 保存 k6 summary、必要 metrics、queue／DB 統計與 Compose 狀態，但不保存 JWT、密碼、Cookie、私鑰或 `.env`。
- 測試環境或資源設定改變後，舊數據不得與新數據混算。

## 8. 驗證策略

### 8.1 文件與連結

- README 與 portfolio 文件內部連結必須可解析。
- Mermaid code fence 可被 GitHub 渲染，且圖中名稱與實作一致。
- Shell script 通過語法檢查與安全路徑檢查。
- API 範例在 demo fixture 上可實際執行。

### 8.2 Demo 工具

- `seed` 連續執行兩次，資料筆數不增加。
- `cleanup` 連續執行兩次，第二次仍安全成功。
- 建立的 USER 與 ADMIN 可登入，重新登入後 ADMIN JWT 具正確角色。
- 非 demo 資料在 seed／cleanup 前後保持不變。

### 8.3 Benchmark

- 三個級距各完成五次，另完成 10 分鐘持續負載。
- 每次都有環境 metadata、原始摘要與資料庫／queue 正確性結果。
- 失敗輪與成功輪都保留，不選擇性刪除。
- 報告中的表格與 JSON 結果可互相核對。

### 8.4 專案回歸

本輪主要修改文件與工具，但 demo／benchmark 會操作完整系統。完成前須維持 backend、frontend 與 CI 全綠，並從乾淨 Compose 環境走完 README 的啟動與 demo 流程。

## 9. 完成定義

- 第一次看到 README 的讀者能在 30 秒內理解專案亮點。
- 讀者可依 README 在約 5 分鐘內啟動本機環境。
- demo seed／cleanup 可重複、安全執行。
- README、架構圖、時序圖、API 範例與實際程式一致。
- 4～6 張精選截圖清晰且無敏感資訊。
- 三級負載各五次與 10 分鐘持續負載完成，正確性不變量成立。
- 報告明確區分本機實測與 production capacity。
- handoff、README 與技術債文件不再包含已失效描述。
- 最終 CI 維持全綠。

## 10. 非目標

- 不建立公開 production 部署。
- 不承諾 production SLA、RPS 或容量。
- 不新增 Prometheus／Grafana。
- 不為作品集新增核心業務功能。
- 不比較歷史同步版與目前非同步版。
- 不將固定密碼、token、私鑰或真實帳號提交到 Git。
