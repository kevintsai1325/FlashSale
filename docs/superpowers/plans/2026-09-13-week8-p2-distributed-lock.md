# Week 8 P2：分散式鎖 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓四個沒有互斥保護的 `@Scheduled` 排程在多副本下只執行一次，修復 P1 實測到的庫存超賣缺陷，並把分散式鎖的故障模式實際量過一遍。

**Architecture:** 以 Redisson 的 `RLock` 實作一層極薄的 `SchedulerLock` 抽象，四個排程各自包一層。取鎖等待時間為 0：拿不到就跳過本次執行，不排隊。另以 Kubernetes Lease API 實作一份 leader election 做故障模式對照，不取代主線方案。

**Tech Stack:** Spring Boot 3.3 / Java 21、Redisson、既有的 Redis 7、Testcontainers、Micrometer。

**Spec:** `docs/superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md`

**前置證據:** `docs/portfolio/scheduler-duplication-evidence.md` —— P1 已實測：三副本下 30 筆訂單有 27 筆被處理三次，`available_quantity` 被回補到 87 而總庫存只有 30。

## Global Constraints

- 不改變任何排程任務的業務語意。這一輪只加互斥，不調整補償邏輯、不改狀態機。
- 取鎖一律使用等待時間 0（`tryLock(0, leaseTime, TimeUnit.SECONDS)`）。排隊等待會讓各副本輪流重複處理同一批資料，與互斥的目的相反。
- `OutboxPublisher` **不加鎖**。它的 `FOR UPDATE SKIP LOCKED` 已在 P1 實測證明安全（117 筆事件、117 筆已發佈、消費紀錄無重複），加鎖只會把每秒兩次的發佈變慢。
- 整合測試沿用 `com.flashsale.testsupport.AbstractIntegrationTest`：Postgres、Redis、RabbitMQ 三個 Testcontainer 為整個測試 JVM 共用的 static 欄位，子類別不要自行宣告 `@Container`，否則會讓 Spring context 快取失效並重啟容器。
- `integration-test` profile 已設定 `app.scheduling.enabled: false`，排程不會自動觸發；測試一律直接呼叫排程方法。
- Redis 目前是單副本 StatefulSet。它故障時所有排程都會停擺，這個取捨要量測並寫入文件，不要假裝不存在。

---

### Task 1: 加入 Redisson 並確認它接到既有的 Redis

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/test/java/com/flashsale/common/scheduling/RedissonWiringIT.java`

**Interfaces:**
- Consumes: 既有的 `spring.data.redis.host` / `spring.data.redis.port` 設定
- Produces: 可注入的 `org.redisson.api.RedissonClient` bean。Task 3 依賴它。

- [ ] **Step 1: 寫失敗測試**

Create `backend/src/test/java/com/flashsale/common/scheduling/RedissonWiringIT.java`：

```java
package com.flashsale.common.scheduling;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redisson 必須連到 AbstractIntegrationTest 提供的同一個 Redis 容器，而不是自己另外找一個
 * localhost:6379。接錯 Redis 的話，鎖在測試裡會「永遠取得成功」，讓 Task 2 的併發測試
 * 假性通過。
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
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.scheduling.RedissonWiringIT"`
Expected: FAIL，找不到 `RedissonClient` 型別或無此 bean

- [ ] **Step 3: 加入相依**

在 `backend/build.gradle.kts` 的 `implementation("org.springframework.boot:spring-boot-starter-data-redis")` 之後加入：

```kotlin
    implementation("org.redisson:redisson-spring-boot-starter:3.35.0")
```

`redisson-spring-boot-starter` 會讀取 `spring.data.redis.*` 自動設定，因此不需要額外的 Redisson
設定檔。若版本與 Spring Boot 3.3 不相容而啟動失敗，改用 Redisson 官方相容表中對應 3.3.x 的版本，
並把實際採用的版本與原因記在工作記錄裡。

- [ ] **Step 4: 執行測試確認通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.scheduling.RedissonWiringIT"`
Expected: PASS

- [ ] **Step 5: 確認沒有破壞既有測試**

Run: `cd backend && ./gradlew test`
Expected: 全部通過。特別注意 `ActuatorHealthIT`：Redisson 會新增自己的健康檢查指標，
若健康檢查群組的斷言變嚴格而失敗，把 Redisson 的 health indicator 關掉
（`management.health.redisson.enabled: false`），不要放寬既有斷言。

- [ ] **Step 6: Commit**

```bash
git add backend/build.gradle.kts backend/src/test/java/com/flashsale/common/scheduling/RedissonWiringIT.java
git commit -m "build: 加入 Redisson，接到既有的 Redis"
```

---

### Task 2: 寫出重現庫存超賣的失敗測試

這是 P2 的核心。P1 的證據是在叢集上取得的，但修復必須由一個可在 CI 重跑的測試來保護，
否則下次有人加新排程時同樣的缺陷會再回來。

**Files:**
- Create: `backend/src/test/java/com/flashsale/order/application/PaymentTimeoutSchedulerConcurrencyIT.java`

**Interfaces:**
- Consumes: `PaymentTimeoutScheduler.expireOverduePayments()`、`InventoryRepository`
- Produces: 一個在無鎖狀態下必定失敗的測試。Task 3 讓它轉綠。

- [ ] **Step 1: 寫失敗測試**

Create `backend/src/test/java/com/flashsale/order/application/PaymentTimeoutSchedulerConcurrencyIT.java`：

```java
package com.flashsale.order.application;

import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重現 P1 在三副本叢集上實測到的缺陷：PaymentTimeoutScheduler 沒有互斥保護時，
 * 每個副本都會讀到同一批逾時訂單並各自回補一次庫存，導致 available_quantity 超過
 * total_quantity —— 也就是超賣。
 *
 * <p>兩條執行緒模擬兩個副本。用 CyclicBarrier 讓它們在同一瞬間進入排程方法，
 * 這正是 P1 中「同時刪除三個 Pod 讓計時器對齊」在單一 JVM 內的等價做法。
 *
 * <p>不加 {@code @Transactional}：測試若包在單一交易內，兩條執行緒會共用同一個連線與交易，
 * 競態就不會發生，測試會假性通過。
 */
class PaymentTimeoutSchedulerConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentTimeoutScheduler scheduler;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    @Sql("/db/testdata/overdue-order-fixtures.sql")
    void concurrentRunsReleaseStockExactlyOnce() throws Exception {
        long flashSaleId = 1L;
        int totalQuantity = inventoryRepository.findByFlashSaleId(flashSaleId).orElseThrow().getTotalQuantity();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await(10, TimeUnit.SECONDS);
                        scheduler.expireOverduePayments();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).isNull();

        int available = inventoryRepository.findByFlashSaleId(flashSaleId).orElseThrow().getAvailableQuantity();
        assertThat(available)
            .as("庫存被回補的次數必須等於逾時訂單數，不能因為多個副本各跑一次而超過總庫存")
            .isLessThanOrEqualTo(totalQuantity);
    }
}
```

- [ ] **Step 2: 建立測試資料**

Create `backend/src/test/resources/db/testdata/overdue-order-fixtures.sql`，內容需要建立：
一個 `ACTIVE` 的搶購活動（id 1）、對應的 `inventory`（`total_quantity` 與
`available_quantity` 皆為 0，代表已售完）、一筆 `PENDING_PAYMENT` 且
`payment_due_at` 已過期的 `orders` 資料列、其 `order_items`、以及一筆連結到該訂單的
`purchase_requests`（`compensate()` 會以 `findByOrderId` 查它，查不到會拋
`IllegalStateException`）。

請比照 `backend/src/test/resources/db/testdata/` 下既有 fixture 的欄位與寫法，
不要自行發明欄位名稱。

- [ ] **Step 3: 執行測試確認失敗**

Run: `cd backend && ./gradlew test --tests "*PaymentTimeoutSchedulerConcurrencyIT"`
Expected: FAIL，`available` 為 2 而 `totalQuantity` 為 1（或等比例的數字），
即庫存被回補了兩次。

**若測試意外通過**：代表兩條執行緒沒有真正重疊。檢查 `findPendingPaymentPastDue` 是否
在第一條執行緒提交後才被第二條讀取。可在 `OrderCompensationService.compensate` 的
`inventory.release` 之前加一個測試專用的延遲來擴大競態窗口 —— 但**這個延遲不可留在
正式程式碼中**，確認競態存在後就移除，改以 `CyclicBarrier` 的時序達成。

- [ ] **Step 4: Commit（紅燈狀態也要提交）**

```bash
git add backend/src/test/java/com/flashsale/order/application/PaymentTimeoutSchedulerConcurrencyIT.java backend/src/test/resources/db/testdata/overdue-order-fixtures.sql
git commit -m "test: 重現多副本下逾時排程重複回補庫存的缺陷"
```

提交紅燈測試是刻意的：它讓「缺陷存在」這件事本身進入版本歷史，Task 3 的 commit 才能
顯示出修復前後的對照。

---

### Task 3: 實作 SchedulerLock 並套用到 PaymentTimeoutScheduler

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/scheduling/SchedulerLock.java`
- Create: `backend/src/test/java/com/flashsale/common/scheduling/SchedulerLockTest.java`
- Modify: `backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java`

**Interfaces:**
- Consumes: `RedissonClient`（Task 1）
- Produces: `SchedulerLock.runIfLocked(String lockName, Duration leaseTime, Runnable task)`，回傳 `boolean`（是否實際執行）。Task 4 的三個排程共用這個介面。

- [ ] **Step 1: 寫單元測試**

Create `backend/src/test/java/com/flashsale/common/scheduling/SchedulerLockTest.java`：

```java
package com.flashsale.common.scheduling;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLockTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final SchedulerLock schedulerLock = new SchedulerLock(redisson);

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
    }

    @Test
    void skipsTheTaskWithoutWaitingWhenTheLockIsHeldElsewhere() throws Exception {
        when(redisson.getLock("scheduler:demo")).thenReturn(lock);
        when(lock.tryLock(0L, 30L, TimeUnit.SECONDS)).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean executed = schedulerLock.runIfLocked("demo", Duration.ofSeconds(30), () -> ran.set(true));

        assertThat(executed).isFalse();
        assertThat(ran).isFalse();
        verify(lock, never()).unlock();
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
        // 租約到期後鎖可能已被其他節點取得。此時呼叫 unlock 會解掉別人的鎖，
        // 造成兩個節點同時持有 —— 比不解鎖更糟。
        when(redisson.getLock("scheduler:demo")).thenReturn(lock);
        when(lock.tryLock(0L, 1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        schedulerLock.runIfLocked("demo", Duration.ofSeconds(1), () -> { });

        verify(lock, never()).unlock();
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `cd backend && ./gradlew test --tests "*SchedulerLockTest"`
Expected: FAIL，`SchedulerLock` 不存在

- [ ] **Step 3: 實作 SchedulerLock**

Create `backend/src/main/java/com/flashsale/common/scheduling/SchedulerLock.java`：

```java
package com.flashsale.common.scheduling;

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
 * <p>等待時間固定為 0：取不到鎖就跳過本次執行，不排隊。排隊會讓各副本輪流處理同一批資料，
 * 與互斥的目的相反 —— 對「掃描一批、逐筆處理」型的排程來說，晚一輪執行沒有壞處，
 * 重複執行才有。
 *
 * <p>租約（leaseTime）到期後 Redis 會自動釋放鎖，避免持鎖節點崩潰造成永久死鎖。代價是
 * 任務執行時間若超過租約，鎖會被其他副本取得而形成雙重執行；因此租約必須明顯大於任務的
 * 最壞執行時間。這裡刻意不使用 Redisson 的看門狗自動續期：續期會讓崩潰後的鎖釋放延遲
 * 變得不可預測，而這些排程任務本來就允許晚一輪執行。
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);
    private static final String KEY_PREFIX = "scheduler:";

    private final RedissonClient redissonClient;

    public SchedulerLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public boolean runIfLocked(String lockName, Duration leaseTime, Runnable task) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + lockName);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, leaseTime.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring scheduler lock {}", lockName);
            return false;
        }
        if (!acquired) {
            log.debug("Scheduler lock {} is held by another instance; skipping this run", lockName);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            // 租約到期後鎖可能已經屬於別的節點。解掉別人的鎖會讓兩個節點同時持有，
            // 比不解鎖更危險，所以先確認仍由本執行緒持有。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                log.warn("Scheduler lock {} expired before the task finished; another instance may have run concurrently", lockName);
            }
        }
    }
}
```

- [ ] **Step 4: 執行單元測試確認通過**

Run: `cd backend && ./gradlew test --tests "*SchedulerLockTest"`
Expected: PASS

- [ ] **Step 5: 套用到 PaymentTimeoutScheduler**

把 `backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java` 改為：

```java
package com.flashsale.order.application;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.order.domain.Order;
import io.micrometer.observation.annotation.Observed;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class PaymentTimeoutScheduler {

    // 租約必須大於一整批逾時訂單的處理時間。P1 實測 30 筆訂單在數十毫秒內處理完，
    // 60 秒留了非常寬裕的餘裕；同時它小於任務間隔的兩倍，持鎖節點崩潰後最多晚一輪恢復。
    private static final Duration LOCK_LEASE = Duration.ofSeconds(60);

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;
    private final SchedulerLock schedulerLock;

    public PaymentTimeoutScheduler(OrderRepository orderRepository,
                                   OrderCompensationService compensationService,
                                   SchedulerLock schedulerLock) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
        this.schedulerLock = schedulerLock;
    }

    @Scheduled(fixedDelay = 30000)
    @Observed(name = "scheduler.expireOverduePayments")
    public void expireOverduePayments() {
        schedulerLock.runIfLocked("expireOverduePayments", LOCK_LEASE, this::doExpireOverduePayments);
    }

    private void doExpireOverduePayments() {
        List<Order> overdue = orderRepository.findPendingPaymentPastDue(Instant.now());
        for (Order order : overdue) {
            compensationService.markExpired(order);
        }
    }
}
```

- [ ] **Step 6: 執行 Task 2 的併發測試確認轉綠**

Run: `cd backend && ./gradlew test --tests "*PaymentTimeoutSchedulerConcurrencyIT"`
Expected: PASS。`available` 不再超過 `totalQuantity`。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flashsale/common/scheduling/SchedulerLock.java backend/src/test/java/com/flashsale/common/scheduling/SchedulerLockTest.java backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java
git commit -m "fix: 逾時排程加上分散式鎖，修復多副本下的庫存超賣"
```

---

### Task 4: 套用到其餘三個排程

**Files:**
- Modify: `backend/src/main/java/com/flashsale/notification/application/NotificationRetryScheduler.java`
- Modify: `backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java`
- Modify: `backend/src/main/java/com/flashsale/common/web/ApiAuditRetentionScheduler.java`
- Modify: `backend/src/test/java/com/flashsale/inventory/application/InventoryReconciliationSchedulerIT.java`

**Interfaces:**
- Consumes: `SchedulerLock.runIfLocked`（Task 3）
- Produces: 四個排程全部具備互斥保護

- [ ] **Step 1: 逐一套用相同模式**

三個排程都採用與 `PaymentTimeoutScheduler` 完全相同的結構：把原本的方法內容改名為
`doXxx()` 私有方法，公開的 `@Scheduled` 方法只負責呼叫 `schedulerLock.runIfLocked(...)`。

鎖名稱與租約：

| 排程 | 鎖名稱 | 租約 | 理由 |
|---|---|---|---|
| `NotificationRetryScheduler` | `retryFailedNotifications` | 120 秒 | 重試會實際發送郵件，SMTP 往返可能較慢 |
| `InventoryReconciliationScheduler` | `reconcileInventory` | 120 秒 | 需掃描所有進行中的活動並比對 Redis 與 DB |
| `ApiAuditRetentionScheduler` | `purgeExpiredAuditLogs` | 300 秒 | 大量刪除可能耗時；每日僅一次，租約長不影響 |

- [ ] **Step 2: 讓既有測試維持通過**

`InventoryReconciliationSchedulerIT` 直接呼叫排程方法，包上鎖之後仍會取得鎖並執行，
理論上不需要修改。執行確認：

Run: `cd backend && ./gradlew test --tests "*InventoryReconciliationSchedulerIT"`
Expected: PASS。若因為測試間鎖未釋放而失敗，在測試的 `@AfterEach` 中清掉
`scheduler:*` 鍵，不要延長租約。

- [ ] **Step 3: 執行完整測試套件**

Run: `cd backend && ./gradlew test`
Expected: 全部通過

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/flashsale/notification/application/NotificationRetryScheduler.java backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java backend/src/main/java/com/flashsale/common/web/ApiAuditRetentionScheduler.java
git commit -m "fix: 其餘三個排程任務加上分散式鎖"
```

---

### Task 5: 鎖的觀測性

沒有指標就無法知道鎖到底有沒有在發揮作用，也無法在 P6 的大屏上呈現。

**Files:**
- Modify: `backend/src/main/java/com/flashsale/common/scheduling/SchedulerLock.java`
- Create: `backend/src/test/java/com/flashsale/common/scheduling/SchedulerLockMetricsTest.java`

**Interfaces:**
- Consumes: `MeterRegistry`（專案已使用 Micrometer，見 `common/metrics/PurchaseMetrics.java`）
- Produces: `scheduler.lock.outcome` 計數器，標籤為 `lock`（鎖名稱）與 `outcome`（`acquired` / `skipped` / `expired`）

- [ ] **Step 1: 寫測試**

驗證三件事：取得鎖時 `outcome=acquired` 加一；取不到時 `outcome=skipped` 加一；
任務結束時鎖已不屬於本執行緒則 `outcome=expired` 加一。比照
`backend/src/main/java/com/flashsale/common/metrics/PurchaseMetrics.java` 既有的指標寫法。

- [ ] **Step 2: 執行確認失敗，實作，再確認通過**

Run: `cd backend && ./gradlew test --tests "*SchedulerLockMetricsTest"`

- [ ] **Step 3: Commit**

```bash
git commit -am "feat: SchedulerLock 加上取得/略過/逾期的計數指標"
```

---

### Task 6: 在叢集上重跑 P1 的重複執行實驗

這是修復是否有效的唯一證明。P1 的證據文件已寫明本實驗必須能重跑並得到正確結果。

**Files:**
- Modify: `docs/portfolio/scheduler-duplication-evidence.md`

- [ ] **Step 1: 重建映像並部署**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\build-local.ps1
$env:FL_K3S_POSTGRES_PASSWORD = 'flashsale-local-pg'
$env:FL_K3S_RABBITMQ_PASSWORD = 'flashsale-local-mq'
$env:FL_K3S_JWT_PRIVATE_KEY_PATH = 'C:\SideProject\flashsale-secrets\jwt-private.pem'
$env:FL_K3S_JWT_PUBLIC_KEY_PATH = 'C:\SideProject\flashsale-secrets\jwt-public.pem'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\deploy.ps1
```

- [ ] **Step 2: 完整重跑 P1 的實驗步驟**

依 `docs/portfolio/scheduler-duplication-evidence.md` 最後一節「重現步驟」執行全部五步。

- [ ] **Step 3: 確認結果**

Expected：
- 每筆訂單的 `EXPIRED` 狀態歷史**恰好一筆**（P1 時為 27 筆訂單各三筆）
- `available_quantity` 等於逾時訂單的數量，**不超過 `total_quantity`**（P1 時為 87 對 30）
- `StockReleaseRequested` 事件數等於訂單數（P1 時為 87 對 30）

- [ ] **Step 4: 把修復後的數據加入證據文件**

在 `scheduler-duplication-evidence.md` 新增「修復後驗證」一節，與修復前的表格並列呈現。
保留修復前的數據，不要刪除 —— 對照本身就是這份文件的價值。

- [ ] **Step 5: Commit**

```bash
git add docs/portfolio/scheduler-duplication-evidence.md
git commit -m "docs: 補上分散式鎖修復後的實測驗證"
```

---

### Task 7: 深水區 —— 四項故障模式實測

規格要求把分散式鎖的故障模式實際量過，而不是只讓功能可用。

**Files:**
- Create: `docs/portfolio/distributed-lock-failure-modes.md`

- [ ] **Step 1: 租約到期導致雙重執行**

把某個排程的租約暫時改為 1 秒，並在該排程內插入 3 秒延遲，部署後觀察兩個副本是否同時執行，
以及 `scheduler.lock.outcome{outcome="expired"}` 是否增加。記錄結果後**還原修改**。

- [ ] **Step 2: 持鎖節點強制刪除後的鎖釋放時間**

在排程執行中以 `kubectl delete pod <持鎖的 Pod> --force --grace-period=0` 強制刪除，
量測從刪除到其他副本成功取得鎖之間的時間。預期約等於剩餘租約時間。

- [ ] **Step 3: Redis 故障時的行為**

`kubectl -n flashsale scale statefulset/redis --replicas=0`，觀察：排程是否全部停止、
是否產生大量錯誤日誌、backend 的 readiness 是否受影響、恢復 Redis 後是否自動復原。
**這一項預期會暴露一個真實風險**：Redis 是單副本，它掛掉時所有排程都停擺。

- [ ] **Step 4: fencing token**

說明為什麼「鎖過期但舊節點仍在執行」無法只靠鎖解決，以及 fencing token 如何讓下游拒絕
過期請求。本專案目前**不實作** fencing token，理由是 `compensate()` 的下游是同一個資料庫，
可用樂觀鎖（在 `Order` 加上 `@Version`）達成等價效果且成本更低。把這個取捨寫清楚，
並在文件中標註「若日後補償動作要呼叫外部系統，fencing token 就變成必要」。

- [ ] **Step 5: 寫成文件並 commit**

---

### Task 8: Kubernetes Lease 對照實作

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/scheduling/KubernetesLeaseLock.java`
- Create: `docs/portfolio/lock-mechanism-comparison.md`

- [ ] **Step 1: 實作 Lease 版 leader election**

以 Kubernetes 的 `coordination.k8s.io/v1` Lease 資源實作，套用在同一組排程上（以設定切換，
預設仍為 Redisson）。需要為 backend 的 ServiceAccount 加上 Lease 的 RBAC 權限。

- [ ] **Step 2: 對同一組故障情境跑一次 Task 7 的實驗**

- [ ] **Step 3: 產出對照表**

至少涵蓋：鎖釋放延遲、對外部元件的依賴、Redis/etcd 故障時的行為、實作複雜度、
以及「什麼情況下該選哪一個」的結論。

---

## 完成標準

1. 四個排程任務全部具備互斥保護，`OutboxPublisher` 維持不加鎖。
2. `PaymentTimeoutSchedulerConcurrencyIT` 在 CI 中通過，且刻意移除鎖之後會失敗。
3. 叢集上重跑 P1 的重複執行實驗，得到「每筆訂單恰好一次、`available_quantity` 不超過
   `total_quantity`」。
4. 四項故障模式皆有實測數據與文件。
5. Redisson 與 Kubernetes Lease 的對照表完成，並給出選擇建議。
6. `backend` 完整測試套件通過。
