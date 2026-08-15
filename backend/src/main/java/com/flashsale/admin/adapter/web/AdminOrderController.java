package com.flashsale.admin.adapter.web;

import com.flashsale.admin.application.AdminOrderQueryService;
import com.flashsale.admin.application.dto.AdminOrderDetail;
import com.flashsale.admin.application.dto.AdminOrderSummary;
import com.flashsale.admin.application.dto.PagedResult;
import com.flashsale.order.domain.OrderStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read API behind {@code /api/admin/**} (guarded by {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} rule).
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderQueryService adminOrderQueryService;

    public AdminOrderController(AdminOrderQueryService adminOrderQueryService) {
        this.adminOrderQueryService = adminOrderQueryService;
    }

    @GetMapping
    public PagedResult<AdminOrderSummary> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminOrderQueryService.list(status, page, size);
    }

    @GetMapping("/{orderId}")
    public AdminOrderDetail detail(@PathVariable Long orderId) {
        return adminOrderQueryService.getDetail(orderId);
    }
}
