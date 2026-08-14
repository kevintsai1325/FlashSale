package com.flashsale.admin.application.dto;

import com.flashsale.common.web.ApiAuditLog;

import java.time.Instant;

public record ApiAuditLogView(
    Long id,
    Instant occurredAt,
    String method,
    String pathTemplate,
    int status,
    Long userId,
    String requestId,
    String traceId,
    int durationMs,
    String clientIp,
    String userAgent,
    String errorCode
) {

    public static ApiAuditLogView from(ApiAuditLog log) {
        return new ApiAuditLogView(
            log.getId(),
            log.getOccurredAt(),
            log.getMethod(),
            log.getPathTemplate(),
            log.getStatus(),
            log.getUserId(),
            log.getRequestId(),
            log.getTraceId(),
            log.getDurationMs(),
            log.getClientIp(),
            log.getUserAgent(),
            log.getErrorCode());
    }
}
