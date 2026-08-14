package com.flashsale.notification.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.stereotype.Service;

@Service
public class NotificationRetryService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSender notificationSender;

    public NotificationRetryService(NotificationDeliveryRepository deliveryRepository,
                                     NotificationSender notificationSender) {
        this.deliveryRepository = deliveryRepository;
        this.notificationSender = notificationSender;
    }

    /**
     * Admin-initiated retry (Task 7's {@code POST /api/admin/notifications/{id}/retry}). Ignores
     * the scheduler's attempt-limit/backoff check - an explicit admin retry is an override, not
     * a "is it time yet" decision (see design spec §5.2/§6).
     */
    public void retry(Long deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new NotFoundException("NOTIFICATION_NOT_FOUND",
                "Notification " + deliveryId + " does not exist"));
        notificationSender.retryAttempt(delivery);
        deliveryRepository.save(delivery);
    }
}
