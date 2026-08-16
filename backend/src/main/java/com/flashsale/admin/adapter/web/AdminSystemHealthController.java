package com.flashsale.admin.adapter.web;

import com.flashsale.admin.application.health.SystemHealthService;
import com.flashsale.admin.application.health.SystemHealthView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/system-health")
public class AdminSystemHealthController {

    private final SystemHealthService systemHealthService;

    public AdminSystemHealthController(SystemHealthService systemHealthService) {
        this.systemHealthService = systemHealthService;
    }

    @GetMapping
    public SystemHealthView getSystemHealth() {
        return systemHealthService.snapshot();
    }
}
