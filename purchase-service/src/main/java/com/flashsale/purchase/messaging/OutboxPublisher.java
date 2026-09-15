package com.flashsale.purchase.messaging;

import com.flashsale.purchase.config.KafkaTopics;
import com.flashsale.purchase.config.RabbitConfig;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 與 backend 的發佈器是同一套機制，但 P4 步驟 2 起各自撈**自己資料庫裡的**那張表。
 *
 * SKIP LOCKED 仍然是必要的 —— 這個服務自己就有三個副本，三份排程同時撈同一張表。
 * 它不再是為了「兩個不同的服務共用一張表」。
 */
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

    // 自己的交易：FOR UPDATE 的列鎖必須在 publishEvent() 的 REQUIRES_NEW 去 UPDATE 同一批列
    // 之前就釋放，否則會自己鎖住自己。
    @Transactional
    public List<OutboxEvent> fetchBatch() {
        return repository.findUnpublishedBatchForUpdate(BATCH_SIZE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishEvent(OutboxEvent event) {
        sendWithStoredParent(event);

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

    /** 同一張 outbox、同一套「至少一次」保證，兩種 transport（理由見 backend 的同名類別）。 */
    private void send(OutboxEvent event) {
        switch (event.getEventType()) {
            case EventTypes.PURCHASE_REQUEST_CREATED, EventTypes.PURCHASE_REQUEST_RESOLVED ->
                kafkaTemplate.send(KafkaTopics.PURCHASE_EVENTS, requirePartitionKey(event), event.getPayload());
            default -> rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, routingKeyFor(event.getEventType()),
                rabbitMessage(event));
        }
    }

    private static String requirePartitionKey(OutboxEvent event) {
        if (event.getPartitionKey() == null) {
            // 沒有鍵時 Kafka 會輪詢分區，同一場活動的事件被打散、順序保證消失。
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
            default -> throw new IllegalStateException("Unknown outbox event type: " + eventType);
        };
    }
}
