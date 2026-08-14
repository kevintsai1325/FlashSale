package com.flashsale.admin.adapter.web;

import com.flashsale.admin.application.DashboardQueryService;
import com.flashsale.admin.application.dto.DashboardSummary;
import com.flashsale.admin.application.dto.DashboardTrends;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read APIs behind {@code /api/admin/**} (guarded by {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} rule).
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final DashboardQueryService dashboardQueryService;

    public AdminDashboardController(DashboardQueryService dashboardQueryService) {
        this.dashboardQueryService = dashboardQueryService;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardQueryService.getSummary();
    }

    @GetMapping("/trends")
    public DashboardTrends trends() {
        return dashboardQueryService.getTrends();
    }
}
