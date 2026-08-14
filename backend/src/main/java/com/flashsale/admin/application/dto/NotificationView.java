package com.flashsale.admin.application.dto;

import com.flashsale.notification.domain.NotificationDelivery;

import java.time.Instant;

public record NotificationView(
    Long id,
    Long userId,
    String channel,
    String template,
    String recipient,
    String status,
    int attemptCount,
    String lastError,
    boolean read,
    Instant createdAt,
    Instant updatedAt
) {

    public static NotificationView from(NotificationDelivery delivery) {
        return new NotificationView(
            delivery.getId(),
            delivery.getUserId(),
            delivery.getChannel().name(),
            delivery.getTemplate(),
            delivery.getRecipient(),
            delivery.getStatus().name(),
            delivery.getAttemptCount(),
            delivery.getLastError(),
            delivery.isRead(),
            delivery.getCreatedAt(),
            delivery.getUpdatedAt());
    }
}
