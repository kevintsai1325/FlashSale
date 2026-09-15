package com.flashsale.flink;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 串流作業裡兩段值得測的純邏輯：事件解析與排名。
 *
 * 視窗與 watermark 的行為不在這裡測 —— 那需要整個 Flink 的迷你叢集，而它們的正確性
 * 是由 Flink 保證的。這裡守的是**我們自己寫的那部分**：解析容不容錯、排名穩不穩定。
 */
class RealtimeDashboardJobTest {

    private static final String ORDER_CREATED = """
        {"orderId":1,"orderNo":"ORD-1","userId":2,"flashSaleId":7,"productId":3,
         "productName":"limited keyboard","quantity":2,"unitPrice":9.99,"totalAmount":19.98,
         "createdAt":"2026-09-15T10:00:00Z"}
        """;

    private static final String ORDER_STATUS_CHANGED = """
        {"orderId":1,"flashSaleId":7,"fromStatus":"PENDING_PAYMENT","toStatus":"PAID",
         "totalAmount":19.98,"changedAt":"2026-09-15T10:05:00Z"}
        """;

    @Test
    void parsesAnOrderCreatedEvent() {
        OrderEvent event = OrderEvent.parseCreated(ORDER_CREATED);

        assertThat(event).isNotNull();
        assertThat(event.getOrderId()).isEqualTo(1);
        assertThat(event.getTotalAmount()).isEqualByComparingTo("19.98");
        assertThat(event.getQuantity()).isEqualTo(2);
        assertThat(event.getCreatedAtMillis()).isEqualTo(1789466400000L);
    }

    @Test
    void ignoresTheOtherEventTypeOnTheSameTopic() {
        // 同一個 topic 上有兩種事件。把 OrderStatusChanged 當成 OrderCreated 來算，
        // GMV 會把每一筆付款、取消、逾時都再算一次金額。
        assertThat(OrderEvent.parseCreated(ORDER_STATUS_CHANGED)).isNull();
    }

    @Test
    void ignoresMalformedPayloadInsteadOfCrashingTheJob() {
        // 上游隨時可能多出欄位或送出壞掉的一筆。一個會因此崩潰的串流作業，
        // 等於讓上游的每一次演進都變成一次線上事故。
        assertThat(OrderEvent.parseCreated("not json at all")).isNull();
        assertThat(OrderEvent.parseCreated("{\"orderNo\":\"ORD-1\"}")).isNull();
    }

    @Test
    void ranksProductsByTotalQuantityAndBreaksTiesDeterministically() {
        List<OrderEvent> events = List.of(
            order(10L, "A", 1), order(10L, "A", 2),   // 3
            order(20L, "B", 3),                        // 3，與 A 平手
            order(30L, "C", 5));                       // 5

        List<String> ranked = RealtimeDashboardJob.rank(events, 5);

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0)).contains("\"productId\":30").contains("\"quantity\":5");
        // 平手時以 productId 排序：沒有這個 tiebreaker，排行榜會在視窗之間無意義地跳動。
        assertThat(ranked.get(1)).contains("\"productId\":10");
        assertThat(ranked.get(2)).contains("\"productId\":20");
    }

    @Test
    void keepsOnlyTheTopN() {
        List<OrderEvent> events = List.of(
            order(1L, "A", 1), order(2L, "B", 2), order(3L, "C", 3), order(4L, "D", 4));

        assertThat(RealtimeDashboardJob.rank(events, 2)).hasSize(2);
    }

    private static OrderEvent order(long productId, String name, int quantity) {
        return OrderEvent.parseCreated("""
            {"orderId":%d,"orderNo":"ORD-%d","userId":1,"flashSaleId":7,"productId":%d,
             "productName":"%s","quantity":%d,"unitPrice":1.00,"totalAmount":%d.00,
             "createdAt":"2026-09-15T10:00:00Z"}
            """.formatted(productId, productId, productId, name, quantity, quantity));
    }
}
