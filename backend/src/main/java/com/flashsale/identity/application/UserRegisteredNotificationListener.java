package com.flashsale.identity.application;

import com.flashsale.notification.application.NotificationSender;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the registration-success notification only after the registering transaction has
 * committed. Listening at AFTER_COMMIT guarantees the new user row is visible to the
 * background thread that persists/sends the notification, avoiding a foreign-key race
 * against notification_deliveries.user_id.
 */
@Component
public class UserRegisteredNotificationListener {

    private final NotificationSender notificationSender;

    public UserRegisteredNotificationListener(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        notificationSender.send(
            NotificationDelivery.pendingEmail(event.userId(), "registration-success", event.email()));
    }
}
