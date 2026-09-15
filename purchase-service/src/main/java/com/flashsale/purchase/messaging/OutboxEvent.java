package com.flashsale.purchase.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 這個服務自己資料庫裡的 outbox 表（P4 步驟 2 起）。
 *
 * outbox 模式的整個重點是「事件的寫入與業務資料的寫入在同一個本地交易裡」。
 * 步驟 1 共用資料庫時這一點其實是勉強成立的 —— 交易確實是同一個，但那個資料庫
 * 不屬於這個服務。拆庫之後它才真正成立。
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
    public StoredTraceContext getTraceContext() { return traceContext; }
}
