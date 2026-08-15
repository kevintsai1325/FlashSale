package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleCreateRequest(
    @NotNull Long productId,
    @NotNull @Positive BigDecimal salePrice,
    @NotNull Instant startsAt,
    @NotNull Instant endsAt,
    @Positive int purchaseLimitPerUser,
    @Positive int totalQuantity
) {
    @AssertTrue(message = "startsAt must be before endsAt")
    public boolean isValidTimeWindow() {
        return startsAt == null || endsAt == null || startsAt.isBefore(endsAt);
    }
}
