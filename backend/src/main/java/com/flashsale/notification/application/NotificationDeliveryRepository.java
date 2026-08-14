package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;

import java.util.List;
import java.util.Optional;

public interface NotificationDeliveryRepository {
    NotificationDelivery save(NotificationDelivery delivery);

    Optional<NotificationDelivery> findById(Long id);

    List<NotificationDelivery> findFailedWithAttemptsBelow(int maxAttempts);
}
