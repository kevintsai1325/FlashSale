package com.flashsale.order.application;

import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCompensationService {

    private final OrderRepository orderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final InventoryRepository inventoryRepository;
    private final OutboxWriter outboxWriter;

    public OrderCompensationService(OrderRepository orderRepository, PurchaseRequestRepository purchaseRequestRepository,
                                     InventoryRepository inventoryRepository, OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.inventoryRepository = inventoryRepository;
        this.outboxWriter = outboxWriter;
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
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findByOrderId(order.getId())
            .orElseThrow(() -> new IllegalStateException("No purchase request linked to order " + order.getId()));
        int quantity = order.totalQuantity();

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(purchaseRequest.getFlashSaleId())
            .orElseThrow(() -> new IllegalStateException("Inventory for flash sale " + purchaseRequest.getFlashSaleId() + " not found"));
        inventory.release(quantity);
        inventoryRepository.save(inventory);

        outboxWriter.write("Order", order.getId().toString(), EventTypes.STOCK_RELEASE_REQUESTED,
            new StockReleaseRequestedEvent(purchaseRequest.getFlashSaleId(), quantity));
    }
}
