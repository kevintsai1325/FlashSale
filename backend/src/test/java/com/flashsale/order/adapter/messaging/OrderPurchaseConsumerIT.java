package com.flashsale.order.adapter.messaging;

import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxPublisher;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.testsupport.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 之後 backend 的輸入不再是 HTTP，而是 purchase-service 送來的 order.create 事件，
 * 所以這個測試也從那裡開始：直接寫一筆 outbox 事件、發佈、消費，再檢查資料庫。
 *
 * 拆分前這個測試是打 /api/flash-sales/{id}/purchase-requests 再輪詢狀態的；那條路徑現在
 * 屬於 purchase-service，backend 已經沒有那兩個端點，留在這裡只會測到不存在的東西。
 */
class OrderPurchaseConsumerIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MeterRegistry meterRegistry;
    @Autowired OutboxWriter outboxWriter;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired OrderPurchaseConsumer consumer;

    private static final long BUYER_ID = 202L;
    private static final UUID REQUEST_ONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REQUEST_TWO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // orders.user_id 有外鍵指向 users。拆分前這一步是靠 HTTP 註冊帶出來的，
    // 現在測試直接從事件開始，就必須自己把買家種進去。
    private void seedBuyer() {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (?, ?, 'x', 'USER', 'ACTIVE') on conflict (id) do nothing",
            BUYER_ID, "consumer-it-" + BUYER_ID + "@example.com");
    }

    private void requestOrder(UUID purchaseRequestId, long flashSaleId, long productId, int quantity) throws Exception {
        outboxWriter.write("PurchaseRequest", purchaseRequestId.toString(), EventTypes.CREATE_ORDER_REQUESTED,
            new CreateOrderRequestedEvent(purchaseRequestId, BUYER_ID, flashSaleId, productId, quantity, new BigDecimal("9.99")));
        outboxPublisher.publishPending();
        Message message = rabbitTemplate.receive(RabbitConfig.CREATE_ORDER_QUEUE, 5_000);
        assertThat(message).isNotNull();
        consumer.handle(message);
    }

    @Test
    @Sql("/db/testdata/inventory-fixtures.sql")
    void createsTheOrderDecrementsStockAndReportsTheTerminalStateBack() throws Exception {
        seedBuyer();
        requestOrder(REQUEST_ONE, 1L, 1L, 1);

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        assertThat(orderCount).isEqualTo(1);

        Integer remainingStock = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(remainingStock).isEqualTo(0);

        Long createdOrderId = jdbcTemplate.queryForObject("select id from orders limit 1", Long.class);
        Integer historyCount = jdbcTemplate.queryForObject(
            "select count(*) from order_status_history where order_id = ? and from_status is null and to_status = 'PENDING_PAYMENT'",
            Integer.class, createdOrderId);
        assertThat(historyCount).isEqualTo(1);

        // 終態回寫給 purchase-service：拆分後這是搶購結果回到使用者眼前的唯一路徑。
        String resolvedPayload = jdbcTemplate.queryForObject(
            "select payload::text from outbox_events where event_type = 'PurchaseResolved'", String.class);
        assertThat(resolvedPayload)
            .contains("\"purchaseRequestId\": \"" + REQUEST_ONE + "\"")
            .contains("\"status\": \"SUCCEEDED\"")
            .contains("\"orderId\": " + createdOrderId);
    }

    @Test
    void successfulOrderCreationIncrementsOrderCreatedCounter() throws Exception {
        double before = meterRegistry.find("purchase.order.created").counter() == null
            ? 0.0 : meterRegistry.find("purchase.order.created").counter().count();

        seedBuyer();
        jdbcTemplate.update("INSERT INTO products (id, name, description) VALUES (2, 'Metrics Test Product', 'Only 1 pair')");
        jdbcTemplate.update("INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "VALUES (2, 2, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "VALUES (2, 2, 1, 1, 0, 0, 0)");

        requestOrder(REQUEST_TWO, 2L, 2L, 1);

        assertThat(meterRegistry.get("purchase.order.created").counter().count()).isGreaterThan(before);
    }
}
