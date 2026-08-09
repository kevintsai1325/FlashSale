package com.flashsale.order.adapter.persistence;

import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestJpaRepository extends JpaRepository<PurchaseRequest, Long> {
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsByUserIdAndFlashSaleIdAndStatus(Long userId, Long flashSaleId, com.flashsale.order.domain.PurchaseRequestStatus status);
}
