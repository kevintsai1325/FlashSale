package com.flashsale.inventory.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class StockReleaseConsumer {

    private static final String CONSUMER_NAME = "stock-release-consumer";

    private final ConsumedMessageGuard consumedMessageGuard;
    private final InventoryStockGateway inventoryStockGateway;
    private final ObjectMapper objectMapper;

    public StockReleaseConsumer(ConsumedMessageGuard consumedMessageGuard, InventoryStockGateway inventoryStockGateway,
                                 ObjectMapper objectMapper) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.inventoryStockGateway = inventoryStockGateway;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.STOCK_RELEASE_QUEUE)
    @Transactional
    public void handle(Message message) throws IOException {
        Long outboxEventId = (Long) message.getMessageProperties().getHeaders().get("outboxEventId");
        if (!consumedMessageGuard.tryConsume(String.valueOf(outboxEventId), CONSUMER_NAME)) {
            return;
        }
        StockReleaseRequestedEvent event = objectMapper.readValue(message.getBody(), StockReleaseRequestedEvent.class);
        inventoryStockGateway.release(event.flashSaleId(), event.quantity());
    }
}
