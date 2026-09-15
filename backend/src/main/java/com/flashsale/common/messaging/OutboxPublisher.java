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
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 200;
    // 比 Kafka producer 的 delivery.timeout 稍長一點，讓 producer 自己的重試先用完。
    private static final int KAFKA_SEND_TIMEOUT_SECONDS = 12;

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
        applicationContext.getBean(OutboxPublisher.class).publishBatch();
    }

    /**
     * 撈一批、發出去、標記已發佈 —— **全部在同一個交易裡**。
     *
     * 這個形狀是 2026-09-15 改的，原本是「先撈並提交，再各自用 REQUIRES_NEW 發佈」。
     * 那樣寫的理由是避免巢狀交易自己鎖自己，但代價是 {@code SKIP LOCKED} 幾乎沒有作用：
     * 列鎖在發佈之前就釋放了，其他副本的下一輪輪詢照樣撈得到同一列。
     * 實測三副本時**幾乎每一筆事件都被發佈兩次**。
     *
     * 鎖持有到交易結束，其他副本就會整批跳過。代價是交易期間包含一次網路往返，
     * 所以 Kafka producer 的逾時必須壓短（見 application.yml 的 delivery-timeout），
     * 否則 broker 掛掉時這個交易會連同它的列鎖一起卡住。
     *
     * 仍然是**至少一次**：送出去之後、提交之前當掉，下一輪會再送一次。
     * 所以每筆事件都帶著 eventId，消費端據此去重 —— 這不是可選的。
     *
     * 單筆事件發佈失敗不重拋：一顆壞掉的事件不該讓整批回滾，
     * 它的 published_at 沒被設定，下一輪自然會重試。
     */
    @Transactional
    public void publishBatch() {
        for (OutboxEvent event : repository.findUnpublishedBatchForUpdate(BATCH_SIZE)) {
            try {
                sendWithStoredParent(event);
                event.markPublished();
            } catch (Exception e) {
                logger.error("Failed to publish event {}: {}", event.getId(), e.getMessage(), e);
            }
        }
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
                sendToKafka(KafkaTopics.ORDER_EVENTS, event);
            default -> rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, routingKeyFor(event.getEventType()),
                rabbitMessage(event));
        }
    }

    /**
     * 同步等待 broker 的 ack，不是 fire-and-forget。
     *
     * 這是承重的：標記 published_at 與這次發送在同一個交易裡，如果不等 ack 就提交，
     * 送失敗的事件會被標記成已發佈、永遠補不回來 —— 從「至少一次」掉成「至多一次」。
     *
     * 代價是一批事件會逐筆序列化等待。目前的量級（每秒個位數到數十筆）看不出差別；
     * ponytail: 真的成為瓶頸時，改成「整批先送、收集 future、再一起等」，
     * 那需要 Rabbit 那條路也一起改成非同步確認，不是現在該付的複雜度。
     */
    private void sendToKafka(String topic, OutboxEvent event) {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(topic, requirePartitionKey(event), event.getPayload());
        // 消費端去重用的識別碼。Kafka 沒有 RabbitMQ 那種 message id 概念，用 header 帶。
        record.headers().add("eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.getId(), interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Failed to publish " + event.getId() + " to " + topic, failure);
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
