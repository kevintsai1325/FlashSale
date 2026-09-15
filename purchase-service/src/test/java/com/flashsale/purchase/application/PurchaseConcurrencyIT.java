package com.flashsale.purchase.application;

import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import com.flashsale.purchase.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * P4 步驟 1 刪掉的 PurchaseConcurrencyIT 的等值替代。
 *
 * 守的是「**Redis 預扣永遠不會超發**」：真的開多條執行緒同時搶同一批庫存，
 * 成功的筆數必須剛好等於庫存，一筆不多。這個不變量的實作是 reserve-stock.lua 的原子性，
 * 而 Lua 的原子性只有在真的 Redis 上才成立 —— 用假的 gateway 測不出來。
 *
 * 注意這裡守的**不是**「不超賣訂單」：訂單在另一個服務，由它的
 * OrderPurchaseConsumerConcurrencyIT（庫存列鎖）守。兩層防線各自有測試，
 * 兩層一起看的那個端到端不變量由 k6 壓測守。
 */
class PurchaseConcurrencyIT extends AbstractIntegrationTest {

    private static final int STOCK = 3;
    private static final int BUYERS = 12;
    private static final long FLASH_SALE_ID = 1L;

    @Autowired CreatePurchaseRequestService service;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubTheFlashSale() {
        Instant now = Instant.now();
        when(flashSaleClient.fetch(anyLong())).thenReturn(new FlashSaleSnapshot(
            FLASH_SALE_ID, 7L, new BigDecimal("9.99"),
            now.minus(1, ChronoUnit.MINUTES), now.plus(1, ChronoUnit.HOURS), 1));
        when(flashSaleClient.availableQuantity(anyLong())).thenReturn(STOCK);
    }

    @Test
    void neverReservesMoreThanTheStockUnderRealConcurrentContention() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(BUYERS);
        CyclicBarrier barrier = new CyclicBarrier(BUYERS);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < BUYERS; i++) {
            long userId = 100L + i;
            tasks.add(() -> {
                // 在這裡等齊，讓預扣盡可能同時發生；否則執行緒會不小心排成序列，
                // 根本碰不到競爭，測試就變成什麼都沒證明。
                barrier.await(10, TimeUnit.SECONDS);
                service.createPurchaseRequest(userId, FLASH_SALE_ID, "key-" + userId, 1);
                return true;
            });
        }

        try {
            for (var future : executor.invokeAll(tasks)) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        Integer pending = jdbcTemplate.queryForObject(
            "select count(*) from purchase_requests where status = ?", Integer.class, PurchaseRequestStatus.PENDING.name());
        Integer soldOut = jdbcTemplate.queryForObject(
            "select count(*) from purchase_requests where status = ?", Integer.class, PurchaseRequestStatus.SOLD_OUT.name());

        assertThat(pending).isEqualTo(STOCK);
        assertThat(soldOut).isEqualTo(BUYERS - STOCK);

        // 預扣成功幾筆就寫幾筆建單請求，不多不少。
        Integer outboxEvents = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'CreateOrderRequested'", Integer.class);
        assertThat(outboxEvents).isEqualTo(STOCK);
    }
}
