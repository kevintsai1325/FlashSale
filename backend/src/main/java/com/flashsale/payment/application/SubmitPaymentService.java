package com.flashsale.payment.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.payment.domain.PaymentRecord;
import com.flashsale.payment.domain.PaymentResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitPaymentService {

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public SubmitPaymentService(OrderRepository orderRepository, OrderCompensationService compensationService,
                                 PaymentRecordRepository paymentRecordRepository,
                                 OrderStatusHistoryRepository orderStatusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @Transactional
    public Order submit(Long orderId, Long userId, PaymentResult result) {
        Order order = orderRepository.findById(orderId)
            .filter(o -> o.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " does not exist"));

        paymentRecordRepository.save(PaymentRecord.record(orderId, result));

        if (result == PaymentResult.SUCCESS) {
            order.pay();
            orderRepository.save(order);
            orderStatusHistoryRepository.record(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);
        } else {
            compensationService.failPayment(order);
        }
        return order;
    }
}
