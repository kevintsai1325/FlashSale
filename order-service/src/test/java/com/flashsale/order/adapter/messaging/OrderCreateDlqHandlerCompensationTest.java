package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.application.event.PurchaseResolvedEvent;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 補償路徑的冪等來源在拆分時換掉了：拆分前靠讀 purchase_requests 的狀態（只有 PENDING 才補償），
 * 拆分後 backend 不再讀那張表做決策，改用去重表。
 *
 * 少了這道守門，重複投遞的 DLQ 訊息會把同一筆庫存釋放兩次 —— 那是超賣的反面，一樣是錯的。
 */
@ExtendWith(MockitoExtension.class)
class OrderCreateDlqHandlerCompensationTest {

    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String EVENT_ID = "22222222-2222-2222-2222-222222222222";

    @Mock ConsumedMessageGuard consumedMessageGuard;
    @Mock OutboxWriter outboxWriter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderCreateDlqHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrderCreateDlqHandler(consumedMessageGuard, outboxWriter, objectMapper);
    }

    private Message messageFor(CreateOrderRequestedEvent event, String eventId) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("eventId", eventId);
        return new Message(objectMapper.writeValueAsBytes(event), properties);
    }

    @Test
    void releasesStockAndReportsFailureBackToPurchaseService() throws Exception {
        when(consumedMessageGuard.tryConsume(EVENT_ID, "order-create-dlq-handler")).thenReturn(true);

        handler.handle(messageFor(new CreateOrderRequestedEvent(REQUEST_ID, 202L, 303L, 404L, "item", 2, new BigDecimal("19.99")), EVENT_ID));

        verify(outboxWriter).write(eq("PurchaseRequest"), eq(REQUEST_ID.toString()), eq("PurchaseResolved"),
            eq(new PurchaseResolvedEvent(REQUEST_ID, "FAILED", null)));
        verify(outboxWriter).write(eq("PurchaseRequest"), eq(REQUEST_ID.toString()), eq("StockReleaseRequested"),
            eq(new StockReleaseRequestedEvent(303L, 2)));
    }

    @Test
    void aRedeliveredDlqMessageDoesNotCompensateTwice() throws Exception {
        when(consumedMessageGuard.tryConsume(EVENT_ID, "order-create-dlq-handler")).thenReturn(false);

        handler.handle(messageFor(new CreateOrderRequestedEvent(REQUEST_ID, 202L, 303L, 404L, "item", 2, new BigDecimal("19.99")), EVENT_ID));

        verifyNoInteractions(outboxWriter);
    }
}
