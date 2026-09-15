package com.flashsale.common.config;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.inventory.application.InventoryReconciliationScheduler;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.PlatformFlashSaleClient;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.PaymentTimeoutScheduler;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * order-service 的兩個排程都要被 {@code @Observed} 包住，而且真的跑起來時要產生觀測值 ——
 * 用 {@link AspectJProxyFactory} 直接把切面套在手動建構的物件上，不啟動 Spring context。
 *
 * P5 拆分時從 platform 的同名測試切出來：這兩個排程跟著訂單與庫存搬過來了，
 * 另外兩個（通知重試、稽核清理）留在 platform。
 */
class SchedulerObservabilityTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void paymentTimeoutSchedulerRunIsObserved() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findPendingPaymentPastDue(any(Instant.class))).thenReturn(List.of());
        PaymentTimeoutScheduler scheduler = observedProxy(
            new PaymentTimeoutScheduler(orderRepository, mock(OrderCompensationService.class), grantingLock()));

        scheduler.expireOverduePayments();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.expireOverduePayments");
    }

    @Test
    void inventoryReconciliationSchedulerRunIsObserved() {
        PlatformFlashSaleClient platformFlashSaleClient = mock(PlatformFlashSaleClient.class);
        when(platformFlashSaleClient.activeFlashSaleIds()).thenReturn(List.of());
        InventoryReconciliationScheduler scheduler = observedProxy(
            new InventoryReconciliationScheduler(platformFlashSaleClient, mock(InventoryRepository.class),
                mock(InventoryStockGateway.class), grantingLock()));

        scheduler.reconcileActiveFlashSales();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.reconcileActiveFlashSales");
    }

    /**
     * 總是放行的 SchedulerLock：直接執行傳進來的工作。
     *
     * <p>用真的執行而不是空的 mock，是為了讓這些測試仍然涵蓋排程的實際內容 —— 若只回傳
     * false 而不跑 Runnable，測試就只驗證了「@Observed 有掛上」，排程本身是死的也會通過。
     */
    private SchedulerLock grantingLock() {
        SchedulerLock lock = mock(SchedulerLock.class);
        when(lock.runIfLocked(any(String.class), any(Duration.class), any(Runnable.class)))
            .thenAnswer(invocation -> {
                invocation.getArgument(2, Runnable.class).run();
                return true;
            });
        return lock;
    }

    private <T> T observedProxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObservedAspect(registry));
        return factory.getProxy();
    }
}
