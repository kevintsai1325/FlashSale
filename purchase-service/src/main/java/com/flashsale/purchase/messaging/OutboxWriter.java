package com.flashsale.purchase.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxWriter(OutboxEventJpaRepository repository, ObjectMapper objectMapper, Tracer tracer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    /** 走 RabbitMQ 的事件（命令）。沒有分區鍵。 */
    public void write(String aggregateType, String aggregateId, String eventType, Object payload) {
        write(aggregateType, aggregateId, eventType, payload, null);
    }

    /** {@code partitionKey} 非 null 時這是一個走 Kafka 的領域事件，鍵一律是 flashSaleId。 */
    public void write(String aggregateType, String aggregateId, String eventType, Object payload, String partitionKey) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(OutboxEvent.create(aggregateType, aggregateId, eventType, json, currentTraceContext(), partitionKey));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
    }

    private StoredTraceContext currentTraceContext() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        return new StoredTraceContext(
            1, context.traceId(), context.spanId(), Boolean.TRUE.equals(context.sampled()));
    }
}
