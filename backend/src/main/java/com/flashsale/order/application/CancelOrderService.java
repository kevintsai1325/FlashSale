package com.flashsale.order.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.domain.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelOrderService {

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;

    public CancelOrderService(OrderRepository orderRepository, OrderCompensationService compensationService) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
    }

    @Transactional
    public Order cancel(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
            .filter(o -> o.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " does not exist"));
        compensationService.cancel(order);
        return order;
    }
}
