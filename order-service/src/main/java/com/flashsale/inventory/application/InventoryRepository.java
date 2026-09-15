package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.Inventory;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository {
    Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId);
    Optional<Inventory> findByFlashSaleId(Long flashSaleId);
    Inventory save(Inventory inventory);

    // 批次查詢給 platform 的店面列表與後台儀表板用（見 InternalInventoryController）。
    List<Inventory> findAllByFlashSaleIdIn(List<Long> flashSaleIds);
    List<Inventory> findAll();
}
