package com.flashsale.inventory.adapter.redis;

import com.flashsale.common.exception.ServiceUnavailableException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class RedisInventoryStockGateway implements InventoryStockGateway {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> reserveStockScript;
    private final InventoryRepository inventoryRepository;

    public RedisInventoryStockGateway(StringRedisTemplate redisTemplate, RedisScript<Long> reserveStockScript,
                                       InventoryRepository inventoryRepository) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = reserveStockScript;
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
                    "Inventory for flash sale " + flashSaleId + " does not exist"));
            redisTemplate.opsForValue().setIfAbsent(stockKey(flashSaleId), String.valueOf(available));
        }
    }

    @Override
    public StockReservationResult reserve(Long flashSaleId, int quantity) {
        ensureSeeded(flashSaleId);
        Long remaining = redisTemplate.execute(reserveStockScript, List.of(stockKey(flashSaleId)), String.valueOf(quantity));
        if (remaining != null && remaining == -2) {
            ensureSeeded(flashSaleId);
            remaining = redisTemplate.execute(reserveStockScript, List.of(stockKey(flashSaleId)), String.valueOf(quantity));
        }
        if (remaining == null || remaining == -2) {
            throw new ServiceUnavailableException("STOCK_GATEWAY_UNAVAILABLE",
                "Unable to reach Redis to reserve stock for flash sale " + flashSaleId);
        }
        return remaining == -1 ? StockReservationResult.INSUFFICIENT_STOCK : StockReservationResult.RESERVED;
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
