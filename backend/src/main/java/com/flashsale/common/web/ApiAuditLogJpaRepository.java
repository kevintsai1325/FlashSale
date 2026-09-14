package com.flashsale.common.web;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ApiAuditLogJpaRepository extends JpaRepository<ApiAuditLog, Long>, JpaSpecificationExecutor<ApiAuditLog> {

    // @Transactional 放在這裡而不是呼叫端：@Modifying 的 delete 需要一個交易，而呼叫端
    // （ApiAuditRetentionScheduler）現在把工作包進分散式鎖的 Runnable 裡。Runnable 是以
    // this:: 方法參考呼叫的，會繞過 Spring 的代理，寫在呼叫端方法上的 @Transactional 不會生效。
    // 讓這個方法自己帶交易，呼叫端怎麼包都安全。
    @Modifying
    @Transactional
    @Query("delete from ApiAuditLog a where a.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);

    // Admin order-detail correlation (com.flashsale.admin): audit rows for the order's user
    // falling within a time window around a status-history timestamp. See
    // AdminOrderQueryService for why this is time-window based rather than a stored-key join.
    List<ApiAuditLog> findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(Long userId, Instant start, Instant end);
}
