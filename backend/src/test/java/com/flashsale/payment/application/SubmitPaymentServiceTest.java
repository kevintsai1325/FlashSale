package com.flashsale.payment.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.payment.domain.PaymentRecord;
import com.flashsale.payment.domain.PaymentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmitPaymentServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderCompensationService compensationService;
    @Mock PaymentRecordRepository paymentRecordRepository;
    @Mock OrderStatusHistoryRepository orderStatusHistoryRepository;

    SubmitPaymentService service;

    private Order pendingOrderOwnedBy(Long userId) {
        return Order.createPendingPayment(userId, 10L, "Test Product", 1, new BigDecimal("9.99"));
    }

    @Test
    void successfulPaymentMarksOrderPaid() {
        service = new SubmitPaymentService(orderRepository, compensationService, paymentRecordRepository, orderStatusHistoryRepository);
        Order order = pendingOrderOwnedBy(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(paymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.submit(5L, 1L, PaymentResult.SUCCESS);

        assertThat(result.getStatus().name()).isEqualTo("PAID");
        verify(orderRepository).save(order);
        verify(compensationService, never()).failPayment(any());
    }

    @Test
    void failedPaymentTriggersCompensation() {
        service = new SubmitPaymentService(orderRepository, compensationService, paymentRecordRepository, orderStatusHistoryRepository);
        Order order = pendingOrderOwnedBy(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(paymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submit(5L, 1L, PaymentResult.FAILURE);

        verify(compensationService).failPayment(order);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void anotherUsersOrderIsNotFound() {
        service = new SubmitPaymentService(orderRepository, compensationService, paymentRecordRepository, orderStatusHistoryRepository);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(pendingOrderOwnedBy(1L)));

        assertThatThrownBy(() -> service.submit(5L, 2L, PaymentResult.SUCCESS))
            .isInstanceOf(NotFoundException.class);
    }
}
