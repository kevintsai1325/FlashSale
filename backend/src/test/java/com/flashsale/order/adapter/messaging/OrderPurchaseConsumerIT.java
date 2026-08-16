package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.OutboxPublisher;
import com.flashsale.testsupport.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class OrderPurchaseConsumerIT extends AbstractIntegrationTest {

    // Test-only RSA key pair (same literal as every other web IT — see PurchaseControllerIT),
    // needed only because JwtKeyConfig requires JWT_PRIVATE_KEY/JWT_PUBLIC_KEY to build the app
    // context. Kept as a plain duplicated literal per this codebase's established convention
    // (see the Global Constraints note on the deferred shared-base-class DRY cleanup).
    private static final String TEST_PRIVATE_KEY = """
        -----BEGIN PRIVATE KEY-----
        MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC4Y9PChpJUXfJi
        EKskFLGDQ1AABZoj6OTbUXa0KyTUc5Y67oDW6YogCYsf562xnQHRwOLD1KNaXj06
        hKMDmC8HQL0GBDF6FTCR83uvyqfUJ4Hpssp8YQov29EK6XmvtzCgJnuqAmA8J8CX
        W3ghiURk0cuAEM5FcZJpS8Ff3sgq/iNR/TOoOmlLoF87tCapz7eAAvO1p0TkB/wr
        OklOEM8YNs+3Kz4hkKjddXz4TP6nJ+4yh9E7KlLhwaF3KkSRKgEhFJGrTDW97Ecj
        wMRsjKwTWOKED1eS6E5hXsg5JjBMTgYqLkME8gRH5jW+os71syfOj5Ao6Nc7DyFZ
        oUj3Q1pfAgMBAAECggEAB0Rv+S/Cnq6hOfo8NIzYPjst8QJHg/jO5FH+orU8m17+
        4c26qD3GIuMdZ6GC+AgfJTw788nyskIama7WmfKqj9eeW5lYtd4V7vqwukn7eWIh
        Pau9TU+pzh8UyyBOmn1W3kkGALpdPqG2doC1aGT3nB2krqR67MPAKIRC19t4+jC+
        3HPVLiIdMy2ayF3xOoTVKAxJjGIKTGSPbEZu8ANOjeWBZ6xTgjYa0pTGgFzy5yg3
        hleW0X6htbcik5cOhwEH0+zhmXGNzF4ktbSihOU+fyTCdL/QpsUPtbImHM/Gf2GT
        NMKI37R1uErJcYzLIQNB8srehWyqtAbRd8Pj0zNxoQKBgQDng7TUMa8uBJqSOh/M
        fX/S8SN3oS7EYdGeChapm2rjfxiuevF17aaXyfz8vBMxw7S26Eifl9IkPI+X4iGH
        jSaHT9RSW4LGLMQq2t7t2yEFv9BE7U7WRecfUewHg5Th3yR3XTTjG0k68jFiuVI9
        pBJrWTzumYJLQAcmVa+j7dWT0QKBgQDL5DdcylyEluyhf/iw8oZvbCQCZ/Y0WX4K
        y2xywcmkhDMcddXDMaxRbeYCQNbc1al13zJ98bIN550+u20GjIgAgvu16eMUyllC
        2nCh/MeVozE9mbLJyoF7sjM+FjXljZBgFj3lSltwW+ZnwDY8gBJrLvkapJdlKPdh
        Vnd7svqHLwKBgDwnGGDZ1+5Y++BqgcCcCw4/4TtAAeq8j75EWMcQvqEFcOBEyWAe
        s15U+Qqhw0r20omDqPruc4c+xQBtnNCfeBdIQp5zcHMVRpLr82hRuy7HO9Hs5sL9
        vqOAoZcCNTjKxarN6OPpPwm1y+cex6OEcdS6hv5nnFb49+KZ+Nza+tdBAoGAcbEQ
        Le2pKUX/LQ7u3bxeukLS0YSnBQnh/qLwFg15IwOUfIo4aF+Kdt2RJDCDnyCFHfUX
        cqMTZi2AwTpB0SULsT1YnleNCErM+zpTFACgShB1pKPPzjXdfdwgNr6rzxThLLM6
        UGDmHAEiuTe1BodjveCzhufAg+gUCXLtrUxf5oECgYAcf3bOkoST/7m3wDArGZHi
        bxa6mDItSNNoQo45H4liID3CMD4pnUGorhZ/oTF1dWwO5Jqeg4DTufkV+lHmVECZ
        HkpcA3daUkxZ5xjGl9I9L84A2e4z2ZJKerTvR+KlLP64A70YDawGpTxO8QH6fjPG
        1sN0RkZQZpMJPN4/1lsz6g==
        -----END PRIVATE KEY-----
        """;

    private static final String TEST_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuGPTwoaSVF3yYhCrJBSx
        g0NQAAWaI+jk21F2tCsk1HOWOu6A1umKIAmLH+etsZ0B0cDiw9SjWl49OoSjA5gv
        B0C9BgQxehUwkfN7r8qn1CeB6bLKfGEKL9vRCul5r7cwoCZ7qgJgPCfAl1t4IYlE
        ZNHLgBDORXGSaUvBX97IKv4jUf0zqDppS6BfO7Qmqc+3gALztadE5Af8KzpJThDP
        GDbPtys+IZCo3XV8+Ez+pyfuMofROypS4cGhdypEkSoBIRSRq0w1vexHI8DEbIys
        E1jihA9XkuhOYV7IOSYwTE4GKi5DBPIER+Y1vqLO9bMnzo+QKOjXOw8hWaFI90Na
        XwIDAQAB
        -----END PUBLIC KEY-----
        """;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MeterRegistry meterRegistry;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired OrderPurchaseConsumer consumer;

    private void publishAndConsumeNextOrderRequest() throws Exception {
        outboxPublisher.publishPending();
        Message message = rabbitTemplate.receive(RabbitConfig.CREATE_ORDER_QUEUE, 5_000);
        assertThat(message).isNotNull();
        consumer.handle(message);
    }

    private String requestBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
    }

    private String registerAndLogin(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(requestBody(email, password)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(requestBody(email, password)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @Sql("/db/testdata/inventory-fixtures.sql")
    void pendingPurchaseRequestEventuallyBecomesSucceededWithARealOrder() throws Exception {
        String token = registerAndLogin("consumer-test@example.com", "secret123");

        MvcResult result = mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "consumer-key-1")
                .contentType(APPLICATION_JSON)
                .content("{\"quantity\":1}"))
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        String requestId = body.get("requestId").asText();
        publishAndConsumeNextOrderRequest();

        MvcResult poll = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/purchase-requests/" + requestId)
                .header("Authorization", "Bearer " + token))
            .andReturn();
        JsonNode polled = objectMapper.readTree(poll.getResponse().getContentAsString());
        assertThat(polled.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(polled.get("orderId").isNull()).isFalse();

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
    }

    @Test
    void successfulOrderCreationIncrementsOrderCreatedCounter() throws Exception {
        double before = meterRegistry.find("purchase.order.created").counter() == null
            ? 0.0 : meterRegistry.find("purchase.order.created").counter().count();

        // Dedicated flash sale id (2), distinct from the id=1 fixture used by
        // pendingPurchaseRequestEventuallyBecomesSucceededWithARealOrder() above: the shared
        // Postgres container persists across test methods in this class, so reusing the
        // class-level inventory-fixtures.sql id=1 row here would either duplicate-key on the
        // INSERT (if that test's @Sql fixture already ran) or exhaust the single unit of stock
        // it seeds. Same pattern as PaymentControllerIT's per-test product ids.
        jdbcTemplate.update("INSERT INTO products (id, name, description) VALUES (2, 'Metrics Test Product', 'Only 1 pair')");
        jdbcTemplate.update("INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "VALUES (2, 2, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "VALUES (2, 2, 1, 1, 0, 0, 0)");

        // Same flow as pendingPurchaseRequestEventuallyBecomesSucceededWithARealOrder() above:
        // register+login, submit a purchase request, wait for it to resolve to a real order.
        String token = registerAndLogin("consumer-metrics-test@example.com", "secret123");
        mockMvc.perform(post("/api/flash-sales/2/purchase-requests")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "consumer-metrics-key-1")
                .contentType(APPLICATION_JSON)
                .content("{\"quantity\":1}"));
        publishAndConsumeNextOrderRequest();

        assertThat(meterRegistry.get("purchase.order.created").counter().count()).isGreaterThan(before);
    }
}
