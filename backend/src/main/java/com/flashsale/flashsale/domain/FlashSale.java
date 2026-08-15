package com.flashsale.flashsale.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "flash_sales")
public class FlashSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sale_price", nullable = false)
    private BigDecimal salePrice;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "purchase_limit_per_user", nullable = false)
    private int purchaseLimitPerUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlashSaleStatus status;

    protected FlashSale() {}

    public static FlashSale schedule(Long productId, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                                      int purchaseLimitPerUser) {
        FlashSale sale = new FlashSale();
        sale.productId = productId;
        sale.salePrice = salePrice;
        sale.startsAt = startsAt;
        sale.endsAt = endsAt;
        sale.purchaseLimitPerUser = purchaseLimitPerUser;
        sale.status = FlashSaleStatus.SCHEDULED;
        return sale;
    }

    public boolean isPurchasableAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /**
     * The persisted {@link #status} is written once by {@link #schedule} and never transitions
     * afterwards — nothing in this codebase moves a sale from SCHEDULED to ACTIVE to ENDED as time
     * passes. Callers that need to know whether a sale is currently active, upcoming, or over must
     * derive it from {@code startsAt}/{@code endsAt} instead of trusting {@link #getStatus()},
     * which reads as whatever it was set to at creation regardless of how much time has elapsed.
     */
    public FlashSaleStatus effectiveStatus(Instant now) {
        if (now.isBefore(startsAt)) {
            return FlashSaleStatus.SCHEDULED;
        }
        return now.isBefore(endsAt) ? FlashSaleStatus.ACTIVE : FlashSaleStatus.ENDED;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public BigDecimal getSalePrice() { return salePrice; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public int getPurchaseLimitPerUser() { return purchaseLimitPerUser; }
    public FlashSaleStatus getStatus() { return status; }
}
