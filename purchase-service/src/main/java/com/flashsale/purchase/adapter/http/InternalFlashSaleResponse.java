package com.flashsale.purchase.adapter.http;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * backend 內部 API 的回應形狀。這是兩個服務之間的契約，改動任何一個欄位名稱都要兩邊一起改，
 * 對應的來源是 backend 的 InternalFlashSaleController。
 */
public record InternalFlashSaleResponse(Long id, Long productId, String productName, BigDecimal salePrice,
                                         Instant startsAt, Instant endsAt, int purchaseLimitPerUser) {}
