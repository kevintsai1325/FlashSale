package com.flashsale.order.application;

import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.OrderStatusHistory;

import java.util.List;

public interface OrderStatusHistoryRepository {

    /**
     * Records a single status transition. {@code from} is nullable — the initial transition
     * out of order creation has no prior status. {@code to} is always required.
     */
    void record(Long orderId, OrderStatus from, OrderStatus to);

    List<OrderStatusHistory> findByOrderId(Long orderId);
}
