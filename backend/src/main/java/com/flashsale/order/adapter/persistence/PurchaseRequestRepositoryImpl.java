package com.flashsale.order.adapter.persistence;

import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PurchaseRequestRepositoryImpl implements PurchaseRequestRepository {

    private final PurchaseRequestJpaRepository jpaRepository;

    public PurchaseRequestRepositoryImpl(PurchaseRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<PurchaseRequest> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public long countAll() {
        return jpaRepository.count();
    }

    @Override
    public long countByStatus(PurchaseRequestStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public List<PurchaseRequest> findAllCreatedAfter(Instant since) {
        return jpaRepository.findByCreatedAtAfter(since);
    }
}
