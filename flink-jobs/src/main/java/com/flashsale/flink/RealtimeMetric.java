package com.flashsale.flink;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 送往 {@code flashsale.realtime-metrics} 的一筆結果。
 *
 * 三個作業共用一種訊息形狀而不是三種：下游（大屏）要的是「這一秒的畫面」，
 * 三種形狀只會讓它寫三段解析。{@code type} 決定哪些欄位有意義。
 */
public final class RealtimeMetric implements Serializable {

    private static final long serialVersionUID = 1L;

    public static String gmv(long windowEndMillis, BigDecimal amount, long orderCount) {
        return String.format(
            "{\"type\":\"gmv\",\"windowEnd\":%d,\"amount\":%s,\"orderCount\":%d}",
            windowEndMillis, amount.toPlainString(), orderCount);
    }

    public static String topProducts(long windowEndMillis, List<String> entries) {
        return String.format("{\"type\":\"topProducts\",\"windowEnd\":%d,\"items\":[%s]}",
            windowEndMillis, String.join(",", entries));
    }

    public static String productEntry(long productId, String productName, long quantity) {
        return String.format("{\"productId\":%d,\"productName\":\"%s\",\"quantity\":%d}",
            productId, productName.replace("\"", "'"), quantity);
    }

    private RealtimeMetric() {}
}
