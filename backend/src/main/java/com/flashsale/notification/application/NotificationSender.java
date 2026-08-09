package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;

public interface NotificationSender {
    void send(NotificationDelivery delivery);
}
