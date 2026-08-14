package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.DashboardSummary;
import com.flashsale.admin.application.dto.DashboardTrends;
import com.flashsale.admin.application.dto.InventorySummary;
import com.flashsale.admin.application.dto.TrendPoint;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
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
 */
@Service
public class DashboardQueryService {

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;

    public DashboardQueryService(PurchaseRequestRepository purchaseRequestRepository,
                                  OrderRepository orderRepository,
                                  InventoryRepository inventoryRepository) {
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public DashboardSummary getSummary() {
        long totalPurchaseRequests = purchaseRequestRepository.countAll();
        long succeededPurchaseRequests = purchaseRequestRepository.countByStatus(PurchaseRequestStatus.SUCCEEDED);

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

        return new DashboardSummary(totalPurchaseRequests, succeededPurchaseRequests, ordersByStatus,
            totalPaidAmount, inventoryByFlashSaleId);
    }

    public DashboardTrends getTrends() {
        Instant now = Instant.now();
        Instant dayAgo = now.minus(24, ChronoUnit.HOURS);

        List<PurchaseRequest> recentRequests = purchaseRequestRepository.findAllCreatedAfter(dayAgo);
        List<Order> recentOrders = orderRepository.findAllCreatedAfter(dayAgo);

        List<TrendPoint> lastHour = bucket(recentRequests, recentOrders, now, ChronoUnit.MINUTES, 60);
        List<TrendPoint> last24Hours = bucket(recentRequests, recentOrders, now, ChronoUnit.HOURS, 24);

        return new DashboardTrends(lastHour, last24Hours);
    }

    private List<TrendPoint> bucket(List<PurchaseRequest> requests, List<Order> orders, Instant now,
                                     ChronoUnit unit, int bucketCount) {
        Instant flooredNow = now.truncatedTo(unit);
        List<TrendPoint> points = new ArrayList<>(bucketCount);
        for (int i = bucketCount - 1; i >= 0; i--) {
            Instant bucketStart = flooredNow.minus(i, unit);
            Instant bucketEnd = bucketStart.plus(1, unit);

            long purchaseRequestCount = requests.stream()
                .filter(r -> isWithin(r.getCreatedAt(), bucketStart, bucketEnd))
                .count();
            long orderCount = orders.stream()
                .filter(o -> isWithin(o.getCreatedAt(), bucketStart, bucketEnd))
                .count();

            points.add(new TrendPoint(bucketStart, purchaseRequestCount, orderCount));
        }
        return points;
    }

    private boolean isWithin(Instant instant, Instant bucketStart, Instant bucketEnd) {
        return !instant.isBefore(bucketStart) && instant.isBefore(bucketEnd);
    }
}
