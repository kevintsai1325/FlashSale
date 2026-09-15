package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.common.metrics.OrderMetrics;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderEventPublisher;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.application.event.PurchaseResolvedEvent;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class OrderPurchaseConsumer {

    private static final String CONSUMER_NAME = "order-purchase-consumer";

    private final ConsumedMessageGuard consumedMessageGuard;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxWriter outboxWriter;
    private final OrderEventPublisher orderEventPublisher;
    private final ObjectMapper objectMapper;
    private final OrderMetrics orderMetrics;

    public OrderPurchaseConsumer(ConsumedMessageGuard consumedMessageGuard, InventoryRepository inventoryRepository,
                                  OrderRepository orderRepository, OrderStatusHistoryRepository orderStatusHistoryRepository,
                                  ObjectMapper objectMapper, OrderMetrics orderMetrics,
                                  OutboxWriter outboxWriter,
                                  OrderEventPublisher orderEventPublisher) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.objectMapper = objectMapper;
        this.orderMetrics = orderMetrics;
        this.outboxWriter = outboxWriter;
        this.orderEventPublisher = orderEventPublisher;
    }

    @RabbitListener(queues = RabbitConfig.CREATE_ORDER_QUEUE)
    @Transactional
    public void handle(Message message) throws IOException {
        String eventId = (String) message.getMessageProperties().getHeaders().get("eventId");
        if (!consumedMessageGuard.tryConsume(eventId, CONSUMER_NAME)) {
            return;
        }

        CreateOrderRequestedEvent event = objectMapper.readValue(message.getBody(), CreateOrderRequestedEvent.class);

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(event.flashSaleId())
            .orElseThrow(() -> new IllegalStateException("Inventory for flash sale " + event.flashSaleId() + " not found"));
        if (!inventory.hasStock(event.quantity())) {
            throw new IllegalStateException(
                "Redis/Postgres stock drift: flash sale " + event.flashSaleId() + " has insufficient Postgres stock despite a Redis reservation");
        }
        inventory.sell(event.quantity());
        inventoryRepository.save(inventory);

        // 商品名稱由事件帶過來，不再查 catalog —— 那張表已經是 platform 的資料（P5）。
        // 事件裡的名稱是「下單當下」的名稱，本來就比事後查一次更正確：
        // 訂單明細要的是購買當時的快照，不是商品現在叫什麼。
        Order order = Order.createPendingPayment(
            event.userId(), event.flashSaleId(), event.purchaseRequestId(),
            event.productId(), event.productName(), event.quantity(), event.unitPrice());
        Order savedOrder = orderRepository.save(order);
        orderStatusHistoryRepository.record(savedOrder.getId(), null, OrderStatus.PENDING_PAYMENT);

        // 終態回寫給 purchase-service。與建單在同一個交易裡寫進 outbox，
        // 所以「訂單建立了但搶購請求還停在 PENDING」不會是一個持久的狀態。
        outboxWriter.write("PurchaseRequest", event.purchaseRequestId().toString(), EventTypes.PURCHASE_RESOLVED,
            new PurchaseResolvedEvent(event.purchaseRequestId(), "SUCCEEDED", savedOrder.getId()));

        // 領域事件（Kafka）。與上面那個終態回呼（RabbitMQ）是兩件不同的事：
        // 那個是說給 purchase-service 聽的命令式回覆，這個是說給所有人聽的「發生了什麼」。
        orderEventPublisher.orderCreated(savedOrder, event.productName(), event.quantity(), event.unitPrice());

        orderMetrics.orderCreated();
    }
}
