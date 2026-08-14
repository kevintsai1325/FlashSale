package com.flashsale.common.web;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ApiAuditLogJpaRepository extends JpaRepository<ApiAuditLog, Long> {

    @Modifying
    @Query("delete from ApiAuditLog a where a.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
