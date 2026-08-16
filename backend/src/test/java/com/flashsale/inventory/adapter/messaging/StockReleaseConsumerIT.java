package com.flashsale.inventory.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.inventory.application.InventoryStockGateway;
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
@Sql("/db/testdata/inventory-fixtures.sql")
class StockReleaseConsumerIT extends AbstractIntegrationTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired InventoryStockGateway inventoryStockGateway;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StockReleaseConsumer consumer;

    @Test
    void stockReleaseMessageIncrementsRedisBackUp() throws Exception {
        // Seed Redis at 0 (fully reserved) via a real reservation, then release 1 via the queue.
        inventoryStockGateway.reserve(1L, 1);
        assertThat(inventoryStockGateway.currentValue(1L)).contains(0);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("flashSaleId", 1);
            put("quantity", 1);
        }});
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("outboxEventId", 4242L);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        consumer.handle(message);

        assertThat(inventoryStockGateway.currentValue(1L)).contains(1);
    }
}
