package com.flashsale.purchase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * purchase_requests 在 P4 步驟 2 之後完全屬於這個服務：自己的資料庫、自己的 migration、
 * 唯一的讀寫者。backend 原本的三處讀取分別換成了「訂單自己存下來的欄位」與
 * 「一支內部統計 API」。
 *
 * user_id / flash_sale_id / order_id 都只是數字，沒有外鍵 —— 它們指向別的服務的資料。
 */
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

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
    public Instant getCreatedAt() { return createdAt; }
}
