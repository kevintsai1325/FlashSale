package com.flashsale.purchase.messaging;

import java.math.BigDecimal;

/**
 * 送給 backend 的建單請求。欄位形狀是契約，改名要兩邊一起改。
 */
public record CreateOrderRequestedEvent(Long purchaseRequestId, Long userId, Long flashSaleId, Long productId,
                                         int quantity, BigDecimal unitPrice) {}
