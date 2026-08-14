package com.flashsale.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String template;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationDelivery() {}

    public static NotificationDelivery pendingEmail(Long userId, String template, String recipient) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.userId = userId;
        delivery.channel = NotificationChannel.EMAIL;
        delivery.template = template;
        delivery.recipient = recipient;
        delivery.status = NotificationStatus.PENDING;
        delivery.createdAt = Instant.now();
        delivery.updatedAt = Instant.now();
        return delivery;
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = NotificationStatus.FAILED;
        this.lastError = error;
        this.attemptCount++;
        this.updatedAt = Instant.now();
    }

    /** Admin batch read-status toggle (Task 7, {@code PATCH /api/admin/notifications/read-status}). */
    public void markRead() {
        this.read = true;
        this.updatedAt = Instant.now();
    }

    /** Admin batch read-status toggle (Task 7, {@code PATCH /api/admin/notifications/read-status}). */
    public void markUnread() {
        this.read = false;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public NotificationChannel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getTemplate() { return template; }
    public NotificationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public boolean isRead() { return read; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
