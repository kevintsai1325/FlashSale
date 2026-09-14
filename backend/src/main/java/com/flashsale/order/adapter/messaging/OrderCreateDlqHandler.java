package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.application.event.PurchaseResolvedEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * 建單重試耗盡後的補償：釋放 Redis 預扣，並把搶購請求的終態回寫給 purchase-service。
 *
 * 冪等的來源變了：拆分前是讀 purchase_requests 的狀態（只有 PENDING 才補償），
 * 拆分後 backend 不再讀那張表來做決策，改用既有的去重表（ConsumedMessageGuard）。
 * 這很重要 —— 少了它，重複投遞的 DLQ 訊息會釋放兩次庫存。
 */
@Component
public class OrderCreateDlqHandler {

    private static final String CONSUMER_NAME = "order-create-dlq-handler";

    private final ConsumedMessageGuard consumedMessageGuard;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public OrderCreateDlqHandler(ConsumedMessageGuard consumedMessageGuard, OutboxWriter outboxWriter,
                                  ObjectMapper objectMapper) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.CREATE_ORDER_DLQ)
    @Transactional
    public void handle(Message message) throws IOException {
        Long outboxEventId = (Long) message.getMessageProperties().getHeaders().get("outboxEventId");
        if (!consumedMessageGuard.tryConsume(String.valueOf(outboxEventId), CONSUMER_NAME)) {
            return;
        }

        CreateOrderRequestedEvent event = objectMapper.readValue(message.getBody(), CreateOrderRequestedEvent.class);

        outboxWriter.write("PurchaseRequest", String.valueOf(event.purchaseRequestId()), EventTypes.PURCHASE_RESOLVED,
            new PurchaseResolvedEvent(event.purchaseRequestId(), "FAILED", null));

        outboxWriter.write("PurchaseRequest", String.valueOf(event.purchaseRequestId()), EventTypes.STOCK_RELEASE_REQUESTED,
            new StockReleaseRequestedEvent(event.flashSaleId(), event.quantity()));
    }
}
