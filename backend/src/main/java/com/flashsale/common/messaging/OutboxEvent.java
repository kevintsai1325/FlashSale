package com.flashsale.common.messaging;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 事件的全域唯一識別碼，消費端用它去重。**不能用上面那個 id** ——
    // 它是這個資料庫的序號，拆庫之後每個服務都從 1 開始，跨服務的去重表會直接撞在一起。
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trace_context", columnDefinition = "jsonb")
    private StoredTraceContext traceContext;

    protected OutboxEvent() {}

    public static OutboxEvent create(String aggregateType, String aggregateId, String eventType,
                                     String payloadJson, StoredTraceContext traceContext) {
        OutboxEvent event = new OutboxEvent();
        event.eventId = UUID.randomUUID();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payloadJson;
        event.traceContext = traceContext;
        event.createdAt = Instant.now();
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
    public StoredTraceContext getTraceContext() { return traceContext; }
}
