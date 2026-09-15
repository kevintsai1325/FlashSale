package com.flashsale.inventory.application;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.inventory.domain.Inventory;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class InventoryReconciliationScheduler {

    // 需掃描所有進行中的活動並逐一比對 Redis 與 Postgres；120 秒是任務間隔（60 秒）的兩倍。
    private static final Duration LOCK_LEASE = Duration.ofSeconds(120);

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationScheduler.class);

    private final PlatformFlashSaleClient platformFlashSaleClient;
    private final InventoryRepository inventoryRepository;
    private final InventoryStockGateway inventoryStockGateway;
    private final SchedulerLock schedulerLock;

    public InventoryReconciliationScheduler(PlatformFlashSaleClient platformFlashSaleClient,
                                             InventoryRepository inventoryRepository,
                                             InventoryStockGateway inventoryStockGateway,
                                             SchedulerLock schedulerLock) {
        this.platformFlashSaleClient = platformFlashSaleClient;
        this.inventoryRepository = inventoryRepository;
        this.inventoryStockGateway = inventoryStockGateway;
        this.schedulerLock = schedulerLock;
    }

    @Scheduled(fixedDelay = 60000)
    @Observed(name = "scheduler.reconcileActiveFlashSales")
    public void reconcileActiveFlashSales() {
        schedulerLock.runIfLocked("reconcileInventory", LOCK_LEASE, this::doReconcileActiveFlashSales);
    }

    /**
     * 「哪些活動正在進行」的判斷留在 platform（活動資料的所有權在那裡），這裡只拿 id 清單。
     *
     * 呼叫失敗時這一輪什麼都不做：對帳是修正性的工作，晚一分鐘做沒有損失，
     * 而拿不到清單時去猜「全部活動」會把已經結束的活動也重新種回 Redis。
     */
    private void doReconcileActiveFlashSales() {
        for (Long flashSaleId : platformFlashSaleClient.activeFlashSaleIds()) {
            inventoryRepository.findByFlashSaleId(flashSaleId).ifPresent(inventory ->
                reconcileOne(flashSaleId, inventory));
        }
    }

    private void reconcileOne(Long flashSaleId, Inventory inventory) {
        int authoritative = inventory.getAvailableQuantity();
        inventoryStockGateway.currentValue(flashSaleId).ifPresentOrElse(redisValue -> {
            if (!redisValue.equals(authoritative)) {
                log.warn("Inventory drift detected for flash sale {}: redis={} postgres={}, resyncing to postgres",
                    flashSaleId, redisValue, authoritative);
                inventoryStockGateway.resync(flashSaleId, authoritative);
            }
        }, () -> inventoryStockGateway.resync(flashSaleId, authoritative));
    }
}
