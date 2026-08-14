package com.flashsale.inventory.application;

import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.domain.Inventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class InventoryReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationScheduler.class);

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryStockGateway inventoryStockGateway;

    public InventoryReconciliationScheduler(FlashSaleRepository flashSaleRepository, InventoryRepository inventoryRepository,
                                             InventoryStockGateway inventoryStockGateway) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryStockGateway = inventoryStockGateway;
    }

    @Scheduled(fixedDelay = 60000)
    public void reconcileActiveFlashSales() {
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
