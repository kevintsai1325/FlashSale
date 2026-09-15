package com.flashsale.analytics.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.analytics.domain.OrderProjection;
import com.flashsale.analytics.domain.PurchaseRequestProjection;
import com.flashsale.analytics.repository.OrderProjectionRepository;
import com.flashsale.analytics.repository.PurchaseRequestProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 兩條事件流餵同一份讀取模型。
 *
 * **沒有去重表。** 發佈端是「至少一次」，但這裡的每一個寫入都是以來源系統的 id 為主鍵的
 * upsert，重放同一個事件得到的是同一個結果。冪等來自資料模型，不是來自一張額外的表 ——
 * 這是讀取模型相對於計數器的主要好處。
 *
 * 事件型別用 payload 的欄位形狀分辨，而不是一個 eventType 標頭：兩個 topic 各自只有兩種
 * 事件，而 `orderNo` / `resolvedAt` 的有無就足以區分。多一個標頭就多一個要維持一致的契約。
 */
@Component
public class DomainEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final OrderProjectionRepository orderProjectionRepository;
    private final PurchaseRequestProjectionRepository purchaseRequestProjectionRepository;
    private final ObjectMapper objectMapper;

    public DomainEventConsumer(OrderProjectionRepository orderProjectionRepository,
                                PurchaseRequestProjectionRepository purchaseRequestProjectionRepository,
                                ObjectMapper objectMapper) {
        this.orderProjectionRepository = orderProjectionRepository;
        this.purchaseRequestProjectionRepository = purchaseRequestProjectionRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.order-events-topic}", groupId = "${app.kafka.group-id}")
    @Transactional
    public void onOrderEvent(String payload) throws IOException {
        JsonNode node = objectMapper.readTree(payload);
        if (node.hasNonNull("orderNo")) {
            orderProjectionRepository.save(OrderProjection.created(
                node.get("orderId").asLong(),
                node.get("orderNo").asText(),
                node.get("userId").asLong(),
                optionalLong(node, "flashSaleId"),
                optionalLong(node, "productId"),
                node.path("productName").asText(null),
                node.path("quantity").asInt(),
                new BigDecimal(node.get("totalAmount").asText()),
                Instant.parse(node.get("createdAt").asText())));
            return;
        }
        long orderId = node.get("orderId").asLong();
        orderProjectionRepository.findById(orderId).ifPresentOrElse(
            projection -> projection.applyStatus(node.get("toStatus").asText(),
                Instant.parse(node.get("changedAt").asText())),
            // 同一個 flashSaleId 的事件都在同一個分區，所以 Created 一定先到。
            // 走到這裡代表投影被手動清掉過而 offset 沒有一起倒回去 —— 值得一行警告。
            () -> logger.warn("OrderStatusChanged for unknown order {}; projection out of sync", orderId));
    }

    @KafkaListener(topics = "${app.kafka.purchase-events-topic}", groupId = "${app.kafka.group-id}")
    @Transactional
    public void onPurchaseEvent(String payload) throws IOException {
        JsonNode node = objectMapper.readTree(payload);
        UUID requestId = UUID.fromString(node.get("requestId").asText());
        if (node.hasNonNull("resolvedAt")) {
            purchaseRequestProjectionRepository.findById(requestId).ifPresentOrElse(
                projection -> projection.resolve(node.get("status").asText(), optionalLong(node, "orderId"),
                    Instant.parse(node.get("resolvedAt").asText())),
                () -> logger.warn("PurchaseRequestResolved for unknown request {}; projection out of sync", requestId));
            return;
        }
        purchaseRequestProjectionRepository.save(PurchaseRequestProjection.created(
            requestId,
            node.get("userId").asLong(),
            node.get("flashSaleId").asLong(),
            node.get("status").asText(),
            Instant.parse(node.get("createdAt").asText())));
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
