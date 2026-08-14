package com.flashsale.order.adapter.persistence;

import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public OrderRepositoryImpl(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Order save(Order order) { return jpaRepository.save(order); }

    @Override
    public Optional<Order> findById(Long id) { return jpaRepository.findById(id); }

    @Override
    public List<Order> findAllByUserId(Long userId) { return jpaRepository.findAllByUserId(userId); }

    @Override
    public List<Order> findPendingPaymentPastDue(Instant now) {
        return jpaRepository.findByStatusAndPaymentDueAtBefore(OrderStatus.PENDING_PAYMENT, now);
    }
}
