package com.flashsale.purchase.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * 搶購請求從 PENDING 轉到終態時發。只有 PENDING 會有這個事件 —— SOLD_OUT 與 REJECTED
 * 在 Created 事件裡就已經是終態了。
 *
 * 下游因此可以用 Created + Resolved 兩條流算出「還在等待的筆數」，
 * 那正是 P5 混沌測試要觀察的東西。
 */
public record PurchaseRequestResolvedEvent(UUID requestId, Long userId, Long flashSaleId,
                                            String status, Long orderId, Instant resolvedAt) {}
