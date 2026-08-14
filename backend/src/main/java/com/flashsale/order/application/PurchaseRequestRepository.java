package com.flashsale.order.application;

import com.flashsale.order.domain.PurchaseRequest;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestRepository {
    PurchaseRequest save(PurchaseRequest request);
    Optional<PurchaseRequest> findById(Long id);
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsSucceededForUserAndFlashSale(Long userId, Long flashSaleId);
    Optional<PurchaseRequest> findByOrderId(Long orderId);
}
