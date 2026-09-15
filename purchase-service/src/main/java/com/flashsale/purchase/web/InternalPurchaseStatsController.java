package com.flashsale.purchase.web;

import com.flashsale.purchase.adapter.persistence.PurchaseRequestJpaRepository;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 後台儀表板要的搶購請求統計。拆庫之後 backend 讀不到 purchase_requests，只能問這裡。
 *
 * **聚合在資料擁有者這一側做完，回傳桶好的數字而不是原始列。** 回傳原始的 createdAt 清單
 * 會讓回應大小跟著資料量線性成長，而且把「怎麼分桶」這個決定留在一個看不到資料的地方。
 * 代價是這個服務得知道儀表板要的粒度（60 個一分鐘桶、24 個一小時桶），這確實是一點耦合——
 * 它是暫時的：P5-2 的 analytics-service 上線後，儀表板改讀那邊的讀取模型，這支端點就會刪掉。
 *
 * 不經 Nginx（它對 /internal/ 一律回 404），只在叢集內部由 backend 呼叫。
 */
@RestController
public class InternalPurchaseStatsController {

    private static final int MINUTE_BUCKETS = 60;
    private static final int HOUR_BUCKETS = 24;

    private final PurchaseRequestJpaRepository repository;

    public InternalPurchaseStatsController(PurchaseRequestJpaRepository repository) {
        this.repository = repository;
    }

    /**
  * {@code asOf} 是分桶的錨點，由呼叫端給。兩個行程各自取 {@code Instant.now()} 再截到分鐘，
  * 只要呼叫剛好跨過一個分鐘邊界，兩邊算出來的桶起點就會差一格，對得起來的資料看起來像消失了。
  * 讓呼叫端指定錨點是最小的修法——這是內部的報表端點，時鐘不是信任邊界。
  */
    @GetMapping("/internal/purchase-requests/stats")
    public PurchaseStatsResponse stats(
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        Instant now = asOf == null ? Instant.now() : asOf;
        List<Instant> createdAt = repository.findCreatedAtSince(now.minus(HOUR_BUCKETS, ChronoUnit.HOURS));
        return new PurchaseStatsResponse(
            repository.count(),
            repository.countByStatus(PurchaseRequestStatus.SUCCEEDED),
            bucket(createdAt, now, ChronoUnit.MINUTES, MINUTE_BUCKETS),
            bucket(createdAt, now, ChronoUnit.HOURS, HOUR_BUCKETS));
    }

    private static List<BucketCount> bucket(List<Instant> timestamps, Instant now, ChronoUnit unit, int bucketCount) {
        Instant flooredNow = now.truncatedTo(unit);
        List<BucketCount> points = new ArrayList<>(bucketCount);
        for (int i = bucketCount - 1; i >= 0; i--) {
            Instant start = flooredNow.minus(i, unit);
            Instant end = start.plus(1, unit);
            long count = timestamps.stream()
                .filter(t -> !t.isBefore(start) && t.isBefore(end))
                .count();
            points.add(new BucketCount(start, count));
        }
        return points;
    }

    public record BucketCount(Instant bucketStart, long count) {}

    public record PurchaseStatsResponse(long total, long succeeded,
                                         List<BucketCount> lastHour, List<BucketCount> last24Hours) {}
}
