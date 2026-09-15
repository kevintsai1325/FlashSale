package com.flashsale.common.messaging;

/**
 * 兩組事件，走兩條路（見 architecture.md 的「Kafka 與 RabbitMQ 各司其職」）：
 *
 * <ul>
 *   <li><b>命令</b>（上半段）走 RabbitMQ：要送給特定對象、需要 per-message ack、重試與 DLQ。</li>
 *   <li><b>領域事件</b>（下半段）走 Kafka：廣播、要保留、要能重放、多個消費者互不影響。</li>
 * </ul>
 *
 * 分界不是「新舊」而是語意。把命令塞進 Kafka 要自己刻重試與毒訊息隔離；
 * 把領域事件塞進 RabbitMQ 則沒有保留期，下游想重算聚合只能回頭掃資料庫。
 */
public final class EventTypes {

    // 命令 —— RabbitMQ
    public static final String CREATE_ORDER_REQUESTED = "CreateOrderRequested";
    public static final String STOCK_RELEASE_REQUESTED = "StockReleaseRequested";
    public static final String PURCHASE_RESOLVED = "PurchaseResolved";

    // 領域事件 —— Kafka
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_STATUS_CHANGED = "OrderStatusChanged";

    private EventTypes() {}
}
