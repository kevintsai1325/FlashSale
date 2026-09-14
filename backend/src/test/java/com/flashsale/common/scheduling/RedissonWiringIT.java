package com.flashsale.common.scheduling;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redisson 必須連到 {@link AbstractIntegrationTest} 提供的那一個 Redis 容器，而不是自己去找
 * localhost:6379。接錯的話鎖會落在另一個 Redis 上，
 * {@link com.flashsale.order.application.PaymentTimeoutSchedulerConcurrencyIT} 那類併發測試
 * 會因為「兩條執行緒各自都取得了鎖」而假性通過 —— 正好是它要防的那個 bug。
 */
class RedissonWiringIT extends AbstractIntegrationTest {

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void redissonIsWiredToTheSharedRedisContainer() {
        assertThat(redissonClient).isNotNull();
        assertThat(redissonClient.isShutdown()).isFalse();

        redissonClient.getBucket("wiring-probe").set("ok");
        assertThat(redissonClient.getBucket("wiring-probe").get()).isEqualTo("ok");
    }

    @Test
    void lockIsActuallyMutuallyExclusiveAcrossClients() throws Exception {
        // 不只是「有 bean」而已：如果 Redisson 連到的 Redis 跟預期不同，或設定成了非叢集模式
        // 的本機假實作，這個斷言會失敗。第二次 tryLock 必須拿不到鎖。
        RLock first = redissonClient.getLock("scheduler:wiring-probe");
        assertThat(first.tryLock(0L, 30L, TimeUnit.SECONDS)).isTrue();
        try {
            Thread other = new Thread(() -> {
                RLock second = redissonClient.getLock("scheduler:wiring-probe");
                try {
                    assertThat(second.tryLock(0L, 30L, TimeUnit.SECONDS))
                        .as("另一個執行緒不該在鎖已被持有時取得它")
                        .isFalse();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            other.start();
            other.join(30_000);
            assertThat(other.isAlive()).isFalse();
        }
        finally {
            if (first.isHeldByCurrentThread()) {
                first.unlock();
            }
        }
    }
}
