package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;

public interface NotificationDeliveryRepository {
    NotificationDelivery save(NotificationDelivery delivery);
}
