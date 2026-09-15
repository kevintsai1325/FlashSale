package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link OrderPurchaseConsumer}'s {@code ConsumedMessageGuard} check actually prevents a
 * redelivered/duplicated message from creating a second order — simulates the redelivery
 * RabbitMQ would perform after an unacked message by manually publishing the exact same
 * {@code eventId} header + body twice.
 */
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderPurchaseConsumerRedeliveryIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderPurchaseConsumer consumer;

    @Test
    void redeliveredMessageWithTheSameOutboxEventIdDoesNotCreateASecondOrder() throws Exception {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (999, 'redelivery@example.com', 'x', 'USER', 'ACTIVE')");

        String payload = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("purchaseRequestId", "99900000-0000-0000-0000-000000000000");
            put("userId", 999);
            put("flashSaleId", 1);
            put("productId", 1);
            put("quantity", 1);
            put("unitPrice", 9.99);
        }});

        for (int i = 0; i < 2; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setHeader("eventId", "55500000-0000-0000-0000-000000000000");
            Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
            consumer.handle(message);
        }

        // P4：終態由 backend 寫進 outbox 再送回 purchase-service，不再直接改 purchase_requests。
        // 重投遞必須只產生一個終態事件——多一個就代表 purchase-service 會被通知兩次。
        Integer resolvedCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'PurchaseResolved' " +
            "and payload::text like '%\"purchaseRequestId\": 999%' and payload::text like '%\"status\": \"SUCCEEDED\"%'",
            Integer.class);
        assertThat(resolvedCount).as("a redelivered message must not report the terminal state twice").isEqualTo(1);

        Integer orderCount = jdbcTemplate.queryForObject(
            "select count(*) from orders where user_id = 999", Integer.class);
        assertThat(orderCount).as("a redelivered message with the same eventId must not create a second order").isEqualTo(1);
    }
}
