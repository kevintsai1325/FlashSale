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
- `LoginPage.tsx`:登入成功後導頁邏輯從固定 `navigate('/')` 改成看 `location.state?.from`。
- `FlashSaleDetailPage.tsx`:加搶購按鈕與送出邏輯。
- 均為既有檔案的增量修改,不重寫既有已通過測試的行為(`RegisterPage`、`FlashSaleListPage` 不動)。

## 10. 樣式與其他刻意不做的事

- **不引入 UI/CSS 框架**。既有頁面全是無樣式的語意化 HTML(`<article>`、`<label>`、`role="alert"`),Week 3 維持同樣風格,只用瀏覽器預設樣式——面試作品集的重點是架構與正確性,不是視覺,加框架是本輪範圍外的裝飾性工作。
- **不做樂觀更新(optimistic update)**。付款/取消都是「使用者主動觸發、後端立即同步回應」的操作(不像搶購結果需要輪詢等非同步 consumer),直接等 mutation 回應更新畫面即可,不需要 TanStack Query 的 optimistic update 複雜度。
- **不做 WebSocket/SSE**。輪詢間隔 1 秒對展示用途已經足夠即時,主規格全文没有要求即時推送,加雙向連線是規格外的複雜度。
