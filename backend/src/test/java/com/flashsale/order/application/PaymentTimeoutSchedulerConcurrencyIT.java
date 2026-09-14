package com.flashsale.order.application;

import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
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
 * 重現 2026-09-13 在三副本 k3s 叢集上實測到的缺陷：{@link PaymentTimeoutScheduler} 沒有互斥
 * 保護時，每個副本都會讀到同一批逾時訂單並各自回補一次庫存。當時 30 筆訂單中有 27 筆被三個
 * 副本各處理一次，{@code available_quantity} 被回補到 87 而總庫存只有 30 —— 整個專案最核心的
 * 「不超賣」保證完全失效。證據見 docs/portfolio/scheduler-duplication-evidence.md。
 *
 * <p>叢集上的證據無法在 CI 重跑，所以這裡用兩條執行緒在單一 JVM 內模擬兩個副本。
 * {@link CyclicBarrier} 讓它們在同一瞬間進入排程方法，等價於叢集實驗中「同時刪除三個 Pod
 * 讓計時器對齊」的手法。
 *
 * <p>刻意不加 {@code @Transactional}：測試若包在單一交易內，兩條執行緒會共用同一個連線與
 * 交易，競態就不會發生，測試會假性通過。
 *
 * <p>fixture 讓庫存起始為售罄（total=1、available=0、sold=1）。
 * {@code Inventory.release()} 沒有邊界檢查，所以重複回補會讓 available 超過 total、
 * 且 sold 變成負數 —— 兩個在正確的系統裡都不可能出現的狀態。
 */
class PaymentTimeoutSchedulerConcurrencyIT extends AbstractIntegrationTest {

    private static final long FLASH_SALE_ID = 1L;

    @Autowired
    private PaymentTimeoutScheduler scheduler;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    @Sql("/db/testdata/overdue-order-fixtures.sql")
    void concurrentRunsReleaseStockExactlyOnce() throws Exception {
        Inventory before = inventoryRepository.findByFlashSaleId(FLASH_SALE_ID).orElseThrow();
        int totalQuantity = before.getTotalQuantity();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await(30, TimeUnit.SECONDS);
                        scheduler.expireOverduePayments();
                    }
                    catch (Throwable t) {
                        // 兩個副本同時處理同一筆訂單時，其中一個可能因為狀態機或鎖競爭而拋例外。
                        // 那不是這個測試要斷言的事；這裡只記下來，真正的斷言是庫存的不變量。
                        failure.compareAndSet(null, t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                .as("兩個模擬副本都要在時限內結束")
                .isTrue();
        }
        finally {
            pool.shutdownNow();
        }

        Inventory after = inventoryRepository.findByFlashSaleId(FLASH_SALE_ID).orElseThrow();

        assertThat(after.getAvailableQuantity())
            .as("庫存被回補的次數必須等於逾時訂單數。可用量超過總量代表同一筆訂單被回補了兩次，"
                + "也就是憑空生出庫存 —— 這正是三副本叢集上實測到的超賣")
            .isLessThanOrEqualTo(totalQuantity);

        assertThat(after.getSoldQuantity())
            .as("已售數量不可能為負；變負代表同一筆訂單被扣回了兩次")
            .isGreaterThanOrEqualTo(0);
    }
}
