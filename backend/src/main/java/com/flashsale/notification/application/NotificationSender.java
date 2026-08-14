package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;

public interface NotificationSender {
    void send(NotificationDelivery delivery);

    /**
     * Re-attempts delivery for an already-persisted {@link NotificationDelivery} row (does not
     * insert a new row and does not save the updated status — callers control persistence).
     * Shares the same send-attempt logic as {@link #send(NotificationDelivery)}, minus the
     * initial insert.
     */
    void retryAttempt(NotificationDelivery delivery);
}
