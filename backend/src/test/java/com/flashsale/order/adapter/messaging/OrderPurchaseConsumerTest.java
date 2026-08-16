package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.common.metrics.PurchaseMetrics;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.PurchaseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPurchaseConsumerTest {

    @Mock ConsumedMessageGuard consumedMessageGuard;
    @Mock PurchaseRequestRepository purchaseRequestRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock PurchaseMetrics purchaseMetrics;
    @Mock ProductRepository productRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderPurchaseConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderPurchaseConsumer(consumedMessageGuard, purchaseRequestRepository,
            inventoryRepository, orderRepository, orderStatusHistoryRepository, objectMapper,
            purchaseMetrics, productRepository);
    }

    @Test
    void savesCatalogNameAsOrderItemSnapshotWhenProcessingPurchaseEvent() throws Exception {
        CreateOrderRequestedEvent event = new CreateOrderRequestedEvent(
            101L, 202L, 303L, 404L, 1, new BigDecimal("1999.00"));
        PurchaseRequest purchaseRequest = PurchaseRequest.pending(202L, 303L, "snapshot-key");
        Product catalogProduct = Product.create("限量鍵盤", "消費時的商品名稱");
        AtomicReference<Order> savedOrder = new AtomicReference<>();

        when(consumedMessageGuard.tryConsume("505", "order-purchase-consumer")).thenReturn(true);
        when(purchaseRequestRepository.findById(101L)).thenReturn(Optional.of(purchaseRequest));
        when(inventoryRepository.findByFlashSaleIdForUpdate(303L))
            .thenReturn(Optional.of(Inventory.initialize(303L, 1)));
        when(productRepository.findById(404L)).thenReturn(Optional.of(catalogProduct));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            savedOrder.set(order);
            return order;
        });

        consumer.handle(messageFor(event, 505L));

        assertThat(savedOrder.get().getItems())
            .singleElement()
            .extracting(item -> item.getProductName())
            .isEqualTo("限量鍵盤");
    }

    private Message messageFor(CreateOrderRequestedEvent event, Long outboxEventId) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("outboxEventId", outboxEventId);
        return new Message(objectMapper.writeValueAsBytes(event), properties);
    }
}
