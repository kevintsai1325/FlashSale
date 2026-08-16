# Admin and Visual Debt Implementation Plan

**Goal:** 補齊商品／活動編輯、修正 CSS scope 與弱斷言，完成 visual audit 五項落差。

## Tasks

- [x] 商品新增／編輯共用表單，支援取消、錯誤與 query refresh。
- [x] 活動摘要提供完整編輯資料，PUT body 排除 productId。
- [x] 活動編輯鎖定商品並正確轉換 datetime-local／ISO。
- [x] 中文結果印章、request ID、空訂單副標與付款期限 strip。
- [x] ACTIVE／SCHEDULED／ENDED 非空分區與 card arrow。
- [x] 強化前端 payload、取消、內容與 CSS class assertions。
- [ ] 最終 integration run 驗證 controller persistence，stack smoke 驗證桌面／窄螢幕。
