# 訂單商品資訊快照設計

**日期：** 2026-08-16  
**狀態：** 已確認設計

## 目標

讓使用者在「我的訂單」列表與訂單詳情中看見每筆訂單購買的商品名稱、數量與下單時單價。歷史訂單必須保留下單當時的商品名稱，不受後台日後修改商品名稱影響。

## 資料模型

在 `order_items` 新增不可為空的 `product_name` 欄位，與既有的 `product_id`、`quantity`、`unit_price` 共同構成訂單品項快照。

Flyway migration 先新增可為空欄位，使用 `products.name` 依 `product_id` 回填所有既有品項，再加上 `NOT NULL` 約束。商品仍以 `product_id` 保留來源關聯，但顯示歷史訂單時一律使用 `order_items.product_name`。

## 建單流程

`OrderPurchaseConsumer` 建立訂單前，透過既有 `ProductRepository` 取得商品。商品不存在時沿用訊息消費流程的錯誤處理，不建立內容不完整的訂單。

`Order.createPendingPayment` 接收商品名稱，並由 `OrderItem.of` 同時保存：

- `productId`
- `productName`
- `quantity`
- `unitPrice`

之後修改 `products.name` 不更新既有 `order_items.product_name`。

## API 契約

新增共用回應 DTO：

```text
OrderItemView {
  productId: number
  productName: string
  quantity: number
  unitPrice: number
}
```

`OrderSummary` 與 `OrderDetail` 都新增 `items: OrderItemView[]`。目前每張訂單只有一個品項，但契約維持陣列形式，以符合訂單領域模型並支援未來多品項。

以下端點都回傳相同的品項快照：

- `GET /api/orders/me`
- `GET /api/orders/{orderId}`
- `POST /api/orders/{orderId}/cancel`
- `POST /api/orders/{orderId}/payments`

DTO 組裝集中於訂單模組的 mapper，避免各 controller 或付款模組重複轉換而漏掉 `items`。

## 前端顯示

「我的訂單」列表的每張卡片在訂單編號與狀態下方逐項顯示：

- 商品名稱
- `數量 × 單價`

訂單詳情在訂單總金額區塊之前顯示完整品項列表，同樣包含商品名稱、數量與單價。總金額仍使用後端的 `totalAmount`，不在前端重新計算。

若 API 回傳空品項陣列，頁面仍可顯示訂單基本資料，不因單一異常資料而整頁失敗；正常新舊資料經 migration 後不得出現空品項。

## 測試與驗收

依 TDD 實作並涵蓋：

1. Flyway migration 能回填既有 `order_items.product_name` 並建立非空約束。
2. 建單時保存商品名稱快照；商品日後改名不改變訂單回應名稱。
3. 我的訂單列表與詳情 API 回傳商品名稱、數量與單價。
4. 取消與付款回應仍包含完整品項。
5. 前端列表與詳情呈現商品名稱、數量與格式化單價。
6. 既有訂單狀態、付款、取消與權限行為維持不變。

本功能完成 scoped/unit tests 後直接提交；完整整合測試依既有約定，等本輪所有工作完成後統一執行。
