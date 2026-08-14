package com.flashsale.order.domain;

import com.flashsale.common.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class OrderTest {

    private Order pendingOrder() {
        return Order.createPendingPayment(1L, 10L, 2, new BigDecimal("9.99"));
    }

    @Test
    void payTransitionsPendingPaymentToPaid() {
        Order order = pendingOrder();
        order.pay();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void cancelTransitionsPendingPaymentToCancelled() {
        Order order = pendingOrder();
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void markExpiredTransitionsPendingPaymentToExpired() {
        Order order = pendingOrder();
        order.markExpired();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void cannotCancelAnAlreadyPaidOrder() {
        Order order = pendingOrder();
        order.pay();
        assertThatThrownBy(order::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void cannotPayAnAlreadyCancelledOrder() {
        Order order = pendingOrder();
        order.cancel();
        assertThatThrownBy(order::pay).isInstanceOf(ConflictException.class);
    }

    @Test
    void totalQuantitySumsAllItems() {
        Order order = Order.createPendingPayment(1L, 10L, 3, new BigDecimal("9.99"));
        assertThat(order.totalQuantity()).isEqualTo(3);
    }
}
