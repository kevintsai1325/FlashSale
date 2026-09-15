package com.flashsale.analytics.repository;

import com.flashsale.analytics.domain.OrderProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface OrderProjectionRepository extends JpaRepository<OrderProjection, Long> {

    @Query("select o.status, count(o) from OrderProjection o group by o.status")
    List<Object[]> countGroupedByStatus();

    @Query("select coalesce(sum(o.totalAmount), 0) from OrderProjection o where o.status = :status")
    BigDecimal sumTotalAmountByStatus(@Param("status") String status);

    // 只取 createdAt：分桶只需要時間，撈整個 entity 會把 24 小時內的每一筆訂單實體化進記憶體。
    @Query("select o.createdAt from OrderProjection o where o.createdAt >= :since")
    List<Instant> findCreatedAtSince(@Param("since") Instant since);
}
