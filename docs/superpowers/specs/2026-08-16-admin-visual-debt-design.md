# 管理功能與視覺技術債設計規格

- 商品與搶購活動列表提供頁內編輯；活動更新不傳 productId。
- 活動摘要以向後相容欄位提供 productId、限購量與總庫存，供表單正確回填。
- datetime-local 以本地 components 顯示，送出轉回 ISO instant。
- 商品 CSS selector 限定在 `.admin-products-page`。
- 搶購結果使用中文印章、PENDING 顯示 request ID、空訂單提供副標、付款期限使用提示 strip。
- 活動依 ACTIVE／SCHEDULED／ENDED 分區，空分區不顯示，卡片 edge 提供箭頭。
- 不新增刪除功能、不允許活動變更商品、不改業務狀態。
