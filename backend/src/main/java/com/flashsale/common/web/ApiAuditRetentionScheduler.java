package com.flashsale.common.web;

import com.flashsale.common.scheduling.SchedulerLock;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes {@code api_audit_logs} rows older than {@code app.audit.retention-days} (default 30),
 * once daily during off-peak hours.
 */
@Component
public class ApiAuditRetentionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ApiAuditRetentionScheduler.class);

    // 大量刪除可能耗時；每日只跑一次，租約長一點不影響任何事。
    private static final Duration LOCK_LEASE = Duration.ofSeconds(300);

    private final ApiAuditLogJpaRepository repository;
    private final SchedulerLock schedulerLock;
    private final int retentionDays;

    public ApiAuditRetentionScheduler(ApiAuditLogJpaRepository repository,
                                       SchedulerLock schedulerLock,
                                       @Value("${app.audit.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.schedulerLock = schedulerLock;
        this.retentionDays = retentionDays;
    }

    // 這裡沒有 @Transactional：刪除的交易由 ApiAuditLogJpaRepository.deleteByOccurredAtBefore
    // 自己帶。原因是實際工作現在透過 this::doPurgeExpiredLogs 的方法參考執行，那會繞過 Spring
    // 的代理，寫在這個方法上的 @Transactional 不會套用到內層。把交易放在 repository 那一側，
    // 也讓「取鎖」不會被包在一個開著的資料庫交易裡。
    @Scheduled(cron = "0 0 3 * * *")
    @Observed(name = "scheduler.purgeExpiredLogs")
    public void purgeExpiredLogs() {
        schedulerLock.runIfLocked("purgeExpiredAuditLogs", LOCK_LEASE, this::doPurgeExpiredLogs);
    }

    private void doPurgeExpiredLogs() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deleteByOccurredAtBefore(cutoff);
        logger.info("Purged {} expired api audit log rows older than {}", deleted, cutoff);
    }
}
