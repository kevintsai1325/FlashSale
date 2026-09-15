package com.flashsale.purchase.messaging;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 送給 backend 的建單請求。欄位形狀是契約，改名要兩邊一起改。
 *
 * purchaseRequestId 送的是公開的 UUID，不是本地主鍵：對方拿它回呼、存進自己的訂單表，
 * 而本地主鍵是 purchase_requests 這張表的實作細節，不該洩漏出服務邊界。
 */
public record CreateOrderRequestedEvent(UUID purchaseRequestId, Long userId, Long flashSaleId, Long productId,
                                         String productName, int quantity, BigDecimal unitPrice) {}
