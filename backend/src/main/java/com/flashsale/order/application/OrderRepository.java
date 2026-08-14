package com.flashsale.order.application;

import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(Long id);
    List<Order> findAllByUserId(Long userId);
    List<Order> findPendingPaymentPastDue(Instant now);

    // Admin dashboard aggregates (com.flashsale.admin).
    long countByStatus(OrderStatus status);
    BigDecimal sumTotalAmountByStatus(OrderStatus status);
    List<Order> findAllCreatedAfter(Instant since);

    // Admin order list/detail (com.flashsale.admin).
    Page<Order> findAllPaged(Pageable pageable);
    Optional<Order> findByIdWithItems(Long id);
}
