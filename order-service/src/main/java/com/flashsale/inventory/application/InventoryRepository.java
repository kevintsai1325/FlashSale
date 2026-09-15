package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.Inventory;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository {
    Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId);
    Optional<Inventory> findByFlashSaleId(Long flashSaleId);
    Inventory save(Inventory inventory);

    // Admin dashboard aggregate (com.flashsale.admin).
    List<Inventory> findAll();
}
