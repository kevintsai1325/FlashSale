package com.flashsale.admin.application.dto;

import java.math.BigDecimal;
import java.util.Map;

public record DashboardSummary(
    long totalPurchaseRequests,
    long succeededPurchaseRequests,
    Map<String, Long> ordersByStatus,
    BigDecimal totalPaidAmount,
    Map<Long, InventorySummary> inventoryByFlashSaleId
) {}
