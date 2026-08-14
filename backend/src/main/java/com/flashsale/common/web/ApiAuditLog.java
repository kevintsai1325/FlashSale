package com.flashsale.common.web;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per HTTP request handled by the app. Written by {@link ApiAuditWriter} from
 * {@link ApiAuditFilter}. Never carries Authorization headers, passwords, JWT contents, or
 * request/response bodies — the filter that populates this only reads status codes, headers
 * that are safe by construction (User-Agent, remote address), and the authenticated user id.
 */
@Entity
@Table(name = "api_audit_logs")
public class ApiAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DB-defaulted (now()); never set from Java.
    @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "path_template", nullable = false)
    private String pathTemplate;

    @Column(nullable = false)
    private int status;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    protected ApiAuditLog() {
    }

    public ApiAuditLog(String method, String pathTemplate, int status, Long userId, String requestId,
                        String traceId, int durationMs, String clientIp, String userAgent, String errorCode) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.status = status;
        this.userId = userId;
        this.requestId = requestId;
        this.traceId = traceId;
        this.durationMs = durationMs;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.errorCode = errorCode;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getMethod() { return method; }
    public String getPathTemplate() { return pathTemplate; }
    public int getStatus() { return status; }
    public Long getUserId() { return userId; }
    public String getRequestId() { return requestId; }
    public String getTraceId() { return traceId; }
    public int getDurationMs() { return durationMs; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public String getErrorCode() { return errorCode; }
}
