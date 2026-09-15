package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates a message that already exhausted its retries and landed on the DLQ directly (rather
 * than actually forcing {@link OrderPurchaseConsumer} to fail 3 times) — publishes straight to
 * {@code order.create.queue.dlq} and asserts the handler's compensation.
 */
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderCreateDlqHandlerIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderCreateDlqHandler handler;

    @Test
    void dlqMessageMarksPurchaseRequestFailedAndWritesStockReleaseOutboxEvent() throws Exception {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (998, 'dlq@example.com', 'x', 'USER', 'ACTIVE')");

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("purchaseRequestId", "99800000-0000-0000-0000-000000000000");
            put("userId", 998);
            put("flashSaleId", 1);
            put("productId", 1);
            put("quantity", 1);
            put("unitPrice", 9.99);
        }});
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // P4：冪等的來源從「讀 purchase_requests 的狀態」換成去重表，所以這個 header 是必要的，
        // 不再只是追蹤用的裝飾。
        props.setHeader("eventId", "49980000-0000-0000-0000-000000000000");
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        handler.handle(message);

        // P4：backend 不再直接把 purchase_requests 改成 FAILED —— 那張表的寫入權責在
        // purchase-service。補償的結果改成一個終態事件送回去。
        Integer resolvedFailedCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'PurchaseResolved' " +
            "and payload::text like '%\"purchaseRequestId\": 998%' and payload::text like '%\"status\": \"FAILED\"%'",
            Integer.class);
        assertThat(resolvedFailedCount).isEqualTo(1);

        Integer releaseEventCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'StockReleaseRequested' and payload::text like '%\"flashSaleId\": 1%'",
            Integer.class);
        assertThat(releaseEventCount).isEqualTo(1);
    }
}
