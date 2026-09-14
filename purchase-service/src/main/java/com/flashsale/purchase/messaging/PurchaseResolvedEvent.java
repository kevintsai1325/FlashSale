package com.flashsale.purchase.messaging;

/**
 * backend 建單成功或補償失敗之後，回頭告訴 purchase-service 這筆搶購的終態。
 *
 * 為什麼要有這個事件，而不是讓 backend 直接改 purchase_requests：
 * 步驟 1 兩個服務共用同一個資料庫，直接寫是能動的，但那會讓同一張表有兩個寫入者，
 * 步驟 2 拆庫時一定要整個重做。用事件的話，步驟 2 只需要換掉儲存位置。
 *
 * status 只會是 SUCCEEDED 或 FAILED；SUCCEEDED 時 orderId 必定不為 null。
 */
public record PurchaseResolvedEvent(Long purchaseRequestId, String status, Long orderId) {}
