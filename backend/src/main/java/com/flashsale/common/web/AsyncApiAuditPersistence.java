package com.flashsale.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class AsyncApiAuditPersistence implements ApiAuditPersistence {
    private static final Logger logger = LoggerFactory.getLogger(AsyncApiAuditPersistence.class);
    private final ApiAuditLogJpaRepository repository;

    AsyncApiAuditPersistence(ApiAuditLogJpaRepository repository) { this.repository = repository; }

    @Override
    @Async
    public void submit(ApiAuditLog log, Runnable completion) {
        try {
            repository.save(log);
        } catch (Exception exception) {
            logger.error("Failed to write api audit log: method={} pathTemplate={} status={} userId={}",
                log.getMethod(), log.getPathTemplate(), log.getStatus(), log.getUserId(), exception);
        } finally {
            completion.run();
        }
    }
}
