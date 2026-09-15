package com.flashsale.admin.application;

import com.flashsale.admin.adapter.http.AnalyticsClient;
import com.flashsale.admin.application.dto.DashboardSummary;
import com.flashsale.admin.application.dto.DashboardTrends;
import com.flashsale.admin.application.dto.InventorySummary;
import com.flashsale.admin.application.dto.TrendPoint;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 後台儀表板的資料來源。P5 之後分成兩類，而這個分界本身是這一階段最值得記下來的東西：
 *
 * <ul>
 *   <li><b>聚合</b>（搶購請求數、訂單狀態分佈、已付款金額、趨勢）來自 analytics-service 的
 *       讀取模型。它訂閱同一條 Kafka 事件流，所以兩個數字出自同一份資料 ——
 *       拆庫之後分別查兩個服務，得到的會是兩個時點的數字。</li>
 *   <li><b>當下的狀態</b>（庫存）直接問擁有者。庫存不是事件的聚合，把它做成投影只會多一份
 *       會過期的複本。</li>
 * </ul>
 *
 * 聚合這一半是最終一致的：事件從發生到進入讀取模型有毫秒級的延遲。
 * 對一個營運儀表板來說這是正確的取捨 —— 它要的是趨勢，不是帳務級的即時餘額。
 */
@Service
public class DashboardQueryService {

    private static final int MINUTE_BUCKETS = 60;
    private static final int HOUR_BUCKETS = 24;

    private final AnalyticsClient analyticsClient;
    private final InventoryRepository inventoryRepository;

    public DashboardQueryService(AnalyticsClient analyticsClient, InventoryRepository inventoryRepository) {
        this.analyticsClient = analyticsClient;
        this.inventoryRepository = inventoryRepository;
    }

    public DashboardSummary getSummary() {
        AnalyticsClient.DashboardSummary aggregates = analyticsClient.summary();

        // 每一個狀態都要有一格，即使是 0 —— 前端的圖表以固定的狀態集合渲染。
        Map<String, Long> ordersByStatus = new LinkedHashMap<>();
        for (String status : List.of("PENDING_PAYMENT", "PAID", "CANCELLED", "EXPIRED")) {
            ordersByStatus.put(status, aggregates.ordersByStatus().getOrDefault(status, 0L));
        }

        Map<Long, InventorySummary> inventoryByFlashSaleId = new LinkedHashMap<>();
        for (Inventory inventory : inventoryRepository.findAll()) {
            inventoryByFlashSaleId.put(inventory.getFlashSaleId(), new InventorySummary(
                inventory.getTotalQuantity(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(),
                inventory.getSoldQuantity()));
        }

        BigDecimal totalPaidAmount = aggregates.totalPaidAmount() == null ? BigDecimal.ZERO : aggregates.totalPaidAmount();
        return new DashboardSummary(aggregates.totalPurchaseRequests(), aggregates.succeededPurchaseRequests(),
            ordersByStatus, totalPaidAmount, inventoryByFlashSaleId);
    }

    public DashboardTrends getTrends() {
        AnalyticsClient.DashboardTrends trends = analyticsClient.trends(Instant.now());
        return new DashboardTrends(toPoints(trends.lastHour()), toPoints(trends.last24Hours()));
    }

    private static List<TrendPoint> toPoints(List<AnalyticsClient.TrendPoint> points) {
        return points.stream()
            .map(p -> new TrendPoint(p.bucketStart(), p.purchaseRequestCount(), p.orderCount()))
            .toList();
    }
}
