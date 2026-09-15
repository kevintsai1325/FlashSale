package com.flashsale.common.messaging;

import com.flashsale.common.config.KafkaTopics;
import com.flashsale.common.config.RabbitConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 200;

    private final OutboxEventJpaRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ApplicationContext applicationContext;
    private final Tracer tracer;

    public OutboxPublisher(OutboxEventJpaRepository repository, RabbitTemplate rabbitTemplate,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ApplicationContext applicationContext, Tracer tracer) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.applicationContext = applicationContext;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelay = 500, initialDelay = 100)
    public void publishPending() {
        OutboxPublisher proxy = applicationContext.getBean(OutboxPublisher.class);
        List<OutboxEvent> batch = proxy.fetchBatch();
        for (OutboxEvent event : batch) {
            try {
                proxy.publishEvent(event);
            } catch (Exception e) {
                logger.error("Failed to publish event {}: {}", event.getId(), e.getMessage(), e);
            }
        }
    }

    // Own transaction so the FOR UPDATE row locks are released (commit) before publishEvent()'s
    // REQUIRES_NEW tries to UPDATE the same rows — otherwise it deadlocks against itself: this
    // thread would hold the lock while synchronously waiting on the nested transaction that needs it.
    @Transactional
    public List<OutboxEvent> fetchBatch() {
        return repository.findUnpublishedBatchForUpdate(BATCH_SIZE);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void publishEvent(OutboxEvent event) {
        sendWithStoredParent(event);

        // Refetch the event in this transaction's persistence context and mark it published
        OutboxEvent managedEvent = repository.findById(event.getId()).orElseThrow();
        managedEvent.markPublished();
        repository.saveAndFlush(managedEvent);
    }

    private void sendWithStoredParent(OutboxEvent event) {
        StoredTraceContext stored = event.getTraceContext();
        Span span;
        if (stored == null) {
            span = tracer.nextSpan().name("outbox publish").start();
        } else {
            TraceContext parent = tracer.traceContextBuilder()
                .traceId(stored.traceId())
                .spanId(stored.spanId())
                .sampled(stored.sampled())
                .build();
            span = tracer.spanBuilder()
                .setParent(parent)
                .name("outbox publish")
                .start();
        }
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            send(event);
        } catch (RuntimeException exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    /**
     * 同一張 outbox 表、同一套「至少一次」保證，兩種 transport。
     *
     * outbox 模式解的是「資料庫交易與訊息發佈的原子性」，那個問題與訊息送去哪裡無關 ——
     * 所以這裡只是多一個分支，而不是為 Kafka 另做一套 outbox（那等於把同一個問題解兩次，
     * 而且兩套的「至少一次」會有不同的漏洞）。
     */
    private void send(OutboxEvent event) {
        switch (event.getEventType()) {
            case EventTypes.ORDER_CREATED, EventTypes.ORDER_STATUS_CHANGED ->
                kafkaTemplate.send(KafkaTopics.ORDER_EVENTS, requirePartitionKey(event), event.getPayload());
            default -> rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, routingKeyFor(event.getEventType()),
                rabbitMessage(event));
        }
    }

    private static String requirePartitionKey(OutboxEvent event) {
        if (event.getPartitionKey() == null) {
            // 沒有鍵的話 Kafka 會輪詢分區，同一場活動的事件會被打散、順序保證消失。
            // 這是設計錯誤而不是可容忍的降級，所以直接失敗而不是預設成 null key。
            throw new IllegalStateException("Kafka-bound event " + event.getId() + " carries no partition key");
        }
        return event.getPartitionKey();
    }

    private Message rabbitMessage(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("eventId", event.getEventId().toString());
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);
    }

    private String routingKeyFor(String eventType) {
        return switch (eventType) {
            case EventTypes.CREATE_ORDER_REQUESTED -> RabbitConfig.CREATE_ORDER_ROUTING_KEY;
            case EventTypes.STOCK_RELEASE_REQUESTED -> RabbitConfig.STOCK_RELEASE_ROUTING_KEY;
            case EventTypes.PURCHASE_RESOLVED -> RabbitConfig.PURCHASE_RESOLVED_ROUTING_KEY;
            default -> throw new IllegalStateException("Unknown outbox event type: " + eventType);
        };
    }
}
