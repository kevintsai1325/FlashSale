package com.flashsale.admin.application;

import com.flashsale.admin.adapter.http.PurchaseStatsClient;
import com.flashsale.admin.adapter.http.PurchaseStatsClient.PurchaseStats;
import com.flashsale.admin.application.dto.DashboardSummary;
import com.flashsale.admin.application.dto.DashboardTrends;
import com.flashsale.admin.application.dto.InventorySummary;
import com.flashsale.admin.application.dto.TrendPoint;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-module read-only aggregates for the admin dashboard. This is a portfolio-demo-scale
 * system with no seed-data generator producing thousands of rows, so summary numbers come from
 * simple single-shot aggregate queries (no caching) and trend bucketing is done in Java over a
 * single time-bounded fetch rather than a SQL {@code date_trunc}/reporting layer.
 *
 * P4 步驟 2 起，搶購請求的數字來自 purchase-service 的內部端點而不是本地查詢 ——
 * 那張表已經在別人的資料庫裡。訂單與庫存仍是本地的。**這兩半的一致性因此是最終一致的**：
 * 兩個數字取自兩個時點，高併發時「搶購請求數」與「訂單數」可能對不起來幾筆。
 * 這是跨服務儀表板的本質，不是 bug；要嚴格對齊只能靠 P5-2 的讀取模型（同一條事件流算出來）。
 */
@Service
public class DashboardQueryService {

    private final PurchaseStatsClient purchaseStatsClient;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;

    public DashboardQueryService(PurchaseStatsClient purchaseStatsClient,
                                  OrderRepository orderRepository,
                                  InventoryRepository inventoryRepository) {
        this.purchaseStatsClient = purchaseStatsClient;
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public DashboardSummary getSummary() {
        PurchaseStats stats = purchaseStatsClient.fetch(Instant.now());

        Map<String, Long> ordersByStatus = new LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            ordersByStatus.put(status.name(), orderRepository.countByStatus(status));
        }
        java.math.BigDecimal totalPaidAmount = orderRepository.sumTotalAmountByStatus(OrderStatus.PAID);

        Map<Long, InventorySummary> inventoryByFlashSaleId = new LinkedHashMap<>();
        for (Inventory inventory : inventoryRepository.findAll()) {
            inventoryByFlashSaleId.put(inventory.getFlashSaleId(), new InventorySummary(
                inventory.getTotalQuantity(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(),
                inventory.getSoldQuantity()));
        }

        return new DashboardSummary(stats.total(), stats.succeeded(), ordersByStatus,
            totalPaidAmount, inventoryByFlashSaleId);
    }

    public DashboardTrends getTrends() {
        Instant now = Instant.now();
        Instant dayAgo = now.minus(24, ChronoUnit.HOURS);

        // 同一個 now 送給 purchase-service 當分桶錨點，兩邊的桶起點才對得上。
        PurchaseStats stats = purchaseStatsClient.fetch(now);
        List<Order> recentOrders = orderRepository.findAllCreatedAfter(dayAgo);

        List<TrendPoint> lastHour = bucket(stats, stats.lastHour(), recentOrders, now, ChronoUnit.MINUTES, 60);
        List<TrendPoint> last24Hours = bucket(stats, stats.last24Hours(), recentOrders, now, ChronoUnit.HOURS, 24);

        return new DashboardTrends(lastHour, last24Hours);
    }

    private List<TrendPoint> bucket(PurchaseStats stats, List<PurchaseStatsClient.BucketCount> purchaseBuckets,
                                     List<Order> orders, Instant now, ChronoUnit unit, int bucketCount) {
        Instant flooredNow = now.truncatedTo(unit);
        List<TrendPoint> points = new ArrayList<>(bucketCount);
        for (int i = bucketCount - 1; i >= 0; i--) {
            Instant bucketStart = flooredNow.minus(i, unit);
            Instant bucketEnd = bucketStart.plus(1, unit);

            long orderCount = orders.stream()
                .filter(o -> isWithin(o.getCreatedAt(), bucketStart, bucketEnd))
                .count();

            points.add(new TrendPoint(bucketStart, stats.countAt(purchaseBuckets, bucketStart), orderCount));
        }
        return points;
    }

    private boolean isWithin(Instant instant, Instant bucketStart, Instant bucketEnd) {
        return !instant.isBefore(bucketStart) && instant.isBefore(bucketEnd);
    }
}
