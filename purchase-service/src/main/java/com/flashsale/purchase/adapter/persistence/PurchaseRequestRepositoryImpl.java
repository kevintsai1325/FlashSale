package com.flashsale.purchase.adapter.persistence;

import com.flashsale.purchase.application.PurchaseRequestRepository;
import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PurchaseRequestRepositoryImpl implements PurchaseRequestRepository {

    private final PurchaseRequestJpaRepository jpaRepository;

    public PurchaseRequestRepositoryImpl(PurchaseRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PurchaseRequest save(PurchaseRequest request) {
        return jpaRepository.save(request);
    }

    @Override
    public Optional<PurchaseRequest> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<PurchaseRequest> findByRequestId(UUID requestId) {
        return jpaRepository.findByRequestId(requestId);
    }

    @Override
    public Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey) {
        return jpaRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(userId, flashSaleId, idempotencyKey);
    }

    @Override
    public boolean existsSucceededForUserAndFlashSale(Long userId, Long flashSaleId) {
        return jpaRepository.existsByUserIdAndFlashSaleIdAndStatus(userId, flashSaleId, PurchaseRequestStatus.SUCCEEDED);
    }
}
