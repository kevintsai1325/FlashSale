package com.flashsale.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Insert-only record of an order's status transitions. Written once per transition by
 * {@link com.flashsale.order.application.OrderStatusHistoryRepository#record}; nothing about a
 * row is ever mutated after creation, so this entity exposes no setters beyond the static factory.
 */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private OrderStatus toStatus;

    // DB-defaulted (now()); never set from Java.
    @Column(name = "changed_at", insertable = false, updatable = false)
    private Instant changedAt;

    protected OrderStatusHistory() {}

    public static OrderStatusHistory of(Long orderId, OrderStatus fromStatus, OrderStatus toStatus) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.orderId = orderId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        return history;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public OrderStatus getFromStatus() { return fromStatus; }
    public OrderStatus getToStatus() { return toStatus; }
    public Instant getChangedAt() { return changedAt; }
}
