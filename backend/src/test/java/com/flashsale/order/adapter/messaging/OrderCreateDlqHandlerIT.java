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
        jdbcTemplate.update(
            "insert into purchase_requests (id, request_id, idempotency_key, user_id, flash_sale_id, status) " +
            "values (998, gen_random_uuid(), 'dlq-key', 998, 1, 'PENDING')");

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("purchaseRequestId", 998);
            put("userId", 998);
            put("flashSaleId", 1);
            put("productId", 1);
            put("quantity", 1);
            put("unitPrice", 9.99);
        }});
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        handler.handle(message);

        String status = jdbcTemplate.queryForObject("select status from purchase_requests where id = 998", String.class);
        assertThat(status).isEqualTo("FAILED");

        Integer releaseEventCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'StockReleaseRequested' and payload::text like '%\"flashSaleId\": 1%'",
            Integer.class);
        assertThat(releaseEventCount).isEqualTo(1);
    }
}
