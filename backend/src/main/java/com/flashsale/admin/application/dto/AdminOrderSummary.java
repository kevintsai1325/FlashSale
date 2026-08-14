package com.flashsale.admin.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminOrderSummary(
    Long id,
    String orderNo,
    Long userId,
    BigDecimal totalAmount,
    String status,
    Instant createdAt
) {}
