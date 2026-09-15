package com.flashsale.common.messaging;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
class OutboxPublisherIT extends AbstractIntegrationTest {

    @Autowired OutboxWriter outboxWriter;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired OutboxPublisher outboxPublisher;

    record Dummy(String note) {}

    @BeforeEach
    void cleanSlate() {
        // Each test's assertions scan the whole outbox_events table / whole queue, so a row or
        // message left over from the other test (run order is unspecified) would corrupt them.
        jdbcTemplate.update("DELETE FROM outbox_events");
        drainQueue(com.flashsale.common.config.RabbitConfig.CREATE_ORDER_QUEUE);
        drainQueue(com.flashsale.common.config.RabbitConfig.STOCK_RELEASE_QUEUE);
    }

    private void drainQueue(String queue) {
        while (rabbitTemplate.receive(queue) != null) {
            // discard
        }
    }

    @Test
    void writtenEventIsPublishedToRabbitAndMarkedPublished() {
        outboxWriter.write("Test", "1", EventTypes.CREATE_ORDER_REQUESTED, new Dummy("hello"));
        outboxPublisher.publishPending();

        Message message = rabbitTemplate.receive(com.flashsale.common.config.RabbitConfig.CREATE_ORDER_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("hello");
        assertThat(message.getMessageProperties().getHeaders()).containsKey("eventId");
    }

    @Test
    void validEventPublishedDespiteInvalidEventInBatch() {
        // Insert invalid event (unknown event type) directly via JDBC to simulate corruption scenario
        jdbcTemplate.update(
            "INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb, now())",
            "Test", "bad", "UnknownEventType", "{\"error\": \"bad event\"}"
        );

        // Insert valid event via normal writer
        outboxWriter.write("Test", "2", EventTypes.STOCK_RELEASE_REQUESTED, new Dummy("valid"));
        outboxPublisher.publishPending();

        Message message = rabbitTemplate.receive(com.flashsale.common.config.RabbitConfig.STOCK_RELEASE_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("valid");
        assertThat(message.getMessageProperties().getHeaders()).containsKey("eventId");

        Long unpublishedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND published_at IS NULL",
            Long.class, "UnknownEventType");
        assertThat(unpublishedCount).isEqualTo(1L);

        Long publishedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND published_at IS NOT NULL",
            Long.class, EventTypes.STOCK_RELEASE_REQUESTED);
        assertThat(publishedCount).isEqualTo(1L);

        // Cleanup: delete the permanently-unpublishable event to prevent it from spamming ERROR logs
        // when the scheduler continues polling for the rest of the test suite
        jdbcTemplate.update("DELETE FROM outbox_events WHERE event_type = ?", "UnknownEventType");
    }
}
