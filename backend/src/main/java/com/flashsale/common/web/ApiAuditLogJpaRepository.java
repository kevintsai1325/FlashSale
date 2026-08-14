package com.flashsale.common.web;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ApiAuditLogJpaRepository extends JpaRepository<ApiAuditLog, Long>, JpaSpecificationExecutor<ApiAuditLog> {

    @Modifying
    @Query("delete from ApiAuditLog a where a.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);

    // Admin order-detail correlation (com.flashsale.admin): audit rows for the order's user
    // falling within a time window around a status-history timestamp. See
    // AdminOrderQueryService for why this is time-window based rather than a stored-key join.
    List<ApiAuditLog> findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(Long userId, Instant start, Instant end);
}
