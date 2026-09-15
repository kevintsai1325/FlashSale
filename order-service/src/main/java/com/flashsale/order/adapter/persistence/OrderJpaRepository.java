package com.flashsale.order.adapter.persistence;

import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    @Override
    @EntityGraph(attributePaths = "items")
    Optional<Order> findById(Long id);

    @EntityGraph(attributePaths = "items")
    List<Order> findAllByUserId(Long userId);

    // Admin order list filter (com.flashsale.admin).
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    // join fetch items to avoid LazyInitializationException: the caller (PaymentTimeoutScheduler)
    // reads order.totalQuantity() in a different transaction than this query runs in.
    @Query("select distinct o from Order o join fetch o.items where o.status = :status and o.paymentDueAt < :instant")
    List<Order> findByStatusAndPaymentDueAtBefore(@Param("status") OrderStatus status, @Param("instant") Instant instant);

    // Admin dashboard aggregates (com.flashsale.admin).
    long countByStatus(OrderStatus status);

    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status = :status")
    BigDecimal sumTotalAmountByStatus(@Param("status") OrderStatus status);

    List<Order> findByCreatedAtAfter(Instant since);

    // join fetch items to avoid LazyInitializationException outside the repository call's own
    // transaction (open-in-view is disabled — see application.yml).
    @Query("select distinct o from Order o join fetch o.items where o.id = :id")
    Optional<Order> findWithItemsById(@Param("id") Long id);
}
