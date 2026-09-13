package com.flashsale.notification.application;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.notification.domain.NotificationDelivery;
import io.micrometer.observation.annotation.Observed;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Postgres-scheduled-scan retry for FAILED email deliveries, following the same pattern as
 * {@code PaymentTimeoutScheduler}/{@code InventoryReconciliationScheduler} - not an
 * outbox/RabbitMQ mechanism. MAX_ATTEMPTS and the backoff steps are hardcoded per design spec
 * §5.2: two steps before the cap don't warrant a configurable backoff-strategy abstraction.
 */
@Component
public class NotificationRetryScheduler {

    // 重試會實際送出郵件，SMTP 往返可能較慢；120 秒是任務間隔（60 秒）的兩倍，
    // 持鎖副本崩潰後最多晚一輪接手。
    private static final Duration LOCK_LEASE = Duration.ofSeconds(120);

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF_AFTER_ONE_FAILURE = Duration.ofMinutes(1);
    private static final Duration BACKOFF_AFTER_TWO_FAILURES = Duration.ofMinutes(5);

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSender notificationSender;
    private final SchedulerLock schedulerLock;

    public NotificationRetryScheduler(NotificationDeliveryRepository deliveryRepository,
                                       NotificationSender notificationSender,
                                       SchedulerLock schedulerLock) {
        this.deliveryRepository = deliveryRepository;
        this.notificationSender = notificationSender;
        this.schedulerLock = schedulerLock;
    }

    @Scheduled(fixedDelay = 60000)
    @Observed(name = "scheduler.retryDueNotifications")
    public void retryDueNotifications() {
        schedulerLock.runIfLocked("retryDueNotifications", LOCK_LEASE, this::doRetryDueNotifications);
    }

    private void doRetryDueNotifications() {
        for (NotificationDelivery delivery : deliveryRepository.findFailedWithAttemptsBelow(MAX_ATTEMPTS)) {
            if (isDue(delivery)) {
                notificationSender.retryAttempt(delivery);
                deliveryRepository.save(delivery);
            }
        }
    }

    private boolean isDue(NotificationDelivery delivery) {
        Duration backoff = delivery.getAttemptCount() <= 1 ? BACKOFF_AFTER_ONE_FAILURE : BACKOFF_AFTER_TWO_FAILURES;
        return !Instant.now().isBefore(delivery.getUpdatedAt().plus(backoff));
    }
}
