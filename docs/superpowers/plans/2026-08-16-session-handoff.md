# 專案交接文件 (2026-08-16)

> **用途**：這份文件是給接手這個專案的下一個人／下一個 AI assistant（可能是 ChatGPT 或其他工具，
> 不一定還是 Claude Code）看的。目的是讓對方不用重新爬完整個 git log 或猜測目前狀態，就能知道
> 「這是什麼專案」「現在做到哪裡」「上一個 session 做了什麼」「接下來該做什麼」。

## 這是什麼專案

FlashSale：一個「限量商品搶購」系統的作品集專案（portfolio project），重點在展示高併發搶購場景
下如何做到「不超賣、不漏賣」，以及後台管理、可觀測性、CI 等完整工程實踐。不是要上線的真實商業
產品。

**技術棧**：
- 後端：Spring Boot 3.3（Java 21）、PostgreSQL、Redis（庫存扣減用 Lua script 做原子操作）、
  RabbitMQ（非同步建立訂單）、Flyway migration、JWT 驗證、Micrometer + Zipkin 追蹤、
  logstash-logback-encoder 結構化日誌。
- 前端：React 19 + TypeScript、Vite、React Query、react-hook-form + Zod、React Router 7。
- 基礎設施：Docker Compose（nginx TLS reverse proxy、postgres、redis、rabbitmq、mailpit 假信箱、
  zipkin）、GitHub Actions CI。
- 完整的本機啟動步驟已經寫在根目錄 `README.md`，包含「如何把帳號升級成 ADMIN」「如何跑 k6
  壓力測試」等操作說明，不在這裡重複。

**設計文件在哪裡**：`docs/superpowers/specs/` 放各週的設計規格（design spec），
`docs/superpowers/plans/` 放對應的實作計畫（implementation plan，含逐步驟的程式碼）。這個專案的
開發流程是「先寫 spec → 使用者確認 → 寫 plan → 依 plan 逐一實作」，每一週（Week N）一組
spec+plan 檔案，檔名都是 `YYYY-MM-DD-flash-sale-weekN-<主題>-design.md` /
`...weekN-<主題>.md`。**這些檔案本身就是最詳細的技術文件**，如果要深入了解某個功能為什麼這樣
設計，先去讀對應那週的 spec，比重新問一輪還快。

## 目前進度（做到 Week 6，Week 7 未開始）

- **Week 1**：同步版 MVP（搶購核心、Redis 原子扣庫存、下單、付款模擬）。
- **Week 2-3**：搶購核心非同步化（RabbitMQ + outbox pattern）、使用者前台頁面。
- **Week 4**：後台管理（訂單查詢、通知中心、API 稽核紀錄）+ JWT 驗證強化。
- **Week 5**：可觀測性（Actuator、Micrometer 自訂指標、Zipkin 分散式追蹤、結構化 JSON log）+
  GitHub Actions CI。
- **Week 6**：範圍中途改過一次——原本規劃的「作品集包裝」（README/架構圖/demo 文件）被延後成
  Week 7，改成優先做「商品/搶購活動後台 CRUD」（因為使用者實際測試時發現後台完全沒有新增搶購
  活動的功能，測不下去）+ 幾個真實 bug 修復 + 視覺設計落差稽核 + 技術債處理。詳見
  `docs/superpowers/plans/2026-08-15-flash-sale-week6-admin-crud.md` 與這份文件下面「上一個
  session 做了什麼」。
- **Week 7（未開始）**：原本的 Week 6 範圍——README 補強、架構圖、API 使用範例、demo 帳號
  文件、同步 vs 非同步效能比較、設計取捨（trade-offs）說明文件。這是目前最主要的「下一步」。

程式碼目前狀態：`main` branch，已經 push 到 GitHub（`origin/main`）。後端測試 131 個全綠
（48 個測試類別），前端測試在有安裝 `recharts`/`@tanstack/react-table` 套件的環境下應該全綠
（開發機上這兩個套件目前沒裝，見下方「環境備忘」）。

## 上一個 session 做了什麼（2026-08-15 晚間 ～ 2026-08-16 凌晨）

這個 session 一開始是接續合併 Week 6 admin CRUD 分支到 main，之後使用者在實際使用 app 時陸續
回報了幾個問題，全部都在同一個 session 內處理完並合併回 main（4 個獨立的 commit 群組）：

1. **前後台都補上「回列表」的導覽**：前台 `AppNav` 的 logo 原本只是純文字，沒有連結；訂單詳情
   頁、搶購結果頁完全沒有掛導覽列。後台 `AdminNav` 原本也沒有連回前台賣場的連結。全部補上。
2. **搶購頁面新增數量選擇**：原本 `CreatePurchaseRequestService` 寫死每次搶購一定買到
   `purchaseLimitPerUser` 的滿額，使用者完全不能選數量。改成前端加數量輸入框（>0 且不超過限購
   數量），後端 `PurchaseController`/`CreatePurchaseRequestService` 改吃使用者送出的 quantity，
   服務層驗證超過限購會回 409。**注意**：這裡有一個容易踩的坑，`purchaseLimitPerUser` 在這個
   專案的 spec 裡明確定義是「同一張訂單的數量上限」，不是「可以下幾張訂單」——同一使用者對同一
   活動最多只有一張有效訂單，這是併發測試的核心不變量（design spec 第 317 行附近）。之前有一次
   review 曾經誤解成後者，被使用者抓到，詳見 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`。
3. **全站錯誤訊息中文化**：後端所有 exception 訊息（`NotFoundException`/`ConflictException`/
   `UnauthorizedException` 等）、Bean Validation 訊息（`@NotBlank`/`@Positive`/`@AssertTrue`
   等）、前端所有「載入中…」「無法載入 XXX」文案，原本全部是英文，混在中文介面裡很突兀。全部
   改成中文。error `code`（如 `PRODUCT_NOT_FOUND`）維持英文不動，因為那是程式判斷用的常數，不
   是給使用者看的文字。
4. **後端整合測試效能重構**（技術債，[[week5_plan_status]] 早就記錄過但一直沒做）：31 個
   `*IT.java` 測試類別，原本每個都各自啟動一組 Postgres/Redis/RabbitMQ container，Spring 的
   test context cache 完全沒辦法重複使用（因為每個類別的 `@DynamicPropertySource` 給的 port
   都不一樣），導致完整測試套件要跑 12-16 分鐘。做法：寫一個共用的
   `backend/src/test/java/com/flashsale/testsupport/AbstractIntegrationTest.java`，container
   只在整個測試 JVM 生命週期啟動一次，所有測試類別共用同一組 `@DynamicPropertySource`，讓
   Spring 可以重複使用 ApplicationContext。**過程中發現一個真實的架構限制，不是隨便可以無腦
   套用的**：有 9 個測試類別（`OutboxPublisherIT`、`OrderPurchaseConsumerIT`、
   `NotificationRetrySchedulerIT` 等）會斷言「真實的 `@RabbitListener`／`@Scheduled` 背景任務
   最終處理完的結果」，如果跟其他測試類別共用同一組容器，某個已經被 Spring cache 住、還活著的
   context 裡的背景執行緒（listener/scheduler）會在背景持續跑，跟這些測試手動輪詢訊息佇列的邏輯
   互搶訊息，導致間歇性測試失敗（實際重現過：`OutboxPublisherIT` 手動 `receive()` 拿到
   `null`，因為訊息已經被另一個 context 裡活著的 `OrderPurchaseConsumer` listener 搶走了）。
   最後的做法是：22 個「純 HTTP/service 層」的測試類別改用共用 container，這 9 個涉及真實非同步
   背景處理的測試類別刻意保留原本「各自獨立 container」的寫法，不勉強套用。結果：完整套件從
   12-16 分鐘降到穩定的 6-7 分鐘（跑了兩次確認結果一致、沒有 flaky）。這件事的完整脈絡寫在
   `AbstractIntegrationTest.java` 的 class-level javadoc 裡，之後如果想繼續把剩下 9 個也轉換
   過去，**必須先解決「共用 container 下背景任務互相干擾」這個根本問題**（可能的方向：測試專用
   的 profile 把 listener/scheduler 全部關掉，讓每個測試手動觸發要測的那個消費者/排程器，而不是
   依賴背景執行緒自動跑），不是單純的機械式改寫可以解決的，這也是為什麼這次選擇「部分轉換 +
   誠實記錄限制」而不是硬做完 31 個。

以上 4 件事都已經 commit、merge 回本地 `main`，並且已經 **push 到 `origin/main`**
（`e6387d0..91fea13`）。

## 已知問題／技術債清單（尚未處理，依重要性大致排序）

1. **`docs/superpowers/plans/2026-08-15-flash-sale-week6-visual-audit-findings.md`**：對照原始
   UI 設計稿（一份 claude.ai artifact，「搶購票根」主題）逐畫面核對後列出的 5 個視覺落差，最明顯
   的一項是 `frontend/src/features/purchase/PurchaseStatusPage.tsx` 的搶購結果印章文字目前是
   英文「PASS」/「STOP」，設計稿是中文「搶購成功」/「已售完」，跟頁面其他地方全中文的呈現不一致
   （其餘 4 項是次要的視覺打磨，該文件裡有詳細說明跟建議）。
2. **`ApiAuditFilter` 稽核不到 401/403**：這個 filter 註冊在 Spring Security filter chain
   之後，所以認證/授權失敗的請求（被 `AuthenticationEntryPoint`/`AccessDeniedHandler` 攔下的）
   永遠不會被稽核到。要修好需要重新設計 filter 註冊順序（可能要放到 Security chain 之前，但這樣
   會拿不到 `SecurityContextHolder` 的 userId），是架構決策，不是機械式修改。這是稽核記錄漏掉的
   剛好是事故排查時最想看的「失敗的登入/授權嘗試」，優先度不低。
3. **`outbox_events` 沒有 trace-context 欄位**：導致一次搶購請求的 Zipkin trace 在 RabbitMQ
   outbox 這個 hop 會斷成兩段不連續的 trace（HTTP 請求一段、`OutboxPublisher`→consumer 又是
   一段），不是真正端到端的分散式追蹤。README/spec 原本宣稱端到端追蹤，已經改成如實描述現況。
   要修好需要在 `outbox_events` 加一個欄位存 trace context，並在寫入/發布時傳遞。
4. **剩下 9 個 IT 測試類別還沒轉換成共用 container**（見上一節），根本問題是背景 listener/
   scheduler 互相干擾，需要先想清楚隔離策略才能繼續做。
5. **Week 6 final review 留下的幾個 Minor 問題**：`updateProduct` 這個 API client 函式前端目前
   沒有任何地方呼叫（只有 `createProduct`/`listProducts` 有用到）、`FlashSale` 後台管理頁面缺
   一個「編輯」的 UI（後端 API 已經有 `PUT` 端點，前端只做了新增+列表）、一個 CSS scoping 外漏
   的小問題、幾個測試斷言寫得比較弱。都不影響功能，優先度低。
6. **`PurchaseMetrics` 的失效路徑沒有指標**：Redis 掛掉時完全不會產生任何 metric 訊號，等於
   「靜默失敗」，事故發生時不容易第一時間從監控面板看出來。
7. **nginx 沒有 `/actuator/` 的 proxy 規則**：Actuator 端點目前只能從 Docker network 內部連到，
   從 host 打 `https://localhost:8443` 是連不到的。README 已經如實記錄這個限制，如果之後想從
   外部（例如串接真正的監控系統）存取 Actuator，需要補這條 nginx 規則（要考慮驗證機制，不能公開
   裸露）。

## 下一步建議：Week 7

按照這個專案一直以來的慣例（先寫 spec、使用者確認、再寫 plan、依 plan 實作），Week 7 應該涵蓋：

- README 補強（架構圖、API 使用範例、demo 帳號說明——部分內容其實已經在這次 session 過程中口頭
  或文件裡累積了一些素材，例如管理後台操作步驟已經在 README 裡了，但架構圖跟 API 範例還沒有）。
- 同步 vs 非同步效能比較（`load-tests/` 底下已經有 k6 壓測腳本，可以用來產生量化數據）。
- 設計取捨（trade-offs）/限制（limitations）說明文件——把上面「已知問題／技術債」這份清單，
  用比較正式、對外的方式寫成一篇文件，這對作品集本身是加分的（誠實揭露限制比假裝沒有問題更有
  說服力）。

如果要先處理技術債而不是 Week 7，優先度建議：**`ApiAuditFilter` 401/403 稽核缺口** >
**PASS/STOP 印章文字中文化**（很小的修改，見上面清單第 1 項）> 其餘。

## 開發慣例（給接手的人/AI 看）

- **文件語言**：`docs/` 底下的 spec、plan、`README.md`、程式碼註解一律用**繁體中文**寫（技術
  名詞可以保留英文，例如 API、Redis、trace）。跟使用者的對話也預設用中文回覆。
- **Git 慣例**：新工作開新分支（不要直接在 `main` 上改），驗證過（測試綠燈）才 merge 回
  `main`。**commit 跟 push 都需要使用者明確同意才能做**，不要自作主張推上去。
- **測試慣例**：後端用 JUnit 5 + Mockito（unit test）+ Testcontainers（integration test，檔名
  以 `IT.java` 結尾），前端用 Vitest + Testing Library。改動程式碼後，跑對應的測試套件確認綠燈
  再視為完成——尤其是後端的完整套件，因為 IT 測試會真的啟動 Docker container，跑起來要幾分鐘，
  不要跳過。
- **這個開發環境（Windows）的特殊狀況**：這台機器的 `node`/`npm`/`npx` 不在系統 PATH 上，前端
  相關指令要透過 Docker 執行，例如：
  ```
  docker run --rm -v "<專案的 frontend 絕對路徑>:/app" -w /app node:20-alpine node node_modules/vitest/vitest.mjs run
  ```
  （在 Windows 上如果用 git-bash 執行 `docker run` 且路徑用 `/app` 這種寫法，前面要加
  `MSYS_NO_PATHCONV=1` 環境變數，不然 git-bash 會把 `/app` 誤判成 Windows 路徑轉換掉。）
  後端的 Testcontainers 測試需要 Docker Desktop 有在跑。

## 這份文件之外，還有哪些歷史紀錄

如果想追更細的歷史（例如某個 review 為什麼做了某個判斷、某次 bug 是怎麼被抓到的），可以看：

- `docs/superpowers/plans/2026-08-15-week4-session-handoff.md`：Week 4 完成時的交接文件，格式
  跟這份類似。
- 每一週的 spec/plan 檔案本身（見上面「這是什麼專案」一節的路徑說明）。
- `git log --oneline` 的 commit 訊息大致都寫得算清楚，commit 訊息本身也是一種文件。
