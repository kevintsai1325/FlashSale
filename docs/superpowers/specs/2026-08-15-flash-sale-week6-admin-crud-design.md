# FlashSale Week 6(後台商品/搶購活動 CRUD、修 bug、視覺補齊、技術債)設計規格

## 0. 關聯文件

本文件是 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`(以下稱「主規格」)§2「第二階段」
所列「後台建立與編輯商品、搶購活動」的細部設計,這次選擇把它從第二階段提前到 Week 6 做。原本主規格
§15 定義的 Week 6(作品集包裝——README、架構圖、效能比較、設計取捨)**順延成 Week 7**,不取消。

本輪範圍是在一次手動驗證(啟動最新 build、實際跑過註冊/登入/搶購流程)時發現的三個問題,加上一個
既有已知技術債清單觸發的:

- 註冊/登入頁「驗證訊息不明確」與搶購頁「Flash sale is not currently active」兩個 bug,已在本輪
  規劃前修復完成(見 §2.1、§2.2)——記錄進本文件是為了讓 Week 6 的完成範圍與 commit 歷史一致,不是
  重新設計。
- 目前後台完全沒有商品/搶購活動的建立/編輯功能,導致這兩個 bug 修完後仍然沒辦法建立新資料來驗證
  搶購流程(見 §2.3)——這是本輪主要新增的功能,詳細設計見 §3-§5。
- 消費端畫面跟 Week 3 「搶購票根」設計提案有明顯落差,只抽查 3/10 個畫面就已經找到具體缺漏(見
  §2.4、§6)。
- `docs/superpowers/plans/2026-08-15-flash-sale-week5-observability-ci.md` 執行過程中記錄的兩項
  技術債(§7)。

## 1. 範圍

包含:

- 商品(`products`)後台 CRU(建立、列表、編輯,不含刪除)。
- 搶購活動(`flash_sales` + `inventory`)後台 CRU(建立含庫存數量、列表、編輯,不含刪除)。
- 後台新增 `/admin/products`、`/admin/flash-sales` 兩個管理頁面。
- 消費端畫面補齊「搶購票根」設計提案的落差(至少涵蓋 §2.4 已發現的兩項,並完成尚未核對的 7 個
  畫面狀態比對)。
- Week 5 執行過程記錄的兩項技術債(視時間狀況排入,§7 已標記優先序)。

明確不包含(維持主規格 §2「明確不包含」的既有立場,不在本輪重新討論):

- 商品/搶購活動的刪除——牽涉既有搶購活動/訂單的外鍵參照,刪除語意(級聯?阻擋?軟刪除?)本身
  就是獨立的設計問題,主規格也沒有這個需求,不在本輪新增。
- 活動「手動提前下架」以外的生命週期管理(例如暫停/恢復)——經過確認,活動狀態維持純時間區間
  計算(這正是 §2.2 那個 bug 修復的精神),不额外疊加人工干預的狀態機。
- Prometheus/Grafana、SMS/站內通知——主規格「第二階段」項目,本輪仍不處理。
- README、架構圖、效能比較、設計取捨文件——順延至 Week 7,不在本文件範圍。

## 2. 現狀盤點

### 2.1 已修復:註冊/登入驗證訊息不明確

`RegisterPage.tsx`、`LoginPage.tsx` 的 `<form>` 都沒有設定 `noValidate`,導致瀏覽器原生 HTML5
表單驗證搶在 react-hook-form + Zod 的驗證邏輯之前攔截送出動作——畫面上看到的是瀏覽器自己的系統
提示框,不是程式碼寫的訊息(這兩個表單的 Zod schema 其實已經有清楚的錯誤訊息,只是根本沒機會顯示)。
用 Playwright 對實際瀏覽器重現、用 jsdom 單元測試重現(`RegisterPage.test.tsx`/`LoginPage.test.tsx`
新增的案例先 RED 後 GREEN)雙重確認。

修復:兩個 `<form>` 都加上 `noValidate`。

### 2.2 已修復:「Flash sale is not currently active」與列表顯示矛盾

`FlashSale.status` 欄位只有在 `FlashSale.schedule()` 建立當下寫入一次(`SCHEDULED`),整個後端
沒有任何地方會依照時間把它轉成 `ACTIVE`/`ENDED`。資料庫裡既有的測試資料是先前手動用 SQL 把
`status` 改成 `ACTIVE`,時間區間(`startsAt`/`endsAt`)早已過期,導致列表頁一直顯示「搶購中」,
點進去按搶購才被 `CreatePurchaseRequestService`(用真正的時間區間判斷)打回票。前端詳情頁的搶購
按鈕(`FlashSaleDetailPage.tsx:85`)也是直接信任這個不可靠的 `status` 欄位,完全沒用它自己算出來
的倒數計時去判斷。

修復:`FlashSale` 新增 `effectiveStatus(Instant now)`,依 `startsAt`/`endsAt` 即時計算
`SCHEDULED`/`ACTIVE`/`ENDED`,邏輯與既有的 `isPurchasableAt()` 一致。`FlashSaleQueryService` 的
`listAll()`/`getDetail()` 改呼叫這個方法,不再讀取永遠不會過期的舊 `status` 欄位。前端完全不用
改——詳情頁按鈕本來就是讀這個 API 回傳的 `status`,後端一修好就自動同步。

### 2.3 現狀:後台沒有任何商品/搶購活動管理功能

`FlashSaleController`(`backend/src/main/java/com/flashsale/flashsale/adapter/web/
FlashSaleController.java`)只有兩個 `@GetMapping`(列表、詳情),沒有任何 `@PostMapping`/
`@PutMapping`。`router.tsx` 的 `/admin/*` 路由只有 dashboard、orders、notifications、api-logs,
沒有活動或商品管理頁面。目前資料庫裡的搶購活動、商品資料,都是先前開發時直接下 SQL 建立的——這正是
§2.2 那個 bug 的資料來源,也是這次要補的功能缺口。

### 2.4 已發現的視覺設計落差(範例,§6 有完整任務範圍)

- `App.tsx:21` 有一個完全沒套用設計系統樣式的 `<h1>FlashSale</h1>`,浮在每一頁最上方——每一頁
  其實都已經有自己的 `AppNav`(內含「FLASH SALE」票根徽章樣式),這個 `<h1>` 是 Week 1 MVP 的
  殘留,從未清除。
- `tokens.css` 已經把設計提案的色票/CSS 變數完整搬進來,但客製字體(Archivo Black)被跳過,
  退回系統 Arial Black(`tokens.css` 內有註解說明)。

## 3. 商品(Product)管理 API

新增 `backend/src/main/java/com/flashsale/admin/adapter/web/AdminProductController.java`,比照
既有 `AdminOrderController` 的模式(`@RestController` + `@RequestMapping("/api/admin/products")`,
授權沿用 `SecurityConfig` 既有的 `/api/admin/**` → `hasRole("ADMIN")` 規則,不需要改
`SecurityConfig`):

- `POST /api/admin/products`:建立商品。Request body:`name`(必填、非空白)、
  `description`(選填)。回傳建立後的商品(id、name、description)。
- `GET /api/admin/products`:列出所有商品(給 §4 建立搶購活動時選擇商品用,也給管理頁面本身列表用)。
- `PUT /api/admin/products/{id}`:編輯商品的 `name`/`description`。商品沒有時間窗、沒有生命週期
  狀態,不像搶購活動需要區分「已開始/未開始」,任何時候都可以編輯。

新增 `backend/src/main/java/com/flashsale/admin/application/AdminProductService.java`,呼叫既有
`catalog.application.ProductRepository`(§2.3 已確認存在,沿用不改)。`catalog.domain.Product`
（`backend/src/main/java/com/flashsale/catalog/domain/Product.java`）目前只有 `create()` 靜態
工廠、沒有編輯方法,依既有的意圖導向命名風格新增 `rename(String name, String description)`
實例方法(不新增裸 setter)。

## 4. 搶購活動(FlashSale)管理 API

新增 `AdminFlashSaleController`(`/api/admin/flash-sales`),同樣沿用 `/api/admin/**` 的既有授權:

- `POST /api/admin/flash-sales`:建立搶購活動。Request body:`productId`、`salePrice`、
  `startsAt`、`endsAt`、`purchaseLimitPerUser`、`totalQuantity`(庫存數量——建立時同時寫入
  `flash_sales` 與 `inventory` 兩張表,兩者在同一個 `@Transactional` 方法內一起寫入,避免建立
  一半的活動沒有庫存列)。
- `GET /api/admin/flash-sales`:管理頁面用的列表(可以直接複用 §「現狀」已有的
  `FlashSaleQueryService.listAll()`,不需要另開一份給 admin 專用——目前的 `FlashSaleSummary` 對
  管理頁面來說欄位已經夠用)。
- `PUT /api/admin/flash-sales/{id}`:編輯。驗證分兩層:
  - **欄位格式**(比照 `RegisterRequest` 既有的 Bean Validation 慣例,`@Positive`/`@NotNull`
    等註解 + `GlobalExceptionHandler` 既有的 `MethodArgumentNotValidException` 處理,回
    `400 VALIDATION_ERROR`):`startsAt < endsAt`、`salePrice > 0`、`purchaseLimitPerUser > 0`、
    `totalQuantity > 0`。
  - **狀態衝突**(比照現有 `ConflictException` 的錯誤慣例,例如 `CreatePurchaseRequestService`
    拋的 `FLASH_SALE_NOT_ACTIVE`,回 `409 Conflict`):若
    `effectiveStatus(Instant.now())` 已經是 `ACTIVE` 或 `ENDED`(即 `now >= startsAt`),只允許
    把 `endsAt` 改成一個 `>= now` 且 `<= 原本的 endsAt` 的新值(對應「提前結束」);其餘欄位
    (`salePrice`/`totalQuantity`/`startsAt`/`purchaseLimitPerUser`)一律拒絕修改,回
    `FLASH_SALE_ALREADY_STARTED`。若還是 `SCHEDULED`(`now < startsAt`):所有欄位都可以自由
    修改,不觸發這層檢查。

新增 `AdminFlashSaleService`(`admin/application/`),呼叫既有 `FlashSaleRepository`/
`InventoryRepository`。`FlashSale`(`backend/src/main/java/com/flashsale/flashsale/domain/
FlashSale.java`)依既有風格新增兩個意圖導向方法,而不是暴露欄位級 setter:

```java
public void reschedule(BigDecimal salePrice, Instant startsAt, Instant endsAt, int purchaseLimitPerUser) {
    // 前置條件由呼叫端(AdminFlashSaleService)保證只在 SCHEDULED 狀態呼叫
}

public void endEarly(Instant newEndsAt, Instant now) {
    // 前置條件:newEndsAt >= now && newEndsAt <= this.endsAt,由呼叫端驗證
}
```

`Inventory` 的初始庫存數量調整(僅限 `SCHEDULED` 階段編輯時)沿用既有 `Inventory` 領域類別已有的
方法,不重新設計(既有測試已確認 `Inventory.initialize(flashSaleId, quantity)` 這類工廠方法
已存在)。

## 5. 前端後台管理頁面

新增 `frontend/src/features/admin/AdminProductsPage.tsx`、`AdminFlashSalesPage.tsx`,比照既有
`AdminOrdersPage.tsx` 的「表格 + 表單」風格(admin 頁面用既有的中性後台樣式,**不**套用 §6 消費端
的搶購票根視覺——這是後台工具頁面,跟消費端品牌呈現是兩回事,主規格既有的管理後台頁面本來就是
這個定位)。`AdminNav.tsx` 加兩個連結。`router.tsx` 加兩個路由(`/admin/products`、
`/admin/flash-sales`),沿用既有的 `RequireAdmin` 路由守衛。

搶購活動建立表單需要一個商品下拉選單(呼叫 §3 的 `GET /api/admin/products`)——如果商品列表是空的,
表單要提示「請先建立商品」,不做「建立活動時順便建立商品」的複合表單(YAGNI,兩個獨立表單已經
夠用,復合表單會讓驗證邏輯變複雜卻沒有對應的明確需求)。

## 6. 消費端視覺設計補齊

§2.4 已確認的兩項直接修:

- 刪除 `App.tsx:21` 的 `<h1>FlashSale</h1>`。
- 補上「搶購票根」設計提案裡的客製字體(`Archivo Black`),取代目前退回的系統 Arial Black——
  字型檔本身在設計提案的產出物裡已經有(base64 內嵌的 woff2),`tokens.css` 的既有註解也提到
  「已經產生過一次,問 controller session 有沒有」,這次直接把字型檔案落地成
  `frontend/public/fonts/archivo-black-sub.woff2`(或等效路徑),`tokens.css` 的
  `@font-face` 改指向真正的檔案而不是退回 fallback。

其餘 7 個畫面狀態(活動詳情、輪詢中/成功/失敗、我的訂單列表、訂單詳情、空狀態)比照設計提案逐一
核對現有實作——設計稿原始檔是 claude.ai 上的一份 artifact,不在版控裡,執行這個任務時需要重新
fetch 一次(URL 見本次規劃對話紀錄,或請使用者重新提供)——列出落差清單再決定要不要每個都修,
不預先假設全部都要改,有些畫面可能已經套用得夠好。

## 7. 技術債優化

延續 Week 5 執行完後記錄的兩項:

1. **共用 Testcontainers context**(優先):29 個 `*IT.java` 各自宣告獨立的
   `@DynamicPropertySource`,導致 Spring test context cache 完全無法重複利用,每個測試類別都要
   重開一次完整應用程式——這是目前完整測試套件要跑 12-24 分鐘的根本原因,Week 5 的
   `maxHeapSize`/`spring.test.context.cache.maxSize` 調整只是權宜緩解,沒有解決重複開機的問題。
   做法:建一個共用的 `AbstractIntegrationTest` base class(或改用 Spring Boot 3.1+ 的
   `@ServiceConnection` 搭配 `@Testcontainers(disabledWithoutDocker = true)` 的單例容器模式),
   讓 29 個 IT 類別共用同一組容器與 Spring context。這個改動預期會動到全部 29 個檔案,範圍
   不小,執行時可能需要拆成自己的一個或多個 SDD task,不跟 §3-§6 混在一起做。
2. **outbox trace context 傳遞**(次要):`outbox_events` 沒有欄位保存觸發它的 HTTP 請求的
   trace context,導致 `OutboxPublisher.publishPending()`(排程執行、沒有上游 span)送出的
   RabbitMQ 訊息會起一條全新的 trace,跟原本觸發它的 HTTP 請求 trace 斷開——Zipkin 上看到的是
   兩條不相干的 trace,不是一條完整呼叫鏈。做法:`outbox_events` 加一個 `trace_id` 欄位(或直接
   存整個 W3C traceparent 字串),寫入時從 `Tracer.currentSpan()` 讀取,`OutboxPublisher` 送出
   訊息前手動把這個 trace context 塞進訊息 header,讓 `OrderPurchaseConsumer` 收到後可以延續同一
   條 trace(Micrometer Tracing 有提供手動傳遞 context 的 API,不需要自己刻)。

兩項都視 Week 6 剩餘時間狀況排入,§1 已經標記為「不確定會不會做完的」——如果時間不夠,依照主規格
一貫的「優先保留正確性與可操作流程」原則,§3-§6 優先於這兩項。

## 8. 測試策略

比照既有慣例:

- `AdminProductService`/`AdminFlashSaleService` 的驗證規則(尤其是 §4 的「已開始後只能提前結束」
  邊界條件)用純 Mockito 單元測試涵蓋,不需要每個案例都是 Testcontainers IT。
- Controller 層至少一個 IT 涵蓋「非 ADMIN 打 403、ADMIN 打通」的授權邊界,比照
  `ActuatorHealthIT`/`AdminApiLogControllerIT` 既有的驗證方式(register+login、DB 直接把 role
  改 ADMIN、重新登入拿到真正帶 ADMIN claim 的 JWT)。
- 前端新增頁面各自的表單驗證(必填、數字範圍)用 vitest + Testing Library,比照既有
  `RegisterPage.test.tsx` 的風格,不用 e2e。
- §6 的視覺補齊沒有自動化測試(比照 Week 5 spec §10 的既有立場——視覺呈現的正確性用人眼核對,不
  硬做像素比對的自動化測試)。
