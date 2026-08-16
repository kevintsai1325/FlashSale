package com.flashsale.common.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAuditWriterTest {

    @Test
    void drainWaitsUntilEverySubmittedWriteCompletes() {
        RecordingPersistence persistence = new RecordingPersistence();
        ApiAuditWriter writer = new ApiAuditWriter(persistence);

        writer.record(auditLog());

        assertThat(writer.awaitIdle(Duration.ofMillis(10))).isFalse();
        persistence.completeAll();
        assertThat(writer.awaitIdle(Duration.ofMillis(10))).isTrue();
    }

    @Test
    void rejectedSubmissionDoesNotLeaveDrainPermanentlyBusy() {
        ApiAuditWriter writer = new ApiAuditWriter((log, completion) -> {
            throw new IllegalStateException("executor rejected submission");
        });

        writer.record(auditLog());

        assertThat(writer.awaitIdle(Duration.ofMillis(10))).isTrue();
    }

    @Test
    void cleanupBarrierSuppressesMatchingWriteAfterEarlierRowsReachZero() {
        RecordingPersistence persistence = new RecordingPersistence();
        ApiAuditWriter writer = new ApiAuditWriter(persistence);

        assertThat(writer.beginDemoCleanup(Set.of(42L), Set.of("trace"), Duration.ofMillis(10))).isTrue();
        writer.record(auditLog());

        assertThat(persistence.submissionCount()).isZero();
        assertThat(writer.awaitIdle(Duration.ofMillis(10))).isTrue();
        writer.endDemoCleanup();
    }

    @Test
    void recordingAnAnonymousLogDuringCleanupBarrierDoesNotThrow() {
        RecordingPersistence persistence = new RecordingPersistence();
        ApiAuditWriter writer = new ApiAuditWriter(persistence);

        assertThat(writer.beginDemoCleanup(Set.of(42L), Set.of("suppressed-trace"), Duration.ofMillis(10))).isTrue();

        writer.record(anonymousAuditLog());

        assertThat(persistence.submissionCount()).isEqualTo(1);
        writer.endDemoCleanup();
    }

    private ApiAuditLog auditLog() {
        return new ApiAuditLog("GET", "/api/orders/me", 200, 42L,
            "trace", "trace", 1, "127.0.0.1", "test", null);
    }

    private ApiAuditLog anonymousAuditLog() {
        return new ApiAuditLog("GET", "/api/flash-sales", 200, null,
            "other-trace", "other-trace", 1, "127.0.0.1", "test", null);
    }

    private static final class RecordingPersistence implements ApiAuditPersistence {
        private final List<Runnable> completions = new ArrayList<>();

        @Override
        public void submit(ApiAuditLog log, Runnable completion) {
            completions.add(completion);
        }

        void completeAll() {
            completions.forEach(Runnable::run);
            completions.clear();
        }

        int submissionCount() { return completions.size(); }
    }
}
