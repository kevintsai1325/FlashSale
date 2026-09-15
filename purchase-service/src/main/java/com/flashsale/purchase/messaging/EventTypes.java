package com.flashsale.purchase.messaging;

/**
 * 這個服務**發佈**的事件型別。步驟 1 時這裡還有 StockReleaseRequested 與 PurchaseResolved，
 * 因為那時兩個服務共用同一張 outbox 表，發佈器隨時可能撈到對方寫的列。
 * 步驟 2 各自有了自己的 outbox，那個理由消失了 —— 留著會讓下一個人以為表還是共用的。
 */
public final class EventTypes {

    // 命令 —— RabbitMQ
    public static final String CREATE_ORDER_REQUESTED = "CreateOrderRequested";

    // 領域事件 —— Kafka
    public static final String PURCHASE_REQUEST_CREATED = "PurchaseRequestCreated";
    public static final String PURCHASE_REQUEST_RESOLVED = "PurchaseRequestResolved";

    private EventTypes() {}
}
