package com.flashsale.order.application;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.order.domain.Order;
import io.micrometer.observation.annotation.Observed;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class PaymentTimeoutScheduler {

    // 租約必須大於一整批逾時訂單的處理時間。三副本叢集上 30 筆訂單在數十毫秒內處理完，
    // 60 秒留了非常寬裕的餘裕；同時它只有任務間隔（30 秒）的兩倍，持鎖副本崩潰後最多
    // 晚一輪就會有別的副本接手。
    private static final Duration LOCK_LEASE = Duration.ofSeconds(60);

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;
    private final SchedulerLock schedulerLock;

    public PaymentTimeoutScheduler(OrderRepository orderRepository,
                                    OrderCompensationService compensationService,
                                    SchedulerLock schedulerLock) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
        this.schedulerLock = schedulerLock;
    }

    /**
     * 四個排程中後果最嚴重的一個：沒有互斥時，同一筆逾時訂單會被每個副本各回補一次庫存，
     * 讓 available_quantity 超過 total_quantity —— 也就是憑空生出庫存。
     * 見 {@code docs/portfolio/scheduler-duplication-evidence.md}。
     */
    @Scheduled(fixedDelay = 30000)
    @Observed(name = "scheduler.expireOverduePayments")
    public void expireOverduePayments() {
        schedulerLock.runIfLocked("expireOverduePayments", LOCK_LEASE, this::doExpireOverduePayments);
    }

    private void doExpireOverduePayments() {
        List<Order> overdue = orderRepository.findPendingPaymentPastDue(Instant.now());
        for (Order order : overdue) {
            compensationService.markExpired(order);
        }
    }
}
