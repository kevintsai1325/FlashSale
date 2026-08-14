package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.NotificationView;
import com.flashsale.admin.application.dto.PagedResult;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.application.NotificationRetryService;
import com.flashsale.notification.domain.NotificationChannel;
import com.flashsale.notification.domain.NotificationDelivery;
import com.flashsale.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Admin notification center: list/detail/read-status/retry/unread-count over
 * {@code notification_deliveries} (Task 4).
 *
 * <p><b>Deliberate deviation from the Task 7 brief's literal Step 4 text</b> (carried forward as
 * binding guidance from Task 4's review): {@link NotificationRetryService#retry(Long)} itself has
 * no precondition on the delivery's current status - by Task 4's design that call is a raw
 * "re-attempt this row" primitive, with any "should we retry right now" decision left to the
 * caller (the scheduler decides via attempt-count/backoff; an admin's explicit click is its own
 * decision). Once this service exposes that primitive over HTTP, calling it on an already-{@code
 * SENT} or still-{@code PENDING} row would re-send a real customer notification with no
 * legitimate trigger behind it. {@link #retry(Long)} therefore checks the delivery's status and
 * rejects anything other than {@code FAILED} with a 409 ({@link ConflictException}) before
 * delegating to {@link NotificationRetryService#retry(Long)} - it does not reimplement any
 * send-attempt logic, only adds the precondition that makes the HTTP entry point safe.
 */
@Service
public class AdminNotificationService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRetryService notificationRetryService;

    public AdminNotificationService(NotificationDeliveryRepository deliveryRepository,
                                     NotificationRetryService notificationRetryService) {
        this.deliveryRepository = deliveryRepository;
        this.notificationRetryService = notificationRetryService;
    }

    public PagedResult<NotificationView> list(Long userId, NotificationChannel channel, NotificationStatus status,
                                               Boolean read, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<NotificationDelivery> result = deliveryRepository.search(userId, channel, status, read, pageable);

        List<NotificationView> content = result.getContent().stream()
            .map(NotificationView::from)
            .toList();

        return new PagedResult<>(content, result.getTotalElements(), page, size);
    }

    public NotificationView detail(Long id) {
        return NotificationView.from(findOrThrow(id));
    }

    public void updateReadStatus(List<Long> ids, boolean read) {
        List<NotificationDelivery> deliveries = deliveryRepository.findByIdIn(ids);
        for (NotificationDelivery delivery : deliveries) {
            if (read) {
                delivery.markRead();
            } else {
                delivery.markUnread();
            }
            deliveryRepository.save(delivery);
        }
    }

    public void retry(Long id) {
        NotificationDelivery delivery = findOrThrow(id);
        if (delivery.getStatus() != NotificationStatus.FAILED) {
            throw new ConflictException("NOTIFICATION_NOT_FAILED",
                "Notification " + id + " is " + delivery.getStatus() + ", only FAILED deliveries can be retried");
        }
        notificationRetryService.retry(id);
    }

    public long unreadCount() {
        return deliveryRepository.countByRead(false);
    }

    private NotificationDelivery findOrThrow(Long id) {
        return deliveryRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("NOTIFICATION_NOT_FOUND",
                "Notification " + id + " does not exist"));
    }
}
