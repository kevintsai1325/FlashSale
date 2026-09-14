package com.flashsale.purchase.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 指標名稱與拆分前一致（purchase.reservation、purchase.reservation.latency），
 * 因為壓測的取樣腳本 load-tests/k8s/sample-downstream.ps1 是按名字讀的。
 * 改名字會讓既有的量測工具安靜地讀不到東西 —— 那正是 P3 踩過的坑。
 */
@Component
public class PurchaseMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter flashSaleCacheServedStaleCounter;

    public PurchaseMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.flashSaleCacheServedStaleCounter = Counter.builder("purchase.flashsale.stale.served")
            .description("活動查詢失敗、改用寬限期內的過期快取放行的次數")
            .register(meterRegistry);
    }

    public void recordReservationOutcome(String outcome) {
        Counter.builder("purchase.reservation")
            .description("Outcome of a Redis stock reservation attempt")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startReservationTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopReservationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("purchase.reservation.latency")
            .description("Latency of the Redis Lua stock reservation call")
            .register(meterRegistry));
    }

    public void flashSaleStaleServed() {
        flashSaleCacheServedStaleCounter.increment();
    }
}
