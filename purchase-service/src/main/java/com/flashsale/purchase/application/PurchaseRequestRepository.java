package com.flashsale.purchase.application;

import com.flashsale.purchase.domain.PurchaseRequest;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestRepository {
    PurchaseRequest save(PurchaseRequest request);
    Optional<PurchaseRequest> findById(Long id);
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsSucceededForUserAndFlashSale(Long userId, Long flashSaleId);
}
