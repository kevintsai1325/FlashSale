package com.flashsale.admin.adapter.web;

import com.flashsale.admin.adapter.web.dto.ReadStatusRequest;
import com.flashsale.admin.application.AdminNotificationService;
import com.flashsale.admin.application.dto.NotificationView;
import com.flashsale.admin.application.dto.PagedResult;
import com.flashsale.notification.domain.NotificationChannel;
import com.flashsale.notification.domain.NotificationStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only read/action API behind {@code /api/admin/**} (guarded by {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} rule). Thin pass-through to {@link AdminNotificationService} - see
 * that class for the retry status guard (carried-forward requirement from Task 4's review).
 */
@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    public AdminNotificationController(AdminNotificationService adminNotificationService) {
        this.adminNotificationService = adminNotificationService;
    }

    @GetMapping
    public PagedResult<NotificationView> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Boolean read,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminNotificationService.list(userId, channel, status, read, page, size);
    }

    @GetMapping("/{id}")
    public NotificationView detail(@PathVariable Long id) {
        return adminNotificationService.detail(id);
    }

    @PatchMapping("/read-status")
    public ResponseEntity<Void> updateReadStatus(@Valid @RequestBody ReadStatusRequest request) {
        adminNotificationService.updateReadStatus(request.ids(), request.read());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        adminNotificationService.retry(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", adminNotificationService.unreadCount());
    }
}
