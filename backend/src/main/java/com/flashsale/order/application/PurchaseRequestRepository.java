package com.flashsale.order.application;

import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 唯讀。寫入權責在 purchase-service（見 PurchaseRequest 的註解）。
 */
public interface PurchaseRequestRepository {
    Optional<PurchaseRequest> findByOrderId(Long orderId);

    // Admin dashboard aggregates (com.flashsale.admin).
    long countAll();
    long countByStatus(PurchaseRequestStatus status);
    List<PurchaseRequest> findAllCreatedAfter(Instant since);
}
