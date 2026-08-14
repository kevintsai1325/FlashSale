package com.flashsale.order.application;

import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestRepository {
    PurchaseRequest save(PurchaseRequest request);
    Optional<PurchaseRequest> findById(Long id);
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsSucceededForUserAndFlashSale(Long userId, Long flashSaleId);
    Optional<PurchaseRequest> findByOrderId(Long orderId);

    // Admin dashboard aggregates (com.flashsale.admin).
    long countAll();
    long countByStatus(PurchaseRequestStatus status);
    List<PurchaseRequest> findAllCreatedAfter(Instant since);
}
