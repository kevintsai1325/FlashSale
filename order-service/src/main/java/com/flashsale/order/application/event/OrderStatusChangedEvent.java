package com.flashsale.order.application.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 訂單狀態轉移，走 Kafka。一個事件涵蓋付款成功、取消、逾時三種轉移，
 * 而不是三個事件型別：下游關心的是「轉到哪個狀態」，用三個型別只會讓每個消費者
 * 都要寫一次三選一的分派。
 *
 * totalAmount 一起帶著，下游算 GMV 修正時才不必回頭查訂單。
 */
public record OrderStatusChangedEvent(Long orderId, Long flashSaleId, String fromStatus, String toStatus,
                                       BigDecimal totalAmount, Instant changedAt) {}
