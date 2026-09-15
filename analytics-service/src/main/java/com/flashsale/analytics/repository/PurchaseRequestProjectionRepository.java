package com.flashsale.analytics.repository;

import com.flashsale.analytics.domain.PurchaseRequestProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PurchaseRequestProjectionRepository extends JpaRepository<PurchaseRequestProjection, UUID> {

    long countByStatus(String status);

    @Query("select p.createdAt from PurchaseRequestProjection p where p.createdAt >= :since")
    List<Instant> findCreatedAtSince(@Param("since") Instant since);
}
