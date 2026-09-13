package com.flashsale.inventory.application;

import com.flashsale.common.scheduling.SchedulerLock;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
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

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryStockGateway inventoryStockGateway;
    private final SchedulerLock schedulerLock;

    public InventoryReconciliationScheduler(FlashSaleRepository flashSaleRepository,
                                             InventoryRepository inventoryRepository,
                                             InventoryStockGateway inventoryStockGateway,
                                             SchedulerLock schedulerLock) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryStockGateway = inventoryStockGateway;
        this.schedulerLock = schedulerLock;
    }

    @Scheduled(fixedDelay = 60000)
    @Observed(name = "scheduler.reconcileActiveFlashSales")
    public void reconcileActiveFlashSales() {
        schedulerLock.runIfLocked("reconcileInventory", LOCK_LEASE, this::doReconcileActiveFlashSales);
    }

    private void doReconcileActiveFlashSales() {
        Instant now = Instant.now();
        for (FlashSale flashSale : flashSaleRepository.findAll()) {
            if (!flashSale.isPurchasableAt(now)) {
                continue;
            }
            inventoryRepository.findByFlashSaleId(flashSale.getId()).ifPresent(inventory ->
                reconcileOne(flashSale.getId(), inventory));
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
