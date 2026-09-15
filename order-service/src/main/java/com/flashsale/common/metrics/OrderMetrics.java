package com.flashsale.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * P5：這個服務只剩一個指標。
 *
 * 拆分前這個類別叫 PurchaseMetrics，同時涵蓋 Redis 預扣與建單 —— 因為那兩件事在同一個行程。
 * 現在預扣在 purchase-service（它有自己的一份），建單在這裡，指標也跟著各自歸位。
 * 指標名稱維持 {@code purchase.order.created} 不變：那是作品集文件與既有壓測引用的名字，
 * 改名只會讓歷史數據對不上。
 */
@Component
public class OrderMetrics {

    private final Counter orderCreatedCounter;

    public OrderMetrics(MeterRegistry meterRegistry) {
        this.orderCreatedCounter = Counter.builder("purchase.order.created")
            .description("Orders successfully created from a Redis-reserved purchase request")
            .register(meterRegistry);
    }

    public void orderCreated() {
        orderCreatedCounter.increment();
    }
}
