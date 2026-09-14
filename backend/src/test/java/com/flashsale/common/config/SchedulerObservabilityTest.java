package com.flashsale.common.config;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.common.web.ApiAuditLogJpaRepository;
import com.flashsale.common.web.ApiAuditRetentionScheduler;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.inventory.application.InventoryReconciliationScheduler;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.application.NotificationRetryScheduler;
import com.flashsale.notification.application.NotificationSender;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.PaymentTimeoutScheduler;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Instant;
import java.util.List;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies each scheduled background job is wrapped with {@code @Observed} and actually produces
 * an observation when it runs — using {@link AspectJProxyFactory} to apply {@link ObservedAspect}
 * directly to a manually constructed instance, without booting a Spring context or any
 * Testcontainers (see design spec §5.3 / §10: Micrometer's own recommended way to test
 * {@code @Observed} aspects in isolation).
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
        FlashSaleRepository flashSaleRepository = mock(FlashSaleRepository.class);
        when(flashSaleRepository.findAll()).thenReturn(List.of());
        InventoryReconciliationScheduler scheduler = observedProxy(
            new InventoryReconciliationScheduler(flashSaleRepository, mock(InventoryRepository.class),
                mock(InventoryStockGateway.class), grantingLock()));

        scheduler.reconcileActiveFlashSales();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.reconcileActiveFlashSales");
    }

    @Test
    void notificationRetrySchedulerRunIsObserved() {
        NotificationDeliveryRepository deliveryRepository = mock(NotificationDeliveryRepository.class);
        when(deliveryRepository.findFailedWithAttemptsBelow(anyInt())).thenReturn(List.of());
        NotificationRetryScheduler scheduler = observedProxy(
            new NotificationRetryScheduler(deliveryRepository, mock(NotificationSender.class), grantingLock()));

        scheduler.retryDueNotifications();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.retryDueNotifications");
    }

    @Test
    void apiAuditRetentionSchedulerRunIsObserved() {
        ApiAuditLogJpaRepository repository = mock(ApiAuditLogJpaRepository.class);
        when(repository.deleteByOccurredAtBefore(any(Instant.class))).thenReturn(0);
        ApiAuditRetentionScheduler scheduler = observedProxy(new ApiAuditRetentionScheduler(repository, grantingLock(), 30));

        scheduler.purgeExpiredLogs();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.purgeExpiredLogs");
    }

    /**
     * 總是放行的 SchedulerLock：直接執行傳進來的工作。
     *
     * <p>用真的執行而不是空的 mock，是為了讓這些測試仍然涵蓋排程的實際內容 —— 若只回傳
     * false 而不跑 Runnable，測試就只驗證了「@Observed 有掛上」，排程本身是死的也會通過。
     * 互斥行為本身由 {@code SchedulerLockTest} 負責。
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
