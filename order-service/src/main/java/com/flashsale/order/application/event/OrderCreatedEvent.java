package com.flashsale.order.application.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 領域事件，走 Kafka。**與 PurchaseResolved 不同**：那個是回呼給特定服務的命令式訊息，
 * 這個是「發生了什麼事」的廣播，誰想聽都可以聽，而且可以重放。
 *
 * 欄位刻意包含 productName 與 totalAmount 這些「算得出來」的東西：
 * 事件是給下游用的，下游不該為了顯示商品名稱而回頭查 platform 的資料庫 ——
 * 那正是 P5 要消除的跨服務查詢。事件自帶足夠的上下文，這叫 event-carried state transfer。
 */
public record OrderCreatedEvent(Long orderId, String orderNo, Long userId, Long flashSaleId,
                                 Long productId, String productName, int quantity,
                                 BigDecimal unitPrice, BigDecimal totalAmount, Instant createdAt) {}
