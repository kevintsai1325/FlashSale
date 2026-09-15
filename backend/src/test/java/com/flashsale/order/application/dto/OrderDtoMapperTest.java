package com.flashsale.order.application.dto;

import com.flashsale.order.domain.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDtoMapperTest {

    @Test
    void mapsProductSnapshotsIntoOrderSummary() {
        Order order = orderWithProductSnapshot();

        OrderSummary result = OrderDtoMapper.toSummary(order);

        assertThat(result.items()).containsExactly(
            new OrderItemView(2L, "限量鍵盤", 3, new BigDecimal("499.00")));
    }

    @Test
    void mapsProductSnapshotsIntoOrderDetail() {
        Order order = orderWithProductSnapshot();

        OrderDetail result = OrderDtoMapper.toDetail(order);

        assertThat(result.items()).containsExactly(
            new OrderItemView(2L, "限量鍵盤", 3, new BigDecimal("499.00")));
    }

    private Order orderWithProductSnapshot() {
        return Order.createPendingPayment(42L, 7L, 700L, 2L, "限量鍵盤", 3, new BigDecimal("499.00"));
    }
}
