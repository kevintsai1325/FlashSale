package com.flashsale.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * 「目前有哪些活動正在進行」由 platform 回答 —— 活動資料的所有權在那裡（P5）。
 *
 * 只有庫存對帳排程用得到，每分鐘一次，不在任何熱路徑上。失敗時回空清單而不是丟例外：
 * 對帳是修正性的工作，晚一分鐘做沒有損失；而在拿不到清單時去猜「全部活動」，
 * 會把已經結束的活動也重新種回 Redis。
 */
@Component
public class PlatformFlashSaleClient {

    private static final Logger logger = LoggerFactory.getLogger(PlatformFlashSaleClient.class);

    private final RestClient restClient;

    public PlatformFlashSaleClient(RestClient.Builder restClientBuilder,
                                    @Value("${app.platform.base-url}") String baseUrl,
                                    @Value("${app.platform.connect-timeout-ms}") long connectTimeoutMs,
                                    @Value("${app.platform.read-timeout-ms}") long readTimeoutMs) {
        this.restClient = restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
            .build();
    }

    public List<Long> activeFlashSaleIds() {
        try {
            List<Long> ids = restClient.get()
                .uri("/internal/flash-sales/active")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<Long>>() {});
            return ids == null ? List.of() : ids;
        } catch (RuntimeException exception) {
            logger.warn("無法取得進行中的活動清單，這一輪庫存對帳跳過: {}", exception.getMessage());
            return List.of();
        }
    }
}
