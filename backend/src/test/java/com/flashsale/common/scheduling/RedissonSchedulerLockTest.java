package com.flashsale.common.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedissonSchedulerLockTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SchedulerLock schedulerLock = new RedissonSchedulerLock(redisson, meterRegistry);

    private double outcomeCount(String outcome) {
        return meterRegistry.find("scheduler.lock.outcome")
            .tag("lock", "demo").tag("outcome", outcome)
            .counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void runsTheTaskAndReleasesTheLockWhenAcquired() throws Exception {
        when(redisson.getLock("scheduler:demo")).thenReturn(lock);
        when(lock.tryLock(0L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = schedulerLock.runIfLocked("demo", Duration.ofSeconds(30), () -> ran.set(true));

        assertThat(executed).isTrue();
        assertThat(ran).isTrue();
        verify(lock, times(1)).unlock();
        assertThat(outcomeCount("acquired")).isEqualTo(1.0);
    }

    @Test
    void skipsTheTaskWithoutWaitingWhenTheLockIsHeldElsewhere() throws Exception {
        // 等待時間必須是 0：排隊會讓各副本輪流處理同一批資料，與互斥的目的相反。
        when(redisson.getLock("scheduler:demo")).thenReturn(lock);
        when(lock.tryLock(0L, 30L, TimeUnit.SECONDS)).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = schedulerLock.runIfLocked("demo", Duration.ofSeconds(30), () -> ran.set(true));

        assertThat(executed).isFalse();
        assertThat(ran).isFalse();
        verify(lock, never()).unlock();
        assertThat(outcomeCount("skipped")).as("取不到鎖必須被記錄，否則無從得知鎖有沒有在發揮作用").isEqualTo(1.0);
    }

    @Test
    void releasesTheLockEvenWhenTheTaskThrows() throws Exception {
        when(redisson.getLock("scheduler:demo")).thenReturn(lock);
        when(lock.tryLock(0L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> schedulerLock.runIfLocked("demo", Duration.ofSeconds(30), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(lock, times(1)).unlock();
    }

    @Test
    void doesNotUnlockALockThisThreadNoLongerHolds() throws Exception {
        // 租約到期後鎖可能已被其他副本取得。此時呼叫 unlock 會解掉別人的鎖，
        // 造成兩個副本同時持有 —— 比不解鎖更糟。
        when(redisson.getLock("scheduler:demo")).thenReturn(lock);
        when(lock.tryLock(0L, 1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        boolean executed = schedulerLock.runIfLocked("demo", Duration.ofSeconds(1), () -> { });

        assertThat(executed).isTrue();
        verify(lock, never()).unlock();
        assertThat(outcomeCount("expired"))
            .as("租約在任務結束前就到期，代表可能有兩個副本同時執行過；這必須是可觀測的")
            .isEqualTo(1.0);
    }

    @Test
    void prefixesLockNamesSoTheyCannotCollideWithOtherRedisKeys() throws Exception {
        when(redisson.getLock("scheduler:expireOverduePayments")).thenReturn(lock);
        when(lock.tryLock(0L, 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        schedulerLock.runIfLocked("expireOverduePayments", Duration.ofSeconds(60), () -> { });

        verify(redisson, times(1)).getLock("scheduler:expireOverduePayments");
    }
}
