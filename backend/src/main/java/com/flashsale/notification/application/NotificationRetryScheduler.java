package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;
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

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF_AFTER_ONE_FAILURE = Duration.ofMinutes(1);
    private static final Duration BACKOFF_AFTER_TWO_FAILURES = Duration.ofMinutes(5);

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSender notificationSender;

    public NotificationRetryScheduler(NotificationDeliveryRepository deliveryRepository,
                                       NotificationSender notificationSender) {
        this.deliveryRepository = deliveryRepository;
        this.notificationSender = notificationSender;
    }

    @Scheduled(fixedDelay = 60000)
    public void retryDueNotifications() {
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
