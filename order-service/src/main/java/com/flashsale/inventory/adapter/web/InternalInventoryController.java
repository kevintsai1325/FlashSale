package com.flashsale.inventory.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.common.exception.ConflictException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 庫存的所有權在這個服務（P5），所以想知道數量的人都要問這裡。
 *
 * 兩個呼叫端、兩種形狀：
 * <ul>
 *   <li>purchase-service 只在 Redis 的庫存鍵不存在時問單一活動 —— 用來種入初始值。
 *       它刻意不快取這個回應：種入一個過期的可售數量會直接造成超賣或漏賣。</li>
 *   <li>platform 的店面列表與後台儀表板一次要問很多活動，所以有批次版本。
 *       **沒有批次版本的話那兩個畫面就是 N+1 次跨服務呼叫** —— 這是把庫存搬進
 *       order-service 之後最直接的代價，而不是一個可以視而不見的細節。</li>
 * </ul>
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

    /**
     * 批次查詢。查不到的活動不會出現在回應裡 —— 呼叫端自己決定要顯示 0 還是「未知」，
     * 這裡不替它決定。
     */
    @GetMapping("/internal/inventories")
    public Map<Long, InventoryView> batch(@RequestParam("flashSaleIds") List<Long> flashSaleIds) {
        Map<Long, InventoryView> result = new LinkedHashMap<>();
        for (Inventory inventory : inventoryRepository.findAllByFlashSaleIdIn(flashSaleIds)) {
            result.put(inventory.getFlashSaleId(), view(inventory));
        }
        return result;
    }

    /**
     * 後台建立或調整活動時宣告庫存（P5）。
     *
     * **只有還沒有人買過的活動可以被重設。** 已經有預扣或售出的活動改總量，會讓
     * available/reserved/sold 三者的和對不上，而那是超賣與漏賣的溫床。platform 那邊
     * 也有同樣的檢查（活動開始後不准改總量），這裡再擋一次 —— 跨服務的驗證不能只放在呼叫端。
     *
     * 冪等：同樣的宣告送兩次，第二次什麼都不會改變。呼叫端因此可以安全重試。
     */
    @PutMapping("/internal/inventories/{flashSaleId}")
    @Transactional
    public InventoryView declare(@PathVariable("flashSaleId") Long flashSaleId,
                                  @RequestBody DeclareInventoryRequest request) {
        Inventory inventory = inventoryRepository.findByFlashSaleId(flashSaleId)
            .orElse(null);
        if (inventory == null) {
            inventory = inventoryRepository.save(Inventory.initialize(flashSaleId, request.totalQuantity()));
            return view(inventory);
        }
        if (inventory.getTotalQuantity() != request.totalQuantity()) {
            if (inventory.getReservedQuantity() != 0 || inventory.getSoldQuantity() != 0) {
                throw new ConflictException("INVENTORY_ALREADY_IN_USE",
                    "搶購活動 " + flashSaleId + " 已經有預扣或售出的庫存，總量不能再改");
            }
            inventory.resetTo(request.totalQuantity());
            inventoryRepository.save(inventory);
        }
        return view(inventory);
    }

    private static InventoryView view(Inventory inventory) {
        return new InventoryView(inventory.getTotalQuantity(), inventory.getAvailableQuantity(),
            inventory.getReservedQuantity(), inventory.getSoldQuantity());
    }

    public record DeclareInventoryRequest(int totalQuantity) {}

    public record AvailableQuantityResponse(Long flashSaleId, int availableQuantity) {}

    public record InventoryView(int totalQuantity, int availableQuantity, int reservedQuantity, int soldQuantity) {}
}
