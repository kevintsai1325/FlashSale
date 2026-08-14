package com.flashsale.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Persists {@link ApiAuditLog} rows off the request thread. By the time this method runs the
 * request has already completed and responded, so a failed write can never fail the request it
 * is auditing — that part is automatic, not something coded defensively here. This method still
 * must not let the failure go unnoticed, so it is logged.
 */
@Component
public class ApiAuditWriter {

    private static final Logger logger = LoggerFactory.getLogger(ApiAuditWriter.class);

    private final ApiAuditLogJpaRepository repository;

    public ApiAuditWriter(ApiAuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Async
    public void record(ApiAuditLog log) {
        try {
            repository.save(log);
        } catch (Exception e) {
            logger.error("Failed to write api audit log: method={} pathTemplate={} status={} userId={}",
                log.getMethod(), log.getPathTemplate(), log.getStatus(), log.getUserId(), e);
        }
    }
}
