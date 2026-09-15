package com.flashsale.purchase.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 刻意回傳 startsAt / endsAt 而不是一個算好的 purchasable 布林值：
 * 布林值一旦被快取就會把活動的起訖邊界一起模糊掉，而時間戳本身不會因為快取而改變意義，
 * 由 purchase-service 用自己的時鐘當場判定即可。
 */
public record FlashSaleSnapshot(Long id, Long productId, String productName, BigDecimal salePrice,
                                 Instant startsAt, Instant endsAt, int purchaseLimitPerUser) {

    public boolean isPurchasableAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
