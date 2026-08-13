package com.flashsale.common.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class OutboxPublisherIT {

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
    }

    @Autowired OutboxWriter outboxWriter;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;

    record Dummy(String note) {}

    @Test
    void writtenEventIsPublishedToRabbitAndMarkedPublished() {
        outboxWriter.write("Test", "1", EventTypes.CREATE_ORDER_REQUESTED, new Dummy("hello"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(com.flashsale.common.config.RabbitConfig.CREATE_ORDER_QUEUE);
            assertThat(message).isNotNull();
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("hello");
            assertThat(message.getMessageProperties().getHeaders()).containsKey("outboxEventId");
        });
    }

    @Test
    void validEventPublishedDespiteInvalidEventInBatch() {
        // Insert invalid event (unknown event type) directly via JDBC to simulate corruption scenario
        jdbcTemplate.update(
            "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, created_at) " +
            "VALUES (?, ?, ?, ?::jsonb, now())",
            "Test", "bad", "UnknownEventType", "{\"error\": \"bad event\"}"
        );

        // Insert valid event via normal writer
        outboxWriter.write("Test", "2", EventTypes.STOCK_RELEASE_REQUESTED, new Dummy("valid"));

        // Verify that valid event was published despite the invalid one in the batch
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(com.flashsale.common.config.RabbitConfig.STOCK_RELEASE_QUEUE);
            assertThat(message).isNotNull();
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("valid");
            assertThat(message.getMessageProperties().getHeaders()).containsKey("outboxEventId");
        });

        // Verify invalid event remains unpublished (null published_at)
        Long unpublishedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND published_at IS NULL",
            Long.class,
            "UnknownEventType"
        );
        assertThat(unpublishedCount).isEqualTo(1L);

        // Verify valid event was marked published
        Long publishedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND published_at IS NOT NULL",
            Long.class,
            EventTypes.STOCK_RELEASE_REQUESTED
        );
        assertThat(publishedCount).isEqualTo(1L);
    }
}
