package com.flashsale.analytics.web;

import com.flashsale.analytics.repository.OrderProjectionRepository;
import com.flashsale.analytics.repository.PurchaseRequestProjectionRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 後台儀表板的數字。**兩半現在出自同一份資料**：拆庫之後「搶購請求數」與「訂單數」
 * 分別住在兩個資料庫，分別查兩次得到的是兩個時點的數字；訂閱同一條事件流之後不再如此。
 *
 * 唯一仍然要另外問的是庫存 —— 那是「當下的狀態」而不是事件的聚合，
 * 由 order-service（它的擁有者）直接回答比較誠實。
 *
 * 不經 Nginx（它對 /internal/ 一律回 404），只在叢集內部由 platform 呼叫。
 */
@RestController
public class InternalAnalyticsController {

    private static final int MINUTE_BUCKETS = 60;
    private static final int HOUR_BUCKETS = 24;

    private final OrderProjectionRepository orders;
    private final PurchaseRequestProjectionRepository purchaseRequests;

    public InternalAnalyticsController(OrderProjectionRepository orders,
                                        PurchaseRequestProjectionRepository purchaseRequests) {
        this.orders = orders;
        this.purchaseRequests = purchaseRequests;
    }

    @GetMapping("/internal/analytics/dashboard-summary")
    public DashboardSummary summary() {
        Map<String, Long> ordersByStatus = new LinkedHashMap<>();
        for (Object[] row : orders.countGroupedByStatus()) {
            ordersByStatus.put((String) row[0], (Long) row[1]);
        }
        return new DashboardSummary(
            purchaseRequests.count(),
            purchaseRequests.countByStatus("SUCCEEDED"),
            ordersByStatus,
            orders.sumTotalAmountByStatus("PAID"));
    }

    /**
     * {@code asOf} 是分桶的錨點，由呼叫端給：兩個行程各自取 now() 再截到分鐘，
     * 只要呼叫跨過一個分鐘邊界，桶起點就會差一格。內部報表端點，時鐘不是信任邊界。
     */
    @GetMapping("/internal/analytics/dashboard-trends")
    public DashboardTrends trends(@RequestParam(name = "asOf", required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        Instant now = asOf == null ? Instant.now() : asOf;
        Instant dayAgo = now.minus(HOUR_BUCKETS, ChronoUnit.HOURS);
        List<Instant> requestTimes = purchaseRequests.findCreatedAtSince(dayAgo);
        List<Instant> orderTimes = orders.findCreatedAtSince(dayAgo);
        return new DashboardTrends(
            bucket(requestTimes, orderTimes, now, ChronoUnit.MINUTES, MINUTE_BUCKETS),
            bucket(requestTimes, orderTimes, now, ChronoUnit.HOURS, HOUR_BUCKETS));
    }

    private static List<TrendPoint> bucket(List<Instant> requestTimes, List<Instant> orderTimes,
                                            Instant now, ChronoUnit unit, int bucketCount) {
        Instant floored = now.truncatedTo(unit);
        List<TrendPoint> points = new ArrayList<>(bucketCount);
        for (int ago = bucketCount - 1; ago >= 0; ago--) {
            Instant start = floored.minus(ago, unit);
            Instant end = start.plus(1, unit);
            points.add(new TrendPoint(start, countWithin(requestTimes, start, end), countWithin(orderTimes, start, end)));
        }
        return points;
    }

    private static long countWithin(List<Instant> times, Instant start, Instant end) {
        return times.stream().filter(t -> !t.isBefore(start) && t.isBefore(end)).count();
    }

    public record DashboardSummary(long totalPurchaseRequests, long succeededPurchaseRequests,
                                    Map<String, Long> ordersByStatus, BigDecimal totalPaidAmount) {}

    public record TrendPoint(Instant bucketStart, long purchaseRequestCount, long orderCount) {}

    public record DashboardTrends(List<TrendPoint> lastHour, List<TrendPoint> last24Hours) {}
}
