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

## 目前進度（技術債六批已完成，Week 7 進行中）

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
- **Week 7（進行中）**：作品集包裝——README 補強、架構圖、API 使用範例、安全 demo 資料、目前系統
  的可重現負載特性、設計取捨、截圖與交接文件。已完成設計與 implementation plan（`d04590d`、
  `f7afaa3`）；尚未把這些作品集交付全部實作完成。

程式碼目前狀態：後端 159 個測試、前端 70 個測試皆為綠燈；CI 成功。上述測試與 CI 結果是 Week 7
implementation plan 指定同步的既有驗證基線；本次僅更新歷史文件，未重新執行完整套件或觸發 CI。

技術債 roadmap 的六批均已完成並保有對應 commit：Security 401／403 稽核（`74507fd`）、前端
401／403 處理（`bfa1a2c`）、整合測試背景任務隔離（`453ad2d`）、outbox trace context 與 Redis
失敗 metric（`5c94ee8`、`7098988`）、後台編輯與視覺打磨（`7b7b1ef`）、全服務健康度 dashboard
（`3da36a3`）。此外，訂單項目商品快照已完成（`b455374`、`a14a8a0`、`1a7cab4`、`62b0bec`）。

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

## 已完成技術債與目前限制

上述 roadmap 的六批與訂單項目商品快照均已完成；不得再把 401／403 稽核、前端授權錯誤、背景任務
隔離、outbox trace context、Redis 失敗 metric、後台視覺缺口、服務健康度或訂單商品快照列為未處理
限制。

目前應如實對外揭露的限制如下：

1. **本機自簽 TLS**：nginx 使用 localhost 的 self-signed certificate；瀏覽器與 `curl` 會顯示
   憑證警告，僅適合本機展示。
2. **僅 Docker Compose 示範**：系統以本機 Compose 環境提供可重現的展示，未建立公開 production
   部署、HA、監控告警或正式維運流程。
3. **不宣稱 production capacity**：後續 Week 7 只量測特定本機與 Docker 資源下的目前系統負載
   特性；結果不可解讀為 production SLA、RPS 或容量承諾。

## 下一步：持續 Week 7 作品集包裝

依已確認的 Week 7 設計與 implementation plan，接續完成安全且可重複的 demo 資料工具、架構／API／
trade-off 文件、隔離 benchmark harness 與實測證據、已清理敏感資訊的畫面截圖、README，以及最終
Week 7 handoff。負載報告只描述目前非同步系統，不比較歷史同步版本，也不主張 production capacity。

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
