package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationChannel;
import com.flashsale.notification.domain.NotificationDelivery;
import com.flashsale.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface NotificationDeliveryRepository {
    NotificationDelivery save(NotificationDelivery delivery);

    Optional<NotificationDelivery> findById(Long id);

    List<NotificationDelivery> findFailedWithAttemptsBelow(int maxAttempts);

    // Admin notification center (com.flashsale.admin).
    Page<NotificationDelivery> search(Long userId, NotificationChannel channel, NotificationStatus status,
                                       Boolean read, Pageable pageable);

    List<NotificationDelivery> findByIdIn(List<Long> ids);

    long countByRead(boolean read);
}
