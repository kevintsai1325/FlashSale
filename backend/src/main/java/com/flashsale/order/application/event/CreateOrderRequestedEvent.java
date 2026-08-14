package com.flashsale.order.application.event;

import java.math.BigDecimal;

public record CreateOrderRequestedEvent(Long purchaseRequestId, Long userId, Long flashSaleId, Long productId,
                                         int quantity, BigDecimal unitPrice) {}
