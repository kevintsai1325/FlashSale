package com.flashsale.order.adapter.persistence;

import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PurchaseRequestJpaRepository extends JpaRepository<PurchaseRequest, Long> {
    Optional<PurchaseRequest> findByOrderId(Long orderId);

    // Admin dashboard aggregates (com.flashsale.admin).
    long countByStatus(PurchaseRequestStatus status);
    List<PurchaseRequest> findByCreatedAtAfter(Instant since);
}
