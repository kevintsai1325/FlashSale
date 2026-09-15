package com.flashsale.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 搶購請求的當前樣貌，由 PurchaseRequestCreated 建立、PurchaseRequestResolved 更新。
 * 主鍵是 purchase-service 對外公開的 requestId，理由同 {@link OrderProjection}。
 */
@Entity
@Table(name = "purchase_request_projection")
public class PurchaseRequestProjection {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "flash_sale_id", nullable = false)
    private Long flashSaleId;

    @Column(nullable = false)
    private String status;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PurchaseRequestProjection() {}

    public static PurchaseRequestProjection created(UUID requestId, Long userId, Long flashSaleId,
                                                     String status, Instant createdAt) {
        PurchaseRequestProjection projection = new PurchaseRequestProjection();
        projection.requestId = requestId;
        projection.userId = userId;
        projection.flashSaleId = flashSaleId;
        projection.status = status;
        projection.createdAt = createdAt;
        projection.updatedAt = createdAt;
        return projection;
    }

    public void resolve(String status, Long orderId, Instant resolvedAt) {
        this.status = status;
        this.orderId = orderId;
        this.updatedAt = resolvedAt;
    }

    public UUID getRequestId() { return requestId; }
    public String getStatus() { return status; }
}
