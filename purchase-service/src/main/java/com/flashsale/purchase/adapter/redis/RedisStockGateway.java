package com.flashsale.purchase.adapter.redis;

import com.flashsale.purchase.application.FlashSaleClient;
import com.flashsale.purchase.application.InventoryStockGateway;
import com.flashsale.purchase.application.StockReservationResult;
import com.flashsale.purchase.exception.ServiceUnavailableException;
import com.flashsale.purchase.metrics.PurchaseMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 預扣邏輯與 Lua 腳本與拆分前逐字相同 —— 這是正確性的核心，搬家不是重寫它的時機。
 *
 * 唯一的差別是種入初始值的來源：拆分前直接讀 Postgres 的 inventories 表，
 * 現在改成向 backend 要。purchase-service 只碰自己的兩張表（purchase_requests、outbox_events），
 * 這件事在步驟 2 拆庫時才不用重做。
 */
@Component
public class RedisStockGateway implements InventoryStockGateway {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> reserveStockScript;
    private final FlashSaleClient flashSaleClient;
    private final PurchaseMetrics purchaseMetrics;

    public RedisStockGateway(StringRedisTemplate redisTemplate, RedisScript<Long> reserveStockScript,
                              FlashSaleClient flashSaleClient, PurchaseMetrics purchaseMetrics) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = reserveStockScript;
        this.flashSaleClient = flashSaleClient;
        this.purchaseMetrics = purchaseMetrics;
    }

    private String stockKey(Long flashSaleId) {
        return "stock:" + flashSaleId;
    }

    private void ensureSeeded(Long flashSaleId) {
        if (Boolean.FALSE.equals(redisTemplate.hasKey(stockKey(flashSaleId)))) {
            int available = flashSaleClient.availableQuantity(flashSaleId);
            redisTemplate.opsForValue().setIfAbsent(stockKey(flashSaleId), String.valueOf(available));
        }
    }

    @Override
    public StockReservationResult reserve(Long flashSaleId, int quantity) {
        Timer.Sample sample = purchaseMetrics.startReservationTimer();
        boolean outcomeRecorded = false;
        try {
            ensureSeeded(flashSaleId);
            Long remaining = redisTemplate.execute(reserveStockScript, List.of(stockKey(flashSaleId)), String.valueOf(quantity));
            if (remaining != null && remaining == -2) {
                ensureSeeded(flashSaleId);
                remaining = redisTemplate.execute(reserveStockScript, List.of(stockKey(flashSaleId)), String.valueOf(quantity));
            }
            if (remaining == null || remaining == -2) {
                throw new ServiceUnavailableException("STOCK_GATEWAY_UNAVAILABLE",
                    "無法連線至庫存服務，請稍後再試（活動 " + flashSaleId + "）");
            }
            StockReservationResult result = remaining == -1 ? StockReservationResult.INSUFFICIENT_STOCK : StockReservationResult.RESERVED;
            purchaseMetrics.recordReservationOutcome(result == StockReservationResult.RESERVED ? "reserved" : "insufficient_stock");
            outcomeRecorded = true;
            return result;
        } catch (RuntimeException exception) {
            if (!outcomeRecorded) {
                purchaseMetrics.recordReservationOutcome("error");
            }
            throw exception;
        } finally {
            purchaseMetrics.stopReservationTimer(sample);
        }
    }
}
