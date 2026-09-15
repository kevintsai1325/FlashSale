package com.flashsale.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka 上 {@code flashsale.order-events} 的兩種事件之一：OrderCreated。
 *
 * **只解析需要的欄位，而且遇到不認得的事件回 null 而不是拋例外。**
 * 事件的結構是由別的服務決定的，它隨時可能多出欄位；一個會因為多一個欄位就崩潰的
 * 串流作業，等於讓上游的每一次演進都變成一次線上事故。
 */
public final class OrderEvent implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long orderId;
    private final long productId;
    private final String productName;
    private final int quantity;
    private final BigDecimal totalAmount;
    private final long createdAtMillis;

    private OrderEvent(long orderId, long productId, String productName, int quantity,
                        BigDecimal totalAmount, long createdAtMillis) {
        this.orderId = orderId;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.createdAtMillis = createdAtMillis;
    }

    /** 不是 OrderCreated（例如 OrderStatusChanged）或解析失敗時回 {@code null}。 */
    public static OrderEvent parseCreated(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            // OrderCreated 才有 orderNo；OrderStatusChanged 沒有。用欄位形狀分辨，
            // 與 analytics-service 的消費端同一套做法。
            if (!node.hasNonNull("orderNo") || !node.hasNonNull("createdAt")) {
                return null;
            }
            return new OrderEvent(
                node.get("orderId").asLong(),
                node.path("productId").asLong(),
                node.path("productName").asText("(unknown)"),
                node.path("quantity").asInt(1),
                new BigDecimal(node.get("totalAmount").asText()),
                Instant.parse(node.get("createdAt").asText()).toEpochMilli());
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    public long getOrderId() { return orderId; }
    public long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public long getCreatedAtMillis() { return createdAtMillis; }
}
