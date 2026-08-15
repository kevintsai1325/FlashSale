# Task 6 — 消費端其餘畫面狀態與設計稿核對:落差清單

**方法說明**:原計畫 Step 2 建議用 Playwright/瀏覽器實際截圖比對。改用更精確的方式——直接把設計稿
(claude.ai artifact「搶購票根」)的完整 HTML/CSS 跟實際頁面原始碼(component + CSS)逐一比對。
這個方法對於「文字內容對不對」「元素有沒有漏做」這類落差,比螢幕截圖肉眼比對更精確、更快,且不需要
重新啟動 docker stack。目視/像素級的排版比對(間距、字重觀感等)如果需要,仍建議之後找時間實際
跑一次瀏覽器驗證——這份清單不涵蓋那個層次。

設計稿共 11 個畫面狀態(說明文字寫「10 個」,但實際數了 11:活動列表、活動詳情、登入、註冊、
處理中、搶購成功、已售完、訂單列表、訂單列表空狀態、訂單詳情待付款、訂單詳情已完成)。§2.4
(design spec)已核對過活動列表/活動詳情/登入 3 個,這裡涵蓋其餘 8 個。

## 整體結論

配色、字型變數(`--paper`/`--ink`/`--go`/`--wait`/`--stop`/`--shadow` 等)、按鈕樣式
(`.btn-primary`/`.btn-outline-go`/`.btn-outline-stop`/`.btn-ghost`)、狀態標籤系統
(`StatusPill.tsx` 的 `pill go/wait/stop` 三色系統跟中文文案)都已經正確採用設計提案的 token,
視覺基調是對的。落差集中在**特定畫面的內容/元素缺漏**,不是整體風格跑掉。

## 具體落差(按嚴重程度排序)

### 1. 搶購結果印章文字是英文,不是中文(明顯落差)

`frontend/src/features/purchase/PurchaseStatusPage.tsx:35-36`:
```tsx
{data.status === 'SUCCEEDED' && <div className="stamp go" aria-hidden="true">PASS</div>}
{NON_SUCCESS_TERMINAL_STATUSES.has(data.status) && <div className="stamp stop" aria-hidden="true">STOP</div>}
```
設計稿的印章文字是**中文**——搶購成功顯示「搶購成功」、已售完顯示「已售完」,直接印在那個旋轉
邊框印章上(這是這個畫面視覺上最醒目的元素)。實際畫面印章顯示的是英文「PASS」/「STOP」,跟頁面
其他地方(下方的 `status-message` 說明文字)全中文的呈現不一致,也偏離設計稿本來的樣子。

**建議**:把印章文字改成中文「搶購成功」/「已售完」,跟設計稿一致,也跟頁面其他文案語言統一。

### 2. 訂單列表空狀態缺一行副標文字

`frontend/src/features/orders/MyOrdersPage.tsx:17-21`:
```tsx
<div className="orders-empty">
  <div className="ticket-icon" aria-hidden="true" />
  <p>尚無訂單</p>
</div>
```
設計稿的空狀態是兩行:標題「尚無訂單」+ 說明「搶購成功後會出現在這裡」。實際畫面只有標題,少了
說明那一行。

**建議**:加一行 `<p className="...">搶購成功後會出現在這裡</p>`(次要文字樣式,參考
`orders-empty` 既有的 `color: var(--muted)`)。

### 3. 訂單詳情頁的付款期限沒有用倒數提示的視覺樣式

`frontend/src/features/orders/OrderDetailPage.tsx:51`:
```tsx
{data.paymentDueAt && <p>付款期限：{new Date(data.paymentDueAt).toLocaleString()}</p>}
```
設計稿這個資訊是用 `.countdown-strip`(`--wait-tint` 背景色的提示條,視覺上明顯強調「這是有時效
性的資訊」),實際畫面只是一行普通段落文字,跟頁面其他文字沒有視覺區別,容易被忽略——付款期限
是這個畫面最重要的資訊,理論上應該最顯眼。

**建議**:比照 `FlashSaleDetailPage.tsx` 已經在用的 `.countdown-strip` 樣式(該元件已經有
`countdown-strip` 這個 class 可以直接複用),把付款期限包進同樣的視覺容器。

### 4. 搶購處理中畫面缺少 request id 顯示

`frontend/src/features/purchase/PurchaseStatusPage.tsx:32-37` 的處理中狀態沒有顯示任何
request id。設計稿有一個小的等寬字體提示(`REQ-8F2A-C914`),讓使用者在回報問題時可以提供這個
編號。

**建議**:低優先——不影響核心功能,只是除錯/客服追蹤的輔助資訊。如果 `useParams` 已經有
`requestId`,加一行 `<span className="...">{requestId}</span>` 即可。

### 5. 活動列表缺少分區標題、卡片缺少可點擊箭頭提示

`frontend/src/features/flash-sales/FlashSaleListPage.tsx:17-28` 直接把所有活動攤平列出,設計稿
是用「現正開賣」這類分區標題(`.section-eyebrow`)把活動分組。另外設計稿每張卡片右側的
`.stub-edge` 裡有一個「›」箭頭符號提示「這張卡片可以點進去」,實際畫面的 `.stub-edge` 是空的
裝飾條,沒有箭頭。

**建議**:低優先,兩者都是視覺打磨而非功能缺陷——卡片本身(`Link`)已經可點擊,只是少了視覺提示。
分區標題目前只有一組資料(沒有依「即將開賣」「已結束」分類顯示),如果之後想做,才需要先決定
分類邏輯要不要做,不只是加個標籤。

## 沒有落差、確認一致的部分

- 登入/註冊頁(`LoginPage.tsx`/`RegisterPage.tsx`):`.auth-body`/`.auth-card`/`.field`/
  `.kicker`/`.wordmark` 全部沿用設計稿的 class 名稱與結構,文案(「登入以搶購」「回到搶購佇列
  繼續完成」「建立帳號」「搶購前先取得你的入場資格」)逐字相符。
- 活動詳情頁(`FlashSaleDetailPage.tsx`):`.detail-hero`/`.stock-meter`/`.stock-bar`/
  `.countdown-strip`/`.limit-note` 結構跟設計稿一致,「剩餘庫存」「距離結束」「每人限購 N 件」
  文案相符。
- 訂單詳情頁的付款操作按鈕文案(「模擬付款成功」「模擬付款失敗」「取消訂單」)逐字相符,
  `StatusPill` 元件放在訂單編號旁的位置也跟設計稿一致。
- `StatusPill.tsx` 的三色狀態系統(`go`/`wait`/`stop`)跟每個狀態對應的中文標籤(搶購中/即將
  開賣/已結束/待付款/已付款/已取消 等)完整比照設計稿的 legend 定義。
- 整體配色 token、按鈕樣式、卡片陰影(`--shadow`)全站一致套用,沒有發現退回瀏覽器預設樣式的
  頁面(§2.4 已修的 `App.tsx` 殘留標題是唯一一個,Task 5 已處理)。

## 建議後續處理方式

落差 1-3 是內容/資訊呈現的真實缺漏(不是主觀的設計偏好差異),值得排進下一個小任務直接修掉——
量都很小(1、2 都是幾行程式碼,3 需要複用既有的 `countdown-strip` class,難度最低)。落差 4-5
是次要打磨,可以先擱置,不影響功能也不影響資訊正確性。

這份清單本身是 Task 6 的完整交付——依照計畫的設計,不在這個任務裡直接動手修,交給使用者決定要不
要排進後續工作。
