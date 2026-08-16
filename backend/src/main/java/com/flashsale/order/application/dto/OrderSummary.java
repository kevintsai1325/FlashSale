package com.flashsale.order.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderSummary(
    Long id,
    String orderNo,
    BigDecimal totalAmount,
    String status,
    List<OrderItemView> items
) {}
