package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.common.metrics.PurchaseMetrics;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.application.event.PurchaseResolvedEvent;
import com.flashsale.order.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPurchaseConsumerTest {

    @Mock ConsumedMessageGuard consumedMessageGuard;
    @Mock InventoryRepository inventoryRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock PurchaseMetrics purchaseMetrics;
    @Mock ProductRepository productRepository;
    @Mock OutboxWriter outboxWriter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderPurchaseConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderPurchaseConsumer(consumedMessageGuard, inventoryRepository, orderRepository,
            orderStatusHistoryRepository, objectMapper, purchaseMetrics, productRepository, outboxWriter);
    }

    private void stubHappyPath(AtomicReference<Order> savedOrder) {
        when(consumedMessageGuard.tryConsume("505", "order-purchase-consumer")).thenReturn(true);
        when(inventoryRepository.findByFlashSaleIdForUpdate(303L))
            .thenReturn(Optional.of(Inventory.initialize(303L, 1)));
        when(productRepository.findById(404L)).thenReturn(Optional.of(Product.create("限量鍵盤", "消費時的商品名稱")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 9001L);
            savedOrder.set(order);
            return order;
        });
    }

    @Test
    void savesCatalogNameAsOrderItemSnapshotWhenProcessingPurchaseEvent() throws Exception {
        AtomicReference<Order> savedOrder = new AtomicReference<>();
        stubHappyPath(savedOrder);

        consumer.handle(messageFor(new CreateOrderRequestedEvent(101L, 202L, 303L, 404L, 1, new BigDecimal("1999.00")), 505L));

        assertThat(savedOrder.get().getItems())
            .singleElement()
            .extracting(item -> item.getProductName())
            .isEqualTo("限量鍵盤");
    }

    /**
     * 拆分後新增的行為：backend 不再直接改 purchase_requests，而是把終態寫進 outbox
     * 送回 purchase-service。這是搶購結果能不能回到使用者眼前的唯一路徑，必須有測試守著。
     */
    @Test
    void writesTheTerminalStateBackToPurchaseServiceInTheSameTransaction() throws Exception {
        AtomicReference<Order> savedOrder = new AtomicReference<>();
        stubHappyPath(savedOrder);

        consumer.handle(messageFor(new CreateOrderRequestedEvent(101L, 202L, 303L, 404L, 1, new BigDecimal("1999.00")), 505L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).write(eq("PurchaseRequest"), eq("101"), eq("PurchaseResolved"), payload.capture());
        assertThat(payload.getValue())
            .isEqualTo(new PurchaseResolvedEvent(101L, "SUCCEEDED", 9001L));
    }

    private Message messageFor(CreateOrderRequestedEvent event, Long outboxEventId) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("outboxEventId", outboxEventId);
        return new Message(objectMapper.writeValueAsBytes(event), properties);
    }
}
