package com.flashsale.flashsale.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleDetail(
    Long id, String productName, String productDescription, BigDecimal salePrice,
    Instant startsAt, Instant endsAt, int purchaseLimitPerUser, String status
) {}
