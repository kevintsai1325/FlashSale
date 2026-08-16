package com.flashsale.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists {@link ApiAuditLog} rows off the request thread. By the time this method runs the
 * request has already completed and responded, so a failed write can never fail the request it
 * is auditing — that part is automatic, not something coded defensively here. This method still
 * must not let the failure go unnoticed, so it is logged.
 */
@Component
public class ApiAuditWriter {

    private static final Logger logger = LoggerFactory.getLogger(ApiAuditWriter.class);

    private final ApiAuditPersistence persistence;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int pending;
    private Set<Long> suppressedUserIds = Set.of();
    private Set<String> suppressedTraceIds = Set.of();

    ApiAuditWriter(ApiAuditPersistence persistence) {
        this.persistence = persistence;
    }

    public void record(ApiAuditLog log) {
        lock.lock();
        try {
            Long userId = log.getUserId();
            String traceId = log.getTraceId();
            if ((userId != null && suppressedUserIds.contains(userId))
                || (traceId != null && suppressedTraceIds.contains(traceId))) return;
            pending++;
        } finally {
            lock.unlock();
        }
        try {
            persistence.submit(log, this::complete);
        } catch (RuntimeException exception) {
            complete();
            logger.error("Failed to submit api audit log", exception);
        }
    }

    public boolean beginDemoCleanup(Set<Long> userIds, Set<String> traceIds, Duration timeout) {
        lock.lock();
        try {
            suppressedUserIds = Set.copyOf(userIds);
            suppressedTraceIds = Set.copyOf(traceIds);
            boolean drained = awaitIdleLocked(timeout);
            if (!drained) {
                suppressedUserIds = Set.of();
                suppressedTraceIds = Set.of();
            }
            return drained;
        } finally { lock.unlock(); }
    }

    public void endDemoCleanup() {
        lock.lock();
        try { suppressedUserIds = Set.of(); suppressedTraceIds = Set.of(); }
        finally { lock.unlock(); }
    }

    public boolean awaitIdle(Duration timeout) {
        lock.lock();
        try { return awaitIdleLocked(timeout); }
        finally { lock.unlock(); }
    }

    private boolean awaitIdleLocked(Duration timeout) {
        long remaining = timeout.toNanos();
        try {
            while (pending > 0 && remaining > 0) remaining = idle.awaitNanos(remaining);
            return pending == 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void complete() {
        lock.lock();
        try {
            pending--;
            if (pending == 0) idle.signalAll();
        } finally { lock.unlock(); }
    }
}
