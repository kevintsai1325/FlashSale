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

    // 訂單自己記得來自哪一場活動、哪一筆搶購請求。補償流程需要 flashSaleId 才能釋放庫存，
    // 而 purchase_requests 已經是別的服務的資料 —— 反查它就是跨服務查詢。
    @Column(name = "flash_sale_id")
    private Long flashSaleId;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "payment_due_at")
    private Instant paymentDueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public static Order createPendingPayment(Long userId, Long flashSaleId, UUID purchaseRequestId, Long productId,
                                              String productName, int quantity, BigDecimal unitPrice) {
        Order order = new Order();
        order.orderNo = "ORD-" + UUID.randomUUID();
        order.userId = userId;
        order.flashSaleId = flashSaleId;
        order.purchaseRequestId = purchaseRequestId;
        order.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        order.status = OrderStatus.PENDING_PAYMENT;
        order.paymentDueAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        OrderItem item = OrderItem.of(productId, productName, quantity, unitPrice);
        item.assignOrder(order);
        order.items.add(item);
        return order;
    }

    public void pay() {
        requirePendingPayment("ORDER_NOT_PAYABLE");
        status = OrderStatus.PAID;
    }

    public void cancel() {
        requirePendingPayment("ORDER_NOT_CANCELLABLE");
        status = OrderStatus.CANCELLED;
    }

    public void markExpired() {
        requirePendingPayment("ORDER_NOT_EXPIRABLE");
        status = OrderStatus.EXPIRED;
    }

    public int totalQuantity() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    private void requirePendingPayment(String code) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new com.flashsale.common.exception.ConflictException(code,
                "Order " + id + " is not awaiting payment (status=" + status + ")");
        }
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getUserId() { return userId; }
    public Long getFlashSaleId() { return flashSaleId; }
    public UUID getPurchaseRequestId() { return purchaseRequestId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public OrderStatus getStatus() { return status; }
    public Instant getPaymentDueAt() { return paymentDueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderItem> getItems() { return items; }
}
