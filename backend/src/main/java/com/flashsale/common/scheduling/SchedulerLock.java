package com.flashsale.common.scheduling;

import java.time.Duration;

/**
 * 讓排程任務在多副本部署下只由一個副本執行。
 *
 * <p>存在的理由是一個實測到的正確性缺陷：2026-09-13 在三副本 k3s 上，沒有互斥保護的
 * {@code PaymentTimeoutScheduler} 讓 30 筆逾時訂單中有 27 筆被三個副本各處理一次，庫存
 * {@code available_quantity} 被回補到 87 而總庫存只有 30。見
 * {@code docs/portfolio/scheduler-duplication-evidence.md}。
 *
 * <p>有兩個實作，以 {@code app.scheduling.lock} 切換：
 * <ul>
 *   <li>{@code redisson}（預設）—— {@link RedissonSchedulerLock}，靠既有的 Redis。
 *   <li>{@code kubernetes} —— {@link KubernetesLeaseSchedulerLock}，靠 Kubernetes 的
 *       Lease API，不需要 Redis。作為故障模式的對照實驗，見
 *       {@code docs/portfolio/lock-mechanism-comparison.md}。
 * </ul>
 *
 * <p><b>不是所有互斥都需要這個。</b>{@code OutboxPublisher} 用
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 就安全了，同一次叢集實驗中它的 117 筆事件全部
 * 只發佈與消費一次。能用資料庫行鎖解決的「逐列取用」型工作，不該引入外部協調服務。
 */
public interface SchedulerLock {

    /**
     * 取得鎖就執行 {@code task}，取不到就跳過（不等待、不排隊）。
     *
     * <p>等待時間必須是 0：排隊會讓各副本輪流處理同一批資料，與互斥的目的相反。對「掃描一批、
     * 逐筆處理」型的排程來說，晚一輪執行沒有壞處，重複執行才有。
     *
     * @param lockName  鎖的名稱，實作會自行加上命名空間前綴
     * @param leaseTime 租約長度。持有者崩潰時，這就是損害的上界 —— 實測誤差在 0.4% 內，
     *                  見 {@code docs/portfolio/distributed-lock-failure-modes.md}。
     *                  必須明顯大於任務的最壞執行時間。
     * @param task      取得鎖後要執行的工作
     * @return 是否真的執行了 task
     */
    boolean runIfLocked(String lockName, Duration leaseTime, Runnable task);
}
