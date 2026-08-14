package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class OrderCreateDlqHandler {

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public OrderCreateDlqHandler(PurchaseRequestRepository purchaseRequestRepository, OutboxWriter outboxWriter,
                                  ObjectMapper objectMapper) {
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.CREATE_ORDER_DLQ)
    @Transactional
    public void handle(Message message) throws IOException {
        CreateOrderRequestedEvent event = objectMapper.readValue(message.getBody(), CreateOrderRequestedEvent.class);

        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(event.purchaseRequestId())
            .orElseThrow(() -> new IllegalStateException("PurchaseRequest " + event.purchaseRequestId() + " not found"));

        if (purchaseRequest.getStatus() != PurchaseRequestStatus.PENDING) {
            // Already resolved (e.g. a duplicate DLQ delivery) — nothing left to compensate.
            return;
        }

        purchaseRequest.markFailed();
        purchaseRequestRepository.save(purchaseRequest);

        outboxWriter.write("PurchaseRequest", purchaseRequest.getId().toString(), EventTypes.STOCK_RELEASE_REQUESTED,
            new StockReleaseRequestedEvent(event.flashSaleId(), event.quantity()));
    }
}
