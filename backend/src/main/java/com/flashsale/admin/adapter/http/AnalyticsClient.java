package com.flashsale.admin.adapter.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 後台儀表板的數字來自 analytics-service 的讀取模型（P5）。
 *
 * 它取代了兩個東西：本地的訂單彙總查詢，以及「跨服務去問 purchase-service 的統計」。
 * **重點不是少一次呼叫，是兩個數字從此出自同一份資料** —— 分別查兩個服務得到的是
 * 兩個時點的數字，高併發時「搶購請求數」與「訂單數」會對不起來幾筆。
 *
 * 失敗時降級成空統計而不是讓整頁 500：儀表板是唯讀的營運畫面，
 * 「數字暫時看不到」可以接受，「整頁打不開」不行。
 */
@Component
public class AnalyticsClient {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsClient.class);

    private final RestClient restClient;

    @Autowired
    public AnalyticsClient(RestClient.Builder restClientBuilder,
                            @Value("${app.analytics.base-url}") String baseUrl,
                            @Value("${app.analytics.connect-timeout-ms}") long connectTimeoutMs,
                            @Value("${app.analytics.read-timeout-ms}") long readTimeoutMs) {
        this(restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
            .build());
    }

    // 測試用：MockRestServiceServer 掛在 builder 上，上面那個建構子會用自己的
    // requestFactory 蓋掉它，所以測試得自己把 client 建好再傳進來。
    AnalyticsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public DashboardSummary summary() {
        try {
            DashboardSummary summary = restClient.get()
                .uri("/internal/analytics/dashboard-summary")
                .retrieve()
                .body(DashboardSummary.class);
            return summary == null ? DashboardSummary.unavailable() : summary;
        } catch (RuntimeException exception) {
            logger.warn("無法取得 analytics 的儀表板彙總，該區塊以 0 呈現: {}", exception.getMessage());
            return DashboardSummary.unavailable();
        }
    }

    public DashboardTrends trends(Instant asOf) {
        try {
            DashboardTrends trends = restClient.get()
                .uri(builder -> builder.path("/internal/analytics/dashboard-trends")
                    .queryParam("asOf", asOf.toString()).build())
                .retrieve()
                .body(DashboardTrends.class);
            return trends == null ? DashboardTrends.unavailable() : trends;
        } catch (RuntimeException exception) {
            logger.warn("無法取得 analytics 的趨勢資料，圖表以空資料呈現: {}", exception.getMessage());
            return DashboardTrends.unavailable();
        }
    }

    public record DashboardSummary(long totalPurchaseRequests, long succeededPurchaseRequests,
                                    Map<String, Long> ordersByStatus, BigDecimal totalPaidAmount) {
        static DashboardSummary unavailable() {
            return new DashboardSummary(0, 0, Map.of(), BigDecimal.ZERO);
        }
    }

    public record TrendPoint(Instant bucketStart, long purchaseRequestCount, long orderCount) {}

    public record DashboardTrends(List<TrendPoint> lastHour, List<TrendPoint> last24Hours) {
        static DashboardTrends unavailable() {
            return new DashboardTrends(List.of(), List.of());
        }
    }
}
