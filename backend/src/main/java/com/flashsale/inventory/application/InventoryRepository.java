package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.Inventory;
import java.util.Optional;

public interface InventoryRepository {
    Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId);
    Inventory save(Inventory inventory);
}
