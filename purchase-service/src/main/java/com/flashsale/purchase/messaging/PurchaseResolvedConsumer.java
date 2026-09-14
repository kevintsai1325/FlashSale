package com.flashsale.purchase.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.purchase.application.PurchaseRequestRepository;
import com.flashsale.purchase.config.RabbitConfig;
import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * 搶購終態的唯一寫入者。
 *
 * 冪等靠的是狀態本身而不是額外的去重表：只有 PENDING 會被改寫，重複投遞的第二次會看到
 * 已經是終態、直接返回。這比 backend 那邊的 ConsumedMessageGuard 少一張表，
 * 而且在這個情境下夠用——終態是不可逆的，重放同一個事件不會得到不同結果。
 */
@Component
public class PurchaseResolvedConsumer {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseResolvedConsumer.class);

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final ObjectMapper objectMapper;

    public PurchaseResolvedConsumer(PurchaseRequestRepository purchaseRequestRepository, ObjectMapper objectMapper) {
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.PURCHASE_RESOLVED_QUEUE)
    @Transactional
    public void handle(Message message) throws IOException {
        PurchaseResolvedEvent event = objectMapper.readValue(message.getBody(), PurchaseResolvedEvent.class);

        PurchaseRequest request = purchaseRequestRepository.findById(event.purchaseRequestId())
            .orElseThrow(() -> new IllegalStateException(
                "PurchaseRequest " + event.purchaseRequestId() + " not found"));

        if (request.getStatus() != PurchaseRequestStatus.PENDING) {
            // 已經是終態：重複投遞，或補償與成功的事件互相追上。終態不可逆，直接結束。
            logger.debug("PurchaseRequest {} 已是 {}，忽略重複的終態事件",
                event.purchaseRequestId(), request.getStatus());
            return;
        }

        if (PurchaseRequestStatus.SUCCEEDED.name().equals(event.status())) {
            if (event.orderId() == null) {
                throw new IllegalStateException(
                    "PurchaseResolved(SUCCEEDED) for " + event.purchaseRequestId() + " carries no orderId");
            }
            request.markSucceeded(event.orderId());
        } else {
            request.markFailed();
        }
        purchaseRequestRepository.save(request);
    }
}
