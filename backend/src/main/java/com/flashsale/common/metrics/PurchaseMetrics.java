package com.flashsale.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting purchase-flow metrics, called from both the inventory module's Redis gateway and
 * the order module's purchase consumer. Lives under {@code common} rather than either module's
 * own {@code adapter} package so neither module has to reach into the other's adapter layer to
 * use it (see design spec §4).
 */
@Component
public class PurchaseMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter orderCreatedCounter;

    public PurchaseMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.orderCreatedCounter = Counter.builder("purchase.order.created")
            .description("Orders successfully created from a Redis-reserved purchase request")
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

    public void orderCreated() {
        orderCreatedCounter.increment();
    }
}
