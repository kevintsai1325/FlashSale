package com.flashsale.inventory.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * purchase-service 只在 Redis 的庫存鍵不存在時才呼叫這支 —— 用來種入初始值。
 * 呼叫端刻意不快取這個回應：種入一個過期的可售數量會直接造成超賣或漏賣。
 */
@RestController
public class InternalInventoryController {

    private final InventoryRepository inventoryRepository;

    public InternalInventoryController(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @GetMapping("/internal/flash-sales/{id}/available-quantity")
    public AvailableQuantityResponse get(@PathVariable("id") Long flashSaleId) {
        Inventory inventory = inventoryRepository.findByFlashSaleId(flashSaleId)
            .orElseThrow(() -> new NotFoundException("INVENTORY_NOT_FOUND",
                "搶購活動 " + flashSaleId + " 的庫存資料不存在"));
        return new AvailableQuantityResponse(flashSaleId, inventory.getAvailableQuantity());
    }

    public record AvailableQuantityResponse(Long flashSaleId, int availableQuantity) {}
}
