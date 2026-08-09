package com.flashsale.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "payment_due_at")
    private Instant paymentDueAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public static Order createPendingPayment(Long userId, Long productId, int quantity, BigDecimal unitPrice) {
        Order order = new Order();
        order.orderNo = "ORD-" + UUID.randomUUID();
        order.userId = userId;
        order.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        order.status = OrderStatus.PENDING_PAYMENT;
        order.paymentDueAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        OrderItem item = OrderItem.of(productId, quantity, unitPrice);
        item.assignOrder(order);
        order.items.add(item);
        return order;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getUserId() { return userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public OrderStatus getStatus() { return status; }
    public Instant getPaymentDueAt() { return paymentDueAt; }
    public List<OrderItem> getItems() { return items; }
}
