package com.flashsale.common.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * platform 對 order-service 的唯一出入口（P5）。放在 common 而不是某個模組底下，
 * 因為兩個模組都要用它：店面列表要庫存、後台要訂單與庫存。
 * 一個下游服務一個 client 是慣例；把它複製成兩份才是問題。
 *
 * **降級策略對兩種呼叫是相反的，而且理由不同：**
 * <ul>
 *   <li>庫存查詢失敗 → 回空 Map。店面列表寧可少顯示一個數字，也不要整頁打不開；
 *       顯示的庫存本來就是近似值（真正的預扣在 Redis）。</li>
 *   <li>訂單查詢失敗 → 讓例外往上拋。後台在看訂單，給他一個空清單會讓他以為
 *       「真的沒有訂單」，那比一個錯誤訊息危險得多。</li>
 * </ul>
 */
@Component
public class OrderServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceClient.class);

    private final RestClient restClient;

    @Autowired
    public OrderServiceClient(RestClient.Builder restClientBuilder,
                               @Value("${app.order-service.base-url}") String baseUrl,
                               @Value("${app.order-service.connect-timeout-ms}") long connectTimeoutMs,
                               @Value("${app.order-service.read-timeout-ms}") long readTimeoutMs) {
        this(restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
            .build());
    }

    OrderServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Map<Long, InventoryView> inventories(List<Long> flashSaleIds) {
        if (flashSaleIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, InventoryView> result = restClient.get()
                .uri(builder -> builder.path("/internal/inventories")
                    .queryParam("flashSaleIds", flashSaleIds.stream().map(String::valueOf).toList())
                    .build())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<Long, InventoryView>>() {});
            return result == null ? Map.of() : result;
        } catch (RuntimeException exception) {
            logger.warn("無法取得庫存資料，數量欄位以未知呈現: {}", exception.getMessage());
            return Map.of();
        }
    }

    /**
     * 宣告某場活動的庫存總量。**在 platform 的交易提交之前呼叫。**
     *
     * 順序是刻意的：遠端先成功、本地再提交。若本地提交失敗，order-service 會留下一列
     * 沒有活動指向它的庫存 —— 那是無害的孤兒（沒有任何流程會讀到它）。
     * 反過來的順序（先提交本地、再呼叫遠端）留下的是「有活動、沒庫存」，
     * 那會讓使用者搶購時直接失敗，是有害的。
     *
     * 兩邊都沒有原子性，所以要選一個「壞掉時比較無害」的方向 —— 拆庫之後的寫入路徑
     * 大多只能做到這個程度。這支端點是冪等的，呼叫端因此可以安全重試。
     */
    public InventoryView declareInventory(Long flashSaleId, int totalQuantity) {
        return restClient.put()
            .uri("/internal/inventories/{id}", flashSaleId)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(new DeclareInventoryRequest(totalQuantity))
            .retrieve()
            .body(InventoryView.class);
    }

    public PagedOrders orders(String status, int page, int size) {
        return restClient.get()
            .uri(builder -> {
                builder.path("/internal/orders").queryParam("page", page).queryParam("size", size);
                if (status != null) {
                    builder.queryParam("status", status);
                }
                return builder.build();
            })
            .retrieve()
            .body(PagedOrders.class);
    }

    public Optional<OrderDetail> orderDetail(Long orderId) {
        try {
            return Optional.ofNullable(restClient.get()
                .uri("/internal/orders/{id}", orderId)
                .retrieve()
                .body(OrderDetail.class));
        } catch (HttpClientErrorException.NotFound notFound) {
            // 「查不到」是權威的答案，不是失敗 —— 呼叫端要能分辨這兩者。
            return Optional.empty();
        }
    }

    public record DeclareInventoryRequest(int totalQuantity) {}

    public record InventoryView(int totalQuantity, int availableQuantity, int reservedQuantity, int soldQuantity) {}

    public record OrderSummary(Long id, String orderNo, Long userId, BigDecimal totalAmount,
                                String status, Instant createdAt) {}

    public record PagedOrders(List<OrderSummary> content, long totalElements, int page, int size) {}

    public record OrderItemView(Long productId, String productName, int quantity, BigDecimal unitPrice) {}

    public record StatusHistoryView(String fromStatus, String toStatus, Instant changedAt) {}

    public record OrderDetail(Long id, String orderNo, Long userId, BigDecimal totalAmount, String status,
                               Instant paymentDueAt, Instant createdAt, Long flashSaleId, UUID purchaseRequestId,
                               List<OrderItemView> items, List<StatusHistoryView> statusHistory) {}
}
