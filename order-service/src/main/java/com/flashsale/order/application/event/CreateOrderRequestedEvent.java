package com.flashsale.order.application.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * purchaseRequestId 是 purchase-service 對外公開的 UUID，不是它資料表的 BIGSERIAL 主鍵。
 * 跨服務的參照只能用對方公開的識別碼——本地代理鍵是那個資料庫的實作細節，
 * 拆庫、換儲存引擎、或資料重建時它可以改變，而公開識別碼不行。
 */
public record CreateOrderRequestedEvent(UUID purchaseRequestId, Long userId, Long flashSaleId, Long productId,
                                         String productName, int quantity, BigDecimal unitPrice) {}
