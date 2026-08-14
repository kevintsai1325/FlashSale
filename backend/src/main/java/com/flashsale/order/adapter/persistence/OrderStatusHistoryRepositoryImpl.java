package com.flashsale.order.adapter.persistence;

import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.OrderStatusHistory;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OrderStatusHistoryRepositoryImpl implements OrderStatusHistoryRepository {

    private final OrderStatusHistoryJpaRepository jpaRepository;

    public OrderStatusHistoryRepositoryImpl(OrderStatusHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void record(Long orderId, OrderStatus from, OrderStatus to) {
        jpaRepository.save(OrderStatusHistory.of(orderId, from, to));
    }

    @Override
    public List<OrderStatusHistory> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderIdOrderByChangedAtAsc(orderId);
    }
}
