package com.flashsale.purchase.adapter.persistence;

import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestJpaRepository extends JpaRepository<PurchaseRequest, Long> {
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsByUserIdAndFlashSaleIdAndStatus(Long userId, Long flashSaleId, PurchaseRequestStatus status);
}
