package com.flashsale.analytics;

import com.flashsale.analytics.web.InternalAnalyticsController;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 端到端地證明讀取模型會被事件餵出來，包含這個服務存在的三個理由：
 *
 * <ol>
 *   <li>兩條事件流（訂單、搶購請求）併成同一份可查詢的聚合。</li>
 *   <li>**重複投遞不會多算**。發佈端是「至少一次」，而這裡沒有去重表 ——
 *       冪等來自「以來源 id 為主鍵的 upsert」這個資料模型選擇，這條測試就是守它的。</li>
 *   <li>狀態轉移事件會更新既有的投影，而不是新增一列。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("integration-test")
class ProjectionIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired InternalAnalyticsController controller;

    @AfterEach
    void clearProjections() {
        jdbcTemplate.execute("TRUNCATE TABLE order_projection, purchase_request_projection");
    }

    private void publish(String topic, String key, String payload) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, payload)).get();
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String orderCreated(long orderId, String amount) {
        return """
            {"orderId":%d,"orderNo":"ORD-%d","userId":1,"flashSaleId":7,"productId":3,
             "productName":"item","quantity":1,"unitPrice":%s,"totalAmount":%s,
             "createdAt":"2026-09-15T10:00:00Z"}
            """.formatted(orderId, orderId, amount, amount);
    }

    private static String orderPaid(long orderId, String amount) {
        return """
            {"orderId":%d,"flashSaleId":7,"fromStatus":"PENDING_PAYMENT","toStatus":"PAID",
             "totalAmount":%s,"changedAt":"2026-09-15T10:05:00Z"}
            """.formatted(orderId, amount);
    }

    private static String purchaseCreated(String requestId, String status) {
        return """
            {"requestId":"%s","userId":1,"flashSaleId":7,"status":"%s",
             "createdAt":"2026-09-15T10:00:00Z"}
            """.formatted(requestId, status);
    }

    private static String purchaseResolved(String requestId, long orderId) {
        return """
            {"requestId":"%s","userId":1,"flashSaleId":7,"status":"SUCCEEDED","orderId":%d,
             "resolvedAt":"2026-09-15T10:00:01Z"}
            """.formatted(requestId, orderId);
    }

    @Test
    void buildsTheDashboardAggregatesFromBothEventStreams() throws Exception {
        String succeeded = "11111111-1111-1111-1111-111111111111";
        String soldOut = "22222222-2222-2222-2222-222222222222";

        publish("flashsale.order-events", "7", orderCreated(1, "19.99"));
        publish("flashsale.order-events", "7", orderCreated(2, "5.00"));
        publish("flashsale.order-events", "7", orderPaid(1, "19.99"));
        publish("flashsale.purchase-events", "7", purchaseCreated(succeeded, "PENDING"));
        publish("flashsale.purchase-events", "7", purchaseCreated(soldOut, "SOLD_OUT"));
        publish("flashsale.purchase-events", "7", purchaseResolved(succeeded, 1));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            InternalAnalyticsController.DashboardSummary summary = controller.summary();
            assertThat(summary.totalPurchaseRequests()).isEqualTo(2);
            assertThat(summary.succeededPurchaseRequests()).isEqualTo(1);
            assertThat(summary.ordersByStatus()).containsEntry("PAID", 1L).containsEntry("PENDING_PAYMENT", 1L);
            assertThat(summary.totalPaidAmount()).isEqualByComparingTo("19.99");
        });
    }

    @Test
    void redeliveringTheSameEventsDoesNotDoubleCount() throws Exception {
        String requestId = "33333333-3333-3333-3333-333333333333";

        for (int attempt = 0; attempt < 3; attempt++) {
            publish("flashsale.order-events", "7", orderCreated(9, "19.99"));
            publish("flashsale.purchase-events", "7", purchaseCreated(requestId, "PENDING"));
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            InternalAnalyticsController.DashboardSummary summary = controller.summary();
            assertThat(summary.ordersByStatus().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);
            assertThat(summary.totalPurchaseRequests()).isEqualTo(1);
        });
    }
}
