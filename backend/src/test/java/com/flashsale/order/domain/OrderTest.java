package com.flashsale.order.domain;

import com.flashsale.common.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class OrderTest {

    private Order pendingOrder() {
        return Order.createPendingPayment(1L, 10L, "Test Product", 2, new BigDecimal("9.99"));
    }

    @Test
    void createsOrderItemWithProductNameSnapshot() {
        Order order = Order.createPendingPayment(1L, 2L, "限量鍵盤", 3, new BigDecimal("499.00"));

        OrderItem item = order.getItems().getFirst();

        assertThat(item.getProductName()).isEqualTo("限量鍵盤");
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("499.00");
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
        Order order = Order.createPendingPayment(1L, 10L, "Test Product", 3, new BigDecimal("9.99"));
        assertThat(order.totalQuantity()).isEqualTo(3);
    }
}
