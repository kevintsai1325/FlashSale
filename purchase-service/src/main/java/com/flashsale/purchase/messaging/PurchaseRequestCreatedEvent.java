package com.flashsale.purchase.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * 每一次搶購嘗試都發一個，無論結果是 PENDING、SOLD_OUT 還是 REJECTED。
 *
 * 為什麼連被擋下來的也發：下游要算的是「多少人來搶、多少人買到」，被擋下來的那些
 * 正是分母。只發成功的事件，轉換率就永遠是 100%。
 */
public record PurchaseRequestCreatedEvent(UUID requestId, Long userId, Long flashSaleId,
                                           String status, Instant createdAt) {}
