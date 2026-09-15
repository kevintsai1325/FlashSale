package com.flashsale.order.application;

import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCompensationService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final OutboxWriter outboxWriter;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public OrderCompensationService(OrderRepository orderRepository,
                                     InventoryRepository inventoryRepository, OutboxWriter outboxWriter,
                                     OrderEventPublisher orderEventPublisher,
                                     OrderStatusHistoryRepository orderStatusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.outboxWriter = outboxWriter;
        this.orderEventPublisher = orderEventPublisher;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @Transactional
    public void cancel(Order order) {
        order.cancel();
        compensate(order);
    }

    @Transactional
    public void markExpired(Order order) {
        order.markExpired();
        compensate(order);
    }

    @Transactional
    public void failPayment(Order order) {
        // Payment failure reuses the same CANCELLED terminal state as a user-initiated cancel —
        // PaymentRecord (Task 10) is what distinguishes "cancelled" from "payment failed" for
        // reporting purposes; the Order state machine itself only needs one non-PAID outcome.
        order.cancel();
        compensate(order);
    }

    private void compensate(Order order) {
        orderRepository.save(order);
        orderStatusHistoryRepository.record(order.getId(), OrderStatus.PENDING_PAYMENT, order.getStatus());
        orderEventPublisher.statusChanged(order, OrderStatus.PENDING_PAYMENT);
        // 拆庫前這裡是從 orderId 反查 purchase_requests 拿 flashSaleId。那張表已經是
        // purchase-service 的資料，所以改讀訂單自己記下的值（V4 migration 加的欄位）。
        Long flashSaleId = order.getFlashSaleId();
        if (flashSaleId == null) {
            throw new IllegalStateException("Order " + order.getId() + " carries no flashSaleId; cannot release stock");
        }
        int quantity = order.totalQuantity();

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(flashSaleId)
            .orElseThrow(() -> new IllegalStateException("Inventory for flash sale " + flashSaleId + " not found"));
        inventory.release(quantity);
        inventoryRepository.save(inventory);

        outboxWriter.write("Order", order.getId().toString(), EventTypes.STOCK_RELEASE_REQUESTED,
            new StockReleaseRequestedEvent(flashSaleId, quantity));
    }
}
