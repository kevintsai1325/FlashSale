package com.flashsale.inventory.adapter.redis;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * P5：這裡**不做預扣**。預扣（那段 Lua）在 purchase-service —— 它是搶購入口的一部分。
 * 這個服務對同一個 Redis 計數器做的是「補償時回補」與「對帳時校正」，
 * 兩者都是單純的 INCRBY / SET，不需要腳本的原子性。
 */
@Component
public class RedisInventoryStockGateway implements InventoryStockGateway {

    private final StringRedisTemplate redisTemplate;
    private final InventoryRepository inventoryRepository;

    public RedisInventoryStockGateway(StringRedisTemplate redisTemplate, InventoryRepository inventoryRepository) {
        this.redisTemplate = redisTemplate;
        this.inventoryRepository = inventoryRepository;
    }

    private String stockKey(Long flashSaleId) {
        return "stock:" + flashSaleId;
    }

    private void ensureSeeded(Long flashSaleId) {
        if (Boolean.FALSE.equals(redisTemplate.hasKey(stockKey(flashSaleId)))) {
            int available = inventoryRepository.findByFlashSaleId(flashSaleId)
                .map(Inventory::getAvailableQuantity)
                .orElseThrow(() -> new NotFoundException("INVENTORY_NOT_FOUND",
                    "搶購活動 " + flashSaleId + " 的庫存資料不存在"));
            redisTemplate.opsForValue().setIfAbsent(stockKey(flashSaleId), String.valueOf(available));
        }
    }

    @Override
    public void release(Long flashSaleId, int quantity) {
        redisTemplate.opsForValue().increment(stockKey(flashSaleId), quantity);
    }

    @Override
    public Optional<Integer> currentValue(Long flashSaleId) {
        String value = redisTemplate.opsForValue().get(stockKey(flashSaleId));
        return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
    }

    @Override
    public void resync(Long flashSaleId, int authoritativeQuantity) {
        redisTemplate.opsForValue().set(stockKey(flashSaleId), String.valueOf(authoritativeQuantity));
    }
}
