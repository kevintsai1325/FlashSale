package com.flashsale.admin.adapter.web;

import com.flashsale.admin.application.ApiAuditQueryService;
import com.flashsale.admin.application.dto.ApiAuditLogView;
import com.flashsale.admin.application.dto.PagedResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read API behind {@code /api/admin/**} (guarded by {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} rule). Filters over {@code api_audit_logs} (Task 2).
 */
@RestController
@RequestMapping("/api/admin/api-logs")
public class AdminApiLogController {

    private final ApiAuditQueryService apiAuditQueryService;

    public AdminApiLogController(ApiAuditQueryService apiAuditQueryService) {
        this.apiAuditQueryService = apiAuditQueryService;
    }

    @GetMapping
    public PagedResult<ApiAuditLogView> search(
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String pathTemplate,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return apiAuditQueryService.search(method, pathTemplate, status, userId, traceId, page, size);
    }
}
