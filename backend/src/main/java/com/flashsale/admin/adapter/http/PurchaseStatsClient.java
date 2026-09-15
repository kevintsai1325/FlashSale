package com.flashsale.admin.adapter.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 後台儀表板的搶購請求統計來自 purchase-service —— 拆庫之後 backend 讀不到那張表。
 *
 * **失敗時降級成空統計，不讓整個儀表板變成 500。** 儀表板是唯讀的營運畫面，
 * 「搶購請求那一區暫時沒有數字」是可以接受的損失；「整頁打不開」不是。
 * 這與搶購熱路徑上那個呼叫（purchase-service → backend）的決定剛好相反，理由也相反：
 * 那邊錯了會賣錯東西，這邊錯了只是少看到一個數字。
 *
 * 逾時比熱路徑寬鬆（2 秒）：後台頁面的使用者願意等，而這支端點要掃 24 小時的資料。
 */
@Component
public class PurchaseStatsClient {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseStatsClient.class);

    private final RestClient restClient;

    // @Autowired 不是裝飾用的：這個類別有兩個建構子（另一個給測試塞入模擬的傳輸層），
    // 沒有標註時 Spring 不會挑，而是去找預設建構子、然後在啟動時炸掉。
    @Autowired
    public PurchaseStatsClient(RestClient.Builder restClientBuilder,
                                @Value("${app.purchase-service.base-url}") String baseUrl,
                                @Value("${app.purchase-service.connect-timeout-ms}") long connectTimeoutMs,
                                @Value("${app.purchase-service.read-timeout-ms}") long readTimeoutMs) {
        this(restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
            .build());
    }

    // 測試用：MockRestServiceServer 掛在 builder 上，而上面那個建構子會用自己的
    // requestFactory 蓋掉它——所以測試得自己把 client 建好再傳進來。
    PurchaseStatsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public PurchaseStats fetch(Instant asOf) {
        try {
            PurchaseStats stats = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/purchase-requests/stats")
                    .queryParam("asOf", asOf.toString()).build())
                .retrieve()
                .body(PurchaseStats.class);
            return stats == null ? PurchaseStats.unavailable() : stats;
        } catch (RuntimeException exception) {
            logger.warn("無法取得 purchase-service 的搶購統計，儀表板該區塊以 0 呈現: {}", exception.getMessage());
            return PurchaseStats.unavailable();
        }
    }

    public record BucketCount(Instant bucketStart, long count) {}

    public record PurchaseStats(long total, long succeeded, List<BucketCount> lastHour, List<BucketCount> last24Hours) {
        static PurchaseStats unavailable() {
            return new PurchaseStats(0, 0, List.of(), List.of());
        }

        /** 對應不到的桶回 0：降級時整條趨勢線就是一條 0，而不是圖表壞掉。 */
        public long countAt(List<BucketCount> buckets, Instant bucketStart) {
            return buckets.stream()
                .filter(b -> b.bucketStart().equals(bucketStart))
                .mapToLong(BucketCount::count)
                .findFirst()
                .orElse(0L);
        }
    }
}
