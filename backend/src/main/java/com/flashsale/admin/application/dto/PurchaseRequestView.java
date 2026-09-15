package com.flashsale.admin.application.dto;

import java.util.UUID;

/**
 * 後台訂單詳情裡的搶購請求區塊。P5 之後這個型別屬於 admin：搶購請求的資料在
 * purchase-service，訂單在 order-service，而這個視圖是 admin 從訂單推導出來的 ——
 * 它不是任何一個服務的領域型別，是這個畫面的形狀。
 */
public record PurchaseRequestView(UUID requestId, String status, Long orderId) {}
