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
import java.util.UUID;

/**
 * **唯讀投影。** purchase_requests 這張表的寫入權責在 purchase-service，backend 只讀它
 * （admin 儀表板、訂單查詢、補償時取得 flashSaleId）。
 *
 * 所以這個類別刻意沒有任何 mutator 與工廠方法 —— 不是忘了搬，是不能有：
 * 留著它們就等於留著「backend 也能寫這張表」這個可能性，而那正是步驟 2 拆庫時
 * 最難拆的東西。共用資料庫階段能做的最低限度，是讓寫入者只有一個。
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

    public Long getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Long getFlashSaleId() { return flashSaleId; }
    public Long getOrderId() { return orderId; }
    public PurchaseRequestStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
