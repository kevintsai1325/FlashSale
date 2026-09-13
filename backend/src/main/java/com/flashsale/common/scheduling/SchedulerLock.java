package com.flashsale.common.scheduling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 讓排程任務在多副本部署下只由一個副本執行。
 *
 * <p>存在的理由是一個實測到的正確性缺陷：2026-09-13 在三副本 k3s 上，沒有互斥保護的
 * {@code PaymentTimeoutScheduler} 讓 30 筆逾時訂單中有 27 筆被三個副本各處理一次，庫存
 * {@code available_quantity} 被回補到 87 而總庫存只有 30。見
 * {@code docs/portfolio/scheduler-duplication-evidence.md}。
 *
 * <p><b>等待時間固定為 0。</b>取不到鎖就跳過本次執行，不排隊。排隊會讓各副本輪流處理同一批
 * 資料，與互斥的目的相反 —— 對「掃描一批、逐筆處理」型的排程來說，晚一輪執行沒有壞處，
 * 重複執行才有。
 *
 * <p><b>不使用 Redisson 的看門狗自動續期。</b>租約到期後 Redis 會自動釋放鎖，持鎖節點崩潰時
 * 最多晚一輪就會有別的副本接手；看門狗會讓「崩潰後多久才釋放」變得不可預測。代價是任務執行
 * 時間若超過租約，鎖會被其他副本取得而形成雙重執行，因此租約必須明顯大於任務的最壞執行時間。
 *
 * <p><b>不是所有互斥都需要這個。</b>{@code OutboxPublisher} 用
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 就安全了，同一次叢集實驗中它的 117 筆事件全部
 * 只發佈與消費一次。能用資料庫行鎖解決的「逐列取用」型工作，不該引入外部協調服務。
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);
    private static final String KEY_PREFIX = "scheduler:";

    private final RedissonClient redissonClient;
    private final MeterRegistry meterRegistry;

    public SchedulerLock(RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.meterRegistry = meterRegistry;
    }

    // 沒有指標就無法知道鎖到底有沒有在發揮作用：acquired 與 skipped 的比例會顯示副本數，
    // 而 expired 只要大於 0 就代表租約設得太短、曾經有兩個副本同時執行。
    private void recordOutcome(String lockName, String outcome) {
        Counter.builder("scheduler.lock.outcome")
            .description("Outcome of a scheduler distributed-lock acquisition")
            .tag("lock", lockName)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    /**
     * 取得鎖就執行 {@code task}，取不到就跳過。
     *
     * @return 是否真的執行了 task
     */
    public boolean runIfLocked(String lockName, Duration leaseTime, Runnable task) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + lockName);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, leaseTime.toSeconds(), TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring scheduler lock {}", lockName);
            recordOutcome(lockName, "interrupted");
            return false;
        }
        if (!acquired) {
            log.debug("Scheduler lock {} is held by another instance; skipping this run", lockName);
            recordOutcome(lockName, "skipped");
            return false;
        }
        recordOutcome(lockName, "acquired");
        try {
            task.run();
            return true;
        }
        finally {
            // 租約到期後鎖可能已經屬於別的副本。無條件 unlock 會解掉別人的鎖，讓兩個副本同時
            // 持有 —— 那比不解鎖更危險。所以先確認仍由本執行緒持有。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            else {
                log.warn("Scheduler lock {} expired before the task finished; another instance may have run concurrently. "
                    + "Increase the lease time if this repeats.", lockName);
                recordOutcome(lockName, "expired");
            }
        }
    }
}
