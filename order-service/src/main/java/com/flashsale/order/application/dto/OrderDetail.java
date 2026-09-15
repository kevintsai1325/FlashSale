package com.flashsale.order.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDetail(
    Long id,
    String orderNo,
    BigDecimal totalAmount,
    String status,
    Instant paymentDueAt,
    List<OrderItemView> items
) {}
