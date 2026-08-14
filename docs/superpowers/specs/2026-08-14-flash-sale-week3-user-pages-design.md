# FlashSale Week 3(使用者操作頁面)設計規格

## 0. 關聯文件

本文件是 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`(以下稱「主規格」)§10、§15 Week 3 範圍的細部設計。API 契約已在 Week 2(`docs/superpowers/specs/2026-08-13-flash-sale-week2-purchase-core-design.md`)定案並實作完成,本輪**純前端**,不新增/修改任何後端 endpoint 或 DTO。

## 1. 範圍

包含(對應主規格 §15 Week 3):
- 註冊登入(Week 1 已有頁面,本輪補上跨頁面共用的登入狀態)
- 活動列表與詳情(Week 1 已有頁面,本輪在詳情頁加「搶購」按鈕)
- 搶購排隊結果輪詢頁(新增)
- 我的訂單:列表、詳情、模擬付款、取消(新增)
- 對應的前端測試(vitest + Testing Library,沿用既有慣例)

不包含(留給 Week 4+):
- 後台 `/admin` 任何頁面
- 通知中心(小鈴鐺)
- k6 壓測、Testcontainers、ArchUnit
- Actuator/tracing 前端呈現

## 2. 現狀盤點與缺口

現有前端(`frontend/src`):

```text
api/httpClient.ts    apiFetch() 統一帶 Authorization,accessToken 存 module-level 變數(非 React state)
api/authApi.ts       register/login/refresh/logout,login/refresh 成功會呼叫 setAccessToken()
api/flashSaleApi.ts  listFlashSales/getFlashSale
features/auth/       LoginPage, RegisterPage, useAuth()(local state hook)
features/flash-sales/FlashSaleListPage, FlashSaleDetailPage(目前無搶購按鈕)
router.tsx           createBrowserRouter,4 條路由
App.tsx              mount 時呼叫 authApi.refresh() 還原登入狀態,QueryClientProvider 包裹全站
```

兩個會擋到 Week 3 的既有缺口,本輪一併補:

1. **`useAuth()` 是純 local state**,每個呼叫它的元件各自有一份 `isAuthenticated`,彼此不同步。`App.tsx` 掛載時呼叫的 `refresh()` 目前沒有任何元件知道結果——LoginPage 自己 `login()` 成功才會把自己的 `isAuthenticated` 設 true,但重新整理頁面後,新的元件樹重新呼叫 `useAuth()` 又是一片空白,除非該元件自己也觸發過登入動作。這對 Week 1/2(只有公開頁 + 登入表單本身)沒差,但「我的訂單」需要一個**共用、可跨頁面判斷是否已登入**的狀態,否則無法做路由守衛或導頁。
2. **沒有需要登入才能進的路由**。Week 3 的「我的訂單」系列頁面必須登入,現在完全沒有這種守衛機制。

## 3. 認證狀態改為 Context 共用

把 `useAuth()` 的內部實作從純 `useState` 改成 `React.Context`(`AuthContext` + `AuthProvider`),對外的 hook 簽章維持不變(`{ isAuthenticated, login, logout }`),既有呼叫方(`LoginPage`、`RegisterPage`)完全不用改。

- `AuthProvider` 包在 `App.tsx` 的 `QueryClientProvider` 內層,`isAuthenticated` 狀態提升到這裡。
- `App.tsx` 掛載時呼叫 `authApi.refresh()`,成功時透過 context 把 `isAuthenticated` 設為 `true`(取代目前呼叫了但結果沒人知道的狀況)。
- 新增 `RequireAuth` 元件:未登入時 `<Navigate to="/login" state={{ from: location }} replace />`,`LoginPage` 登入成功後若 `location.state.from` 存在就導回原本要去的頁面,否則導回 `/`。

## 4. 頁面地圖(routes)

```text
/                              FlashSaleListPage           既有,不變
/flash-sales/:id               FlashSaleDetailPage         既有,加「搶購」按鈕
/register                      RegisterPage                既有,不變
/login                         LoginPage                   既有,加登入後導回 from
/purchase-requests/:requestId  PurchaseStatusPage          新增,RequireAuth
/orders                        MyOrdersPage                新增,RequireAuth
/orders/:orderId               OrderDetailPage             新增,RequireAuth,含付款/取消
```

`FlashSaleListPage` 加一個到 `/orders` 的導覽連結(頁首或頁尾皆可,不特別設計 nav bar 元件,維持現有極簡風格)。

## 5. 搶購流程

`FlashSaleDetailPage` 新增「搶購」按鈕:

- 未登入:導向 `/login`,帶 `state={{ from: '/flash-sales/{id}' }}`。
- `flashSale.status !== 'ACTIVE'`:按鈕 disabled,不送出請求(後端仍是最終判斷,前端只是避免無意義的請求)。
- 點擊:
  1. 用 `crypto.randomUUID()` 產生 `Idempotency-Key`(每次點擊產生新的一組——不是每個 flash sale 固定一組,重點只是避免同一次點擊因網路重試被算成兩次購買,不是要跨 session 記憶)。
  2. `POST /api/flash-sales/{id}/purchase-requests`,成功(202)後 `navigate('/purchase-requests/' + requestId)`。
  3. 送出中 disable 按鈕,避免重複點擊(即使有 Idempotency-Key 保護,重複點擊仍會產生多筆不同 key 的請求,徒增後端負擔)。

`PurchaseStatusPage`:

- `useQuery({ queryKey: ['purchase-requests', requestId], queryFn: ..., refetchInterval: (query) => 終局狀態 ? false : 1000 })`。
- 狀態對應顯示文字:
  - `PENDING`:「搶購處理中,請稍候…」
  - `SUCCEEDED`:「搶購成功!」+ 連到 `/orders/{orderId}` 的連結(`orderId` 來自輪詢回應)
  - `SOLD_OUT`:「很抱歉,商品已售完」
  - `REJECTED`:「您已經購買過這個活動的商品」
  - `FAILED`:「訂單建立失敗,系統已自動釋放您的庫存扣減,請重新嘗試搶購」(對應 Task 8 DLQ 補償路徑)
- **不設前端逾時**:後端的 outbox/consumer/DLQ 補償設計保證最終會到達某個終局狀態(見 Week 2 design spec §5),沒有「永遠卡住」的正常情境,前端沒必要自己加一個會製造假錯誤訊息的 timeout。停止輪詢的唯一條件是狀態變成上述四種終局狀態之一。

## 6. 我的訂單

`MyOrdersPage`:`GET /api/orders/me` 列表,每列顯示 `orderNo`、`totalAmount`、`status`,點列連到 `/orders/{id}`。空清單顯示「尚無訂單」。

`OrderDetailPage`:`GET /api/orders/{orderId}`,顯示 `orderNo`、`totalAmount`、`status`、`paymentDueAt`。

- `status === 'PENDING_PAYMENT'` 時顯示:
  - 「付款倒數」:`paymentDueAt` 純前端顯示用(主規格 §10:「前端倒數只供顯示;活動有效性一律由後端判斷」),實際逾時由 Week 2 的 `PaymentTimeoutScheduler` 認定,前端倒數只是體驗,不做任何客戶端強制動作(不因為前端倒數歸零就 disable 按鈕或自動導頁——後端才是真相,倒數歸零後使用者仍可能在畫面上按下付款,由後端回應決定成敗)。
  - 「模擬付款」兩個按鈕:成功 / 失敗,呼叫 `POST /api/orders/{orderId}/payments { result: 'SUCCESS' | 'FAILURE' }`,成功後用回應更新畫面(不用重新 fetch,mutation 回傳的就是最新 `OrderDetail`)。
  - 「取消訂單」按鈕,呼叫 `POST /api/orders/{orderId}/cancel`。
- 其他狀態(`PAID`/`CANCELLED`/`EXPIRED`)唯讀顯示,不出現任何操作按鈕。

## 7. API Client 新增

`api/purchaseApi.ts`:
```ts
createPurchaseRequest(flashSaleId: number, idempotencyKey: string): Promise<{ requestId: string; status: string; orderId: number | null }>
getPurchaseRequest(requestId: string): Promise<{ requestId: string; status: string; orderId: number | null }>
```

`api/orderApi.ts`:
```ts
listMyOrders(): Promise<OrderSummary[]>
getOrder(orderId: number): Promise<OrderDetail>
cancelOrder(orderId: number): Promise<OrderDetail>
submitPayment(orderId: number, result: 'SUCCESS' | 'FAILURE'): Promise<OrderDetail>
```

型別對應後端 DTO(`PurchaseRequestView`、`OrderSummary`、`OrderDetail`,見 `backend/src/main/java/com/flashsale/order/application/dto/`),欄位命名直接照後端 JSON(camelCase 一致,不用額外轉換層)。

## 8. 前端測試策略

沿用既有慣例(`FlashSaleListPage.test.tsx`):`vi.spyOn` mock API 模組函式,`QueryClientProvider` + `MemoryRouter` 包裹,`render` + `waitFor` 斷言畫面內容,不起真的後端/MSW。涵蓋:

- `FlashSaleDetailPage`:搶購按鈕點擊後呼叫 `createPurchaseRequest` 並 navigate 到正確路徑;未登入時點擊導向 `/login`。
- `PurchaseStatusPage`:`PENDING` 顯示處理中文字;`SUCCEEDED` 顯示成功文字與訂單連結;停止輪詢(mock `getPurchaseRequest` 呼叫次數在終局狀態後不再增加)。
- `MyOrdersPage`:渲染列表、空清單文案。
- `OrderDetailPage`:`PENDING_PAYMENT` 顯示付款/取消按鈕且點擊呼叫對應 API 並更新畫面;非 `PENDING_PAYMENT` 狀態不顯示操作按鈕。
- `RequireAuth`:未登入導向 `/login` 並保留原目標路徑。

不做:E2E(Playwright/Cypress)、視覺回歸測試——主規格 §14 沒有要求,YAGNI。

## 9. 對既有程式碼的影響

- `router.tsx`:加 3 條新路由,`/orders`、`/orders/:orderId`、`/purchase-requests/:requestId` 包 `RequireAuth`。
- `useAuth.ts`:內部改用 Context,對外簽章不變。
- `App.tsx`:加 `AuthProvider` 包裹,`refresh()` 成功時更新 context。
- `LoginPage.tsx`:登入成功後導頁邏輯從固定 `navigate('/')` 改成看 `location.state?.from`;另外讀取 `location.state?.email` 帶入 email 欄位預設值(見 §11.4)。
- `RegisterPage.tsx`:註冊成功後 `navigate('/login', { state: { email: values.email } })`,取代原本不帶 state 的 `navigate('/login')`。
- `FlashSaleDetailPage.tsx`:加搶購按鈕與送出邏輯。
- 均為既有檔案的增量修改,不重寫既有已通過測試的行為(`FlashSaleListPage` 版面內容不變,只套新樣式)。

## 10. 樣式與其他刻意不做的事

- **不做樂觀更新(optimistic update)**。付款/取消都是「使用者主動觸發、後端立即同步回應」的操作(不像搶購結果需要輪詢等非同步 consumer),直接等 mutation 回應更新畫面即可,不需要 TanStack Query 的 optimistic update 複雜度。
- **不做 WebSocket/SSE**。輪詢間隔 1 秒對展示用途已經足夠即時,主規格全文没有要求即時推送,加雙向連線是規格外的複雜度。
- **不裝 Tailwind/元件庫**。§11 的視覺方向已經用一組完整的 CSS design token 定案(顏色、字型、間距全部有規則可循),用純 CSS + class 就能一致地套用到每個頁面,不需要為此裝建置工具鏈或執行期依賴——這點取代並修正了本文件初版「不引入 UI/CSS 框架」的決定,原因見 §11.0。

## 11. 視覺設計系統(票根 Design System)

### 11.0 背景

初版本文件曾決定「不引入 UI/CSS 框架,維持瀏覽器預設樣式」,理由是「面試作品集重點是架構正確性,不是視覺」。實際做完 Task 1-5 後,使用者反饋介面「太陽春」——`frontend/src/index.css` 其實從頭到尾都是 Vite 專案樣板的預設樣式(紫色 `--accent: #aa3bff`、`#root` 固定寬度 1126px),從未真正客製過,不是「刻意極簡」而是「沒設計過」。這裡修正決定:加一套視覺設計系統,但不裝任何新的 npm 依賴(見下方 11.1 的取捨)。

### 11.1 設計方向:入場票根(ticket stub)

以「限量搶購 = 排隊領票」的體驗做視覺隱喻——票根紙質底色、剪票口造型的 logo、卡片用虛線分隔像撕票線、搶購結果用「已核可/已售完」風格的印章呈現。方向已透過一份涵蓋全部 10 個畫面狀態的靜態 HTML 提案確認,實作以此為準。

### 11.2 Design tokens

CSS custom properties,定義在新檔案 `frontend/src/styles/tokens.css`(全站唯一色彩/字型/間距來源,元件樣式一律吃 token,不允許寫死色碼):

```css
--ink / --paper / --paper-raised / --line / --line-strong / --muted   /* 中性色階 */
--stub / --stub-hover / --stub-ink                                    /* 主色:入場章紅橙 */
--go / --go-tint    /* 語意色:進行中/成功/已完成 */
--wait / --wait-tint /* 語意色:等待中/即將開始 */
--stop / --stop-tint /* 語意色:已結束/失敗/取消 */
--font-display  /* Archivo Black(webfont,見 11.3) */
--font-ui       /* system-ui 疊字型,中文用 */
--font-mono     /* 訂單編號/金額/倒數計時等 tabular 數字用 */
```

深色模式:`@media (prefers-color-scheme: dark)`(guard `:root:not([data-theme="light"])`)+ 使用者若之後加主題切換,`:root[data-theme="dark"]` 同步覆寫——本輪沒有主題切換 UI,先把 token 結構準備好。

狀態色彩對應(貫穿全站,取代目前直接印 enum 原文的作法):

| 後端 enum | 語意色 | 顯示文字 |
|---|---|---|
| `FlashSale.status = ACTIVE` | go | 搶購中 |
| `= SCHEDULED` | wait | 即將開賣 |
| `= ENDED` | stop | 已結束 |
| `PurchaseRequest.status = SUCCEEDED` | go | 搶購成功 |
| `= PENDING` | wait | 搶購處理中 |
| `= SOLD_OUT` / `REJECTED` / `FAILED` | stop | 依 §5 原文案 |
| `Order.status = PAID` | go | 已付款 |
| `= PENDING_PAYMENT` | wait | 待付款 |
| `= CANCELLED` / `EXPIRED` | stop | 已取消 / 已逾期 |

### 11.3 字型

顯示用字重(logo、活動名稱、搶購結果印章文字)內嵌一支開源字型 Archivo Black,僅取 Latin 基本字元(不含中文,中文一律走系統字),用 `fonttools` 裁到 ~9KB 後轉 woff2、`@font-face` base64 內嵌(不連外部字型 CDN——Artifact 環境本來就會擋外部請求,實際部屬到 Nginx 後也沒有連外部落點的理由)。內文與所有中文一律用系統字疊字型(`-apple-system, "Segoe UI", "PingFang TC", "Microsoft JhengHei", system-ui`),金額/訂單編號/倒數計時用等寬字(`ui-monospace` 疊字型)+ `font-variant-numeric: tabular-nums`。

### 11.4 響應式(RWD)

Mobile-first,單欄流式版面為基礎(這是購物類 app,手機是主要情境),斷點用 `min-width` 往上疊加:

- **< 640px(預設)**:所有頁面單欄,寬度 100% 減 page padding。
- **≥ 640px**:表單類頁面(登入/註冊)維持置中卡片、限制 `max-width`,不要求全寬拉伸到滿版難看。
- **≥ 900px**:列表類頁面(活動列表、我的訂單)從單欄卡片改多欄 grid(`repeat(auto-fill, minmax(...))`),善用桌面空間;詳情類頁面(活動詳情、訂單詳情)維持單欄但置中,設 `max-width` 避免內文行寬超過易讀範圍。

不做斷點特化的元件邏輯(例如手機版/桌面版切換不同元件樹)——純 CSS 排版差異即可涵蓋所有頁面,沒有需要 JS 判斷視窗寬度的情境。

### 11.5 共用元件

從既有各自為政的 markup 抽出 2 個共用元件,放 `frontend/src/components/`:

- `AppNav`:目前只有 `FlashSaleListPage` 有 logo/我的訂單/登出這排,`MyOrdersPage`、`FlashSaleDetailPage` 應該也要有(訂單詳情、搶購結果頁維持極簡不放,呼應 §11.1 提案的版型)。抽成元件後三個頁面共用,不各自複製一份。
- `StatusPill`:輸入 enum 字串,輸出對應語意色 + 中文文字的 pill(表 11.2)。取代目前 `FlashSaleListPage`/`OrderDetailPage` 直接印 `{sale.status}`/`{data.status}` 原始 enum 文字的作法。

### 11.6 註冊後自動帶入登入帳號

`RegisterPage` 成功後導向 `/login` 時,把剛註冊的 email 透過 `navigate('/login', { state: { email } })` 帶過去;`LoginPage` 用 `location.state?.email` 當 email 欄位的 `defaultValue`(react-hook-form 的 `useForm({ defaultValues: { email: prefillEmail } })`),使用者不用重打一次剛輸入過的帳號。若沒有 `state.email`(直接訪問 `/login`)則維持空白,不影響現有行為。
