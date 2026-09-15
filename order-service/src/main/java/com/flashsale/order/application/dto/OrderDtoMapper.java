package com.flashsale.order.application.dto;

import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderItem;

public final class OrderDtoMapper {

    private OrderDtoMapper() {}

    public static OrderSummary toSummary(Order order) {
        return new OrderSummary(
            order.getId(),
            order.getOrderNo(),
            order.getTotalAmount(),
            order.getStatus().name(),
            order.getItems().stream().map(OrderDtoMapper::toItemView).toList());
    }

    public static OrderDetail toDetail(Order order) {
        return new OrderDetail(
            order.getId(),
            order.getOrderNo(),
            order.getTotalAmount(),
            order.getStatus().name(),
            order.getPaymentDueAt(),
            order.getItems().stream().map(OrderDtoMapper::toItemView).toList());
    }

    private static OrderItemView toItemView(OrderItem item) {
        return new OrderItemView(
            item.getProductId(),
            item.getProductName(),
            item.getQuantity(),
            item.getUnitPrice());
    }
}
