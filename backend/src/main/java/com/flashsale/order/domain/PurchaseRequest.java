package com.flashsale.order.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "purchase_requests")
public class PurchaseRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "flash_sale_id", nullable = false)
    private Long flashSaleId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseRequestStatus status;

    protected PurchaseRequest() {}

    private static PurchaseRequest create(Long userId, Long flashSaleId, String idempotencyKey,
                                           PurchaseRequestStatus status, Long orderId) {
        PurchaseRequest request = new PurchaseRequest();
        request.requestId = UUID.randomUUID();
        request.userId = userId;
        request.flashSaleId = flashSaleId;
        request.idempotencyKey = idempotencyKey;
        request.status = status;
        request.orderId = orderId;
        return request;
    }

    public static PurchaseRequest pending(Long userId, Long flashSaleId, String idempotencyKey) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.PENDING, null);
    }

    public static PurchaseRequest soldOut(Long userId, Long flashSaleId, String idempotencyKey) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.SOLD_OUT, null);
    }

    public static PurchaseRequest reject(Long userId, Long flashSaleId, String idempotencyKey) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.REJECTED, null);
    }

    public void markSucceeded(Long orderId) {
        this.status = PurchaseRequestStatus.SUCCEEDED;
        this.orderId = orderId;
    }

    public void markFailed() {
        this.status = PurchaseRequestStatus.FAILED;
    }

    public Long getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Long getFlashSaleId() { return flashSaleId; }
    public Long getOrderId() { return orderId; }
    public PurchaseRequestStatus getStatus() { return status; }
}
