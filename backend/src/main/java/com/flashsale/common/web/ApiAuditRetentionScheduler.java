package com.flashsale.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes {@code api_audit_logs} rows older than {@code app.audit.retention-days} (default 30),
 * once daily during off-peak hours.
 */
@Component
public class ApiAuditRetentionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ApiAuditRetentionScheduler.class);

    private final ApiAuditLogJpaRepository repository;
    private final int retentionDays;

    public ApiAuditRetentionScheduler(ApiAuditLogJpaRepository repository,
                                       @Value("${app.audit.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredLogs() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deleteByOccurredAtBefore(cutoff);
        logger.info("Purged {} expired api audit log rows older than {}", deleted, cutoff);
    }
}
