package com.flashsale.order.application;

import com.flashsale.order.domain.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class PaymentTimeoutScheduler {

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;

    public PaymentTimeoutScheduler(OrderRepository orderRepository, OrderCompensationService compensationService) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
    }

    @Scheduled(fixedDelay = 30000)
    public void expireOverduePayments() {
        List<Order> overdue = orderRepository.findPendingPaymentPastDue(Instant.now());
        for (Order order : overdue) {
            compensationService.markExpired(order);
        }
    }
}
