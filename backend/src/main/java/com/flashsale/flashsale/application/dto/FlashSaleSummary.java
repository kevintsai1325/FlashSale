package com.flashsale.flashsale.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleSummary(
    Long id, Long productId, String productName, BigDecimal salePrice, Instant startsAt, Instant endsAt,
    int purchaseLimitPerUser, int totalQuantity, String status
) {}
