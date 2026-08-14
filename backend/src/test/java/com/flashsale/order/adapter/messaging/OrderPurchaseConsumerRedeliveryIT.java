package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves {@link OrderPurchaseConsumer}'s {@code ConsumedMessageGuard} check actually prevents a
 * redelivered/duplicated message from creating a second order — simulates the redelivery
 * RabbitMQ would perform after an unacked message by manually publishing the exact same
 * {@code outboxEventId} header + body twice.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderPurchaseConsumerRedeliveryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
    }

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

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
            rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.CREATE_ORDER_ROUTING_KEY, message);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject("select status from purchase_requests where id = 999", String.class);
            assertThat(status).isEqualTo("SUCCEEDED");
        });

        Integer orderCount = jdbcTemplate.queryForObject(
            "select count(*) from orders where user_id = 999", Integer.class);
        assertThat(orderCount).as("a redelivered message with the same outboxEventId must not create a second order").isEqualTo(1);
    }
}
