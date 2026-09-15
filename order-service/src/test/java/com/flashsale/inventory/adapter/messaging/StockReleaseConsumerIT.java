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
        // P5：預扣（那段 Lua）在 purchase-service，這個服務不做預扣 ——
        // 所以「售罄」的起始狀態改用 resync 直接設成 0。這條測試要證明的是補償會把數字加回去，
        // 那件事與它是怎麼變成 0 的無關。
        inventoryStockGateway.resync(1L, 0);
        assertThat(inventoryStockGateway.currentValue(1L)).contains(0);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("flashSaleId", 1);
            put("quantity", 1);
        }});
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("eventId", "42420000-0000-0000-0000-000000000000");
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        consumer.handle(message);

        assertThat(inventoryStockGateway.currentValue(1L)).contains(1);
    }
}
