package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleUpdateRequest(
    @NotNull(message = "售價為必填") @Positive(message = "售價必須大於 0") BigDecimal salePrice,
    @NotNull(message = "開始時間為必填") Instant startsAt,
    @NotNull(message = "結束時間為必填") Instant endsAt,
    @Positive(message = "每人限購數量必須大於 0") int purchaseLimitPerUser,
    @Positive(message = "庫存數量必須大於 0") int totalQuantity
) {
    @AssertTrue(message = "開始時間必須早於結束時間")
    public boolean isValidTimeWindow() {
        return startsAt == null || endsAt == null || startsAt.isBefore(endsAt);
    }
}
