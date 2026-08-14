package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class OrderPurchaseConsumer {

    private static final String CONSUMER_NAME = "order-purchase-consumer";

    private final ConsumedMessageGuard consumedMessageGuard;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    public OrderPurchaseConsumer(ConsumedMessageGuard consumedMessageGuard, PurchaseRequestRepository purchaseRequestRepository,
                                  InventoryRepository inventoryRepository, OrderRepository orderRepository,
                                  OrderStatusHistoryRepository orderStatusHistoryRepository, ObjectMapper objectMapper) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.CREATE_ORDER_QUEUE)
    @Transactional
    public void handle(Message message) throws IOException {
        Long outboxEventId = (Long) message.getMessageProperties().getHeaders().get("outboxEventId");
        if (!consumedMessageGuard.tryConsume(String.valueOf(outboxEventId), CONSUMER_NAME)) {
            return;
        }

        CreateOrderRequestedEvent event = objectMapper.readValue(message.getBody(), CreateOrderRequestedEvent.class);

        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(event.purchaseRequestId())
            .orElseThrow(() -> new IllegalStateException("PurchaseRequest " + event.purchaseRequestId() + " not found"));

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(event.flashSaleId())
            .orElseThrow(() -> new IllegalStateException("Inventory for flash sale " + event.flashSaleId() + " not found"));
        if (!inventory.hasStock(event.quantity())) {
            throw new IllegalStateException(
                "Redis/Postgres stock drift: flash sale " + event.flashSaleId() + " has insufficient Postgres stock despite a Redis reservation");
        }
        inventory.sell(event.quantity());
        inventoryRepository.save(inventory);

        Order order = Order.createPendingPayment(event.userId(), event.productId(), event.quantity(), event.unitPrice());
        Order savedOrder = orderRepository.save(order);
        orderStatusHistoryRepository.record(savedOrder.getId(), null, OrderStatus.PENDING_PAYMENT);

        purchaseRequest.markSucceeded(savedOrder.getId());
        purchaseRequestRepository.save(purchaseRequest);
    }
}
