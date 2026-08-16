package com.flashsale.common.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void beginDemoCleanupBlocksOnRealInFlightWritesFromConcurrentThreads() throws Exception {
        int writeCount = 20;
        CountDownLatch allSubmitted = new CountDownLatch(writeCount);
        List<Runnable> completions = Collections.synchronizedList(new ArrayList<>());
        ApiAuditWriter writer = new ApiAuditWriter((log, completion) -> {
            completions.add(completion);
            allSubmitted.countDown();
        });

        ExecutorService writers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < writeCount; i++) {
            writers.submit(() -> writer.record(auditLog()));
        }
        assertThat(allSubmitted.await(5, TimeUnit.SECONDS)).isTrue();
        writers.shutdown();

        // beginDemoCleanup runs on its own thread since it must block until every one of the
        // writeCount writes above (submitted from real, separate threads) actually completes.
        ExecutorService barrierRunner = Executors.newSingleThreadExecutor();
        Future<Boolean> drained = barrierRunner.submit(() ->
            writer.beginDemoCleanup(Set.of(42L), Set.of("trace"), Duration.ofSeconds(5)));

        Thread.sleep(50);
        assertThat(drained.isDone()).isFalse();

        completions.forEach(Runnable::run);

        assertThat(drained.get(5, TimeUnit.SECONDS)).isTrue();
        writer.endDemoCleanup();
        barrierRunner.shutdown();
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
