package com.flashsale.order.adapter.persistence;

import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByUserId(Long userId);

    // join fetch items to avoid LazyInitializationException: the caller (PaymentTimeoutScheduler)
    // reads order.totalQuantity() in a different transaction than this query runs in.
    @Query("select distinct o from Order o join fetch o.items where o.status = :status and o.paymentDueAt < :instant")
    List<Order> findByStatusAndPaymentDueAtBefore(@Param("status") OrderStatus status, @Param("instant") Instant instant);
}
