package com.flashsale.purchase.adapter.http;

import com.flashsale.purchase.application.FlashSaleClient;
import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.exception.NotFoundException;
import com.flashsale.purchase.exception.ServiceUnavailableException;
import com.flashsale.purchase.metrics.PurchaseMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「backend 掛掉時搶購 API 會怎麼壞」的完整定義就在這個類別，三個決定都寫在這裡：
 *
 * 1. 快取 TTL 2 秒。活動資料在活動期間幾乎不變，2 秒就能讓熱路徑上的跨服務呼叫降到趨近於零
 *    （600 rps 下大約每 1200 次請求才真的打一次）。
 *    代價講清楚：後台把活動提前結束（endEarly）之後，最多 2 秒內仍可能有請求被放行。
 *    活動的起訖判定是用 purchase-service 自己的時鐘當場算的，所以快取模糊掉的只有
 *    「活動被改動」這件事，不是「活動有沒有到時間」。
 *
 * 2. 失敗時先用寬限期（60 秒）內的過期快取放行，超過寬限期才拒絕（503）。
 *    搶購進行中時，backend 掛掉不該讓正在搶的人全部被擋下來；但過期資料不能無限期用下去。
 *
 * 3. 所以「backend 掛掉時搶購還能不能開」的答案是：**寬限期內能，超過不能，
 *    而快取裡沒有的活動一開始就不能。** 這次拆分讓「開一個沒人搶過的活動」依賴 backend 活著，
 *    已經在搶的活動則有 60 秒的緩衝。這是拆分的代價，不是拆分的好處。
 *
 * 查不到（NotFound）不會走降級：那是權威的答案，快取要一起清掉。
 *
 * ponytail: TTL 到期時沒有做 single-flight。600 rps、來回約 5 ms 的情況下同時穿透的請求是
 * 個位數，不值得為它加一層 per-key 鎖。若把 TTL 拉長、或下游變慢到幾百毫秒，穿透量會跟著
 * 放大，那時再加。
 */
@Component
@Primary
public class CachingFlashSaleClient implements FlashSaleClient {

    private static final Logger logger = LoggerFactory.getLogger(CachingFlashSaleClient.class);

    private final FlashSaleClient delegate;
    private final PurchaseMetrics purchaseMetrics;
    private final Duration ttl;
    private final Duration grace;
    private final Clock clock;
    private final ConcurrentHashMap<Long, CachedSnapshot> cache = new ConcurrentHashMap<>();

    // @Autowired 不是裝飾用的：這個類別有兩個建構子（另一個給測試注入可控時鐘），
    // 沒有標註時 Spring 不會挑，而是去找預設建構子、然後在啟動時炸掉。
    @Autowired
    public CachingFlashSaleClient(MonolithFlashSaleClient delegate,
                                   PurchaseMetrics purchaseMetrics,
                                   @Value("${app.flash-sale.cache-ttl-ms}") long ttlMs,
                                   @Value("${app.flash-sale.cache-grace-ms}") long graceMs) {
        this(delegate, purchaseMetrics, Duration.ofMillis(ttlMs), Duration.ofMillis(graceMs), Clock.systemUTC());
    }

    CachingFlashSaleClient(FlashSaleClient delegate, PurchaseMetrics purchaseMetrics,
                            Duration ttl, Duration grace, Clock clock) {
        this.delegate = delegate;
        this.purchaseMetrics = purchaseMetrics;
        this.ttl = ttl;
        this.grace = grace;
        this.clock = clock;
    }

    @Override
    public FlashSaleSnapshot fetch(Long flashSaleId) {
        Instant now = clock.instant();
        CachedSnapshot cached = cache.get(flashSaleId);
        if (cached != null && cached.isFreshAt(now, ttl)) {
            return cached.snapshot();
        }

        try {
            FlashSaleSnapshot fresh = delegate.fetch(flashSaleId);
            cache.put(flashSaleId, new CachedSnapshot(fresh, clock.instant()));
            return fresh;
        } catch (NotFoundException notFound) {
            cache.remove(flashSaleId);
            throw notFound;
        } catch (RuntimeException exception) {
            if (cached != null && cached.isUsableAt(now, grace)) {
                purchaseMetrics.flashSaleStaleServed();
                logger.warn("活動 {} 查詢失敗，改用 {} 秒前的快取放行：{}",
                    flashSaleId, Duration.between(cached.fetchedAt(), now).toSeconds(), exception.toString());
                return cached.snapshot();
            }
            throw exception;
        }
    }

    @Override
    public int availableQuantity(Long flashSaleId) {
        // 刻意不快取、也沒有寬限期：種入一個過期的可售數量會直接造成超賣或漏賣，
        // 拒絕這次請求遠比猜一個數字安全。
        return delegate.availableQuantity(flashSaleId);
    }

    private record CachedSnapshot(FlashSaleSnapshot snapshot, Instant fetchedAt) {

        boolean isFreshAt(Instant now, Duration ttl) {
            return fetchedAt.plus(ttl).isAfter(now);
        }

        boolean isUsableAt(Instant now, Duration grace) {
            return fetchedAt.plus(grace).isAfter(now);
        }
    }
}
