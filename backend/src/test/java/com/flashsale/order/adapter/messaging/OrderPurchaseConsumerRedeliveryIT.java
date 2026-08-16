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
 * {@code outboxEventId} header + body twice.
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
        jdbcTemplate.update(
            "insert into purchase_requests (id, request_id, idempotency_key, user_id, flash_sale_id, status) " +
            "values (999, gen_random_uuid(), 'redelivery-key', 999, 1, 'PENDING')");

        String payload = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("purchaseRequestId", 999);
            put("userId", 999);
            put("flashSaleId", 1);
            put("productId", 1);
            put("quantity", 1);
            put("unitPrice", 9.99);
        }});

        for (int i = 0; i < 2; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setHeader("outboxEventId", 555L);
            Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
            consumer.handle(message);
        }

        String status = jdbcTemplate.queryForObject("select status from purchase_requests where id = 999", String.class);
        assertThat(status).isEqualTo("SUCCEEDED");

        Integer orderCount = jdbcTemplate.queryForObject(
            "select count(*) from orders where user_id = 999", Integer.class);
        assertThat(orderCount).as("a redelivered message with the same outboxEventId must not create a second order").isEqualTo(1);
    }
}
