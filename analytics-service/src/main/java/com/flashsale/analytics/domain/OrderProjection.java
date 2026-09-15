package com.flashsale.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 訂單的當前樣貌，由 OrderCreated 建立、OrderStatusChanged 更新。
 *
 * 主鍵是來源系統的 orderId 而不是自增 id：這讓寫入變成 upsert，重複投遞同一個事件
 * 不會多出一列。發佈端是「至少一次」，這個選擇讓消費端不需要額外的去重表。
 */
@Entity
@Table(name = "order_projection")
public class OrderProjection {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "flash_sale_id")
    private Long flashSaleId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderProjection() {}

    public static OrderProjection created(Long orderId, String orderNo, Long userId, Long flashSaleId, Long productId,
                                           String productName, int quantity, BigDecimal totalAmount, Instant createdAt) {
        OrderProjection projection = new OrderProjection();
        projection.orderId = orderId;
        projection.orderNo = orderNo;
        projection.userId = userId;
        projection.flashSaleId = flashSaleId;
        projection.productId = productId;
        projection.productName = productName;
        projection.quantity = quantity;
        projection.totalAmount = totalAmount;
        projection.status = "PENDING_PAYMENT";
        projection.createdAt = createdAt;
        projection.updatedAt = createdAt;
        return projection;
    }

    /**
     * 狀態轉移。**不檢查 from 是否吻合**：同一個 flashSaleId 的事件都在同一個 Kafka 分區，
     * 所以順序是保證的，比對 from 只會在重放時把正確的轉移擋下來。
     */
    public void applyStatus(String toStatus, Instant changedAt) {
        this.status = toStatus;
        this.updatedAt = changedAt;
    }

    public Long getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
