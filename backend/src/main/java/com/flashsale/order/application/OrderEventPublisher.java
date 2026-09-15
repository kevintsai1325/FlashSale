package com.flashsale.order.application;

import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.order.application.event.OrderCreatedEvent;
import com.flashsale.order.application.event.OrderStatusChangedEvent;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 訂單的領域事件都從這裡發，而不是散在各個呼叫點自己組 outbox 寫入。
 *
 * 只有三個呼叫點（建單、付款成功、補償），所以這個類別不是為了「可擴展」而抽的抽象 ——
 * 它存在的理由是**分區鍵**：每一次都要記得用 flashSaleId 當鍵，忘記一次就有一條事件流
 * 失去順序保證，而那種錯誤在測試裡看不出來（單分區時一切正常）。集中在一個地方就不會忘。
 */
@Component
public class OrderEventPublisher {

    private final OutboxWriter outboxWriter;

    public OrderEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void orderCreated(Order order, String productName, int quantity, java.math.BigDecimal unitPrice) {
        outboxWriter.write("Order", order.getId().toString(), EventTypes.ORDER_CREATED,
            new OrderCreatedEvent(order.getId(), order.getOrderNo(), order.getUserId(), order.getFlashSaleId(),
                order.getItems().get(0).getProductId(), productName, quantity, unitPrice,
                order.getTotalAmount(), order.getCreatedAt()),
            partitionKey(order));
    }

    public void statusChanged(Order order, OrderStatus from) {
        outboxWriter.write("Order", order.getId().toString(), EventTypes.ORDER_STATUS_CHANGED,
            new OrderStatusChangedEvent(order.getId(), order.getFlashSaleId(), from.name(),
                order.getStatus().name(), order.getTotalAmount(), Instant.now()),
            partitionKey(order));
    }

    /**
     * V4 之前建立的訂單沒有 flashSaleId。那些訂單不會再有新事件（它們早就是終態），
     * 但補償路徑理論上碰得到，所以這裡退回用 orderId 當鍵而不是丟例外 ——
     * 順序保證對一筆孤兒訂單沒有意義，讓事件發不出去才是真的損失。
     */
    private static String partitionKey(Order order) {
        return order.getFlashSaleId() == null ? "order-" + order.getId() : String.valueOf(order.getFlashSaleId());
    }
}
