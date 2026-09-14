package com.flashsale.order.application.event;

/**
 * 建單成功或補償失敗之後，回頭告訴 purchase-service 這筆搶購的終態。
 *
 * backend 不再直接寫 purchase_requests：步驟 1 兩個服務共用同一個資料庫，直接寫是能動的，
 * 但那會讓同一張表有兩個寫入者，步驟 2 拆庫時一定要整個重做。
 *
 * 對應 purchase-service 的 PurchaseResolvedEvent，欄位形狀是契約。
 */
public record PurchaseResolvedEvent(Long purchaseRequestId, String status, Long orderId) {}
