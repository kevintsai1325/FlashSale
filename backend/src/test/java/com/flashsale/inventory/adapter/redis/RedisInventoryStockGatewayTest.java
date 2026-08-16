package com.flashsale.inventory.adapter.redis;

import com.flashsale.common.exception.ServiceUnavailableException;
import com.flashsale.common.metrics.PurchaseMetrics;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.inventory.domain.Inventory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisInventoryStockGatewayTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock RedisScript<Long> reserveStockScript;
    @Mock InventoryRepository inventoryRepository;
    @Mock ValueOperations<String, String> valueOperations;
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    PurchaseMetrics purchaseMetrics = new PurchaseMetrics(meterRegistry);

    @Test
    void throwsServiceUnavailableWhenLuaScriptReturnsMinusTwoBothAttempts() {
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);

        // Stub hasKey to return true so ensureSeeded is a no-op (doesn't try to load from DB)
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        // Stub execute to return -2L on every invocation (both initial and retry)
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString())).thenReturn(-2L);

        // Call reserve and expect ServiceUnavailableException
        assertThatThrownBy(() -> gateway.reserve(42L, 1))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasFieldOrPropertyWithValue("code", "STOCK_GATEWAY_UNAVAILABLE");

        // Verify that execute was called exactly twice (initial attempt + retry)
        verify(redisTemplate, times(2)).execute(eq(reserveStockScript), anyList(), anyString());
        assertThat(reservationCount("error")).isEqualTo(1.0);
        assertThat(reservationCount("reserved")).isZero();
        assertThat(reservationCount("insufficient_stock")).isZero();
        assertThat(meterRegistry.get("purchase.reservation.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void reservesSuccessfullyWhenLuaReturnsRemainderQuantity() {
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);

        // Stub hasKey to return true
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        // Stub execute to return 5 (meaning 5 units remain after reservation)
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString())).thenReturn(5L);

        StockReservationResult result = gateway.reserve(42L, 2);

        assertThat(result).isEqualTo(StockReservationResult.RESERVED);
        // Verify execute was called only once (no retry needed)
        verify(redisTemplate, times(1)).execute(eq(reserveStockScript), anyList(), anyString());
    }

    @Test
    void returnInsufficientStockWhenLuaReturnsMinusOne() {
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);

        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        // Lua script returns -1 when stock is insufficient
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString())).thenReturn(-1L);

        StockReservationResult result = gateway.reserve(42L, 10);

        assertThat(result).isEqualTo(StockReservationResult.INSUFFICIENT_STOCK);
    }

    @Test
    void retriesSeededOnceWhenScriptInitiallyReturnsMinusTwo() {
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);

        Inventory inventory = Inventory.initialize(42L, 5);
        when(inventoryRepository.findByFlashSaleId(42L)).thenReturn(Optional.of(inventory));

        // Mock opsForValue() for setIfAbsent call
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // hasKey returns false first time (so ensureSeeded loads from DB), then true on retry
        when(redisTemplate.hasKey("stock:42"))
            .thenReturn(false)   // First check in ensureSeeded: key doesn't exist
            .thenReturn(true);   // Second check in retry ensureSeeded: key was created

        // execute returns -2 on first attempt, then a valid remainder on retry
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString()))
            .thenReturn(-2L)  // First attempt returns -2
            .thenReturn(3L);  // Retry attempt returns 3 (2 units reserved from initial 5)

        StockReservationResult result = gateway.reserve(42L, 2);

        assertThat(result).isEqualTo(StockReservationResult.RESERVED);
        // Verify execute was called twice (initial failed with -2, then retry succeeded)
        verify(redisTemplate, times(2)).execute(eq(reserveStockScript), anyList(), anyString());
    }

    @Test
    void recordsOneErrorAndRethrowsTheOriginalRedisException() {
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(
            redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("down");
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> gateway.reserve(42L, 1)).isSameAs(failure);

        assertThat(reservationCount("error")).isEqualTo(1.0);
        assertThat(meterRegistry.get("purchase.reservation.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsOneErrorWhenLuaReturnsNull() {
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(
            redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> gateway.reserve(42L, 1))
            .isInstanceOf(ServiceUnavailableException.class);

        assertThat(reservationCount("error")).isEqualTo(1.0);
        assertThat(meterRegistry.get("purchase.reservation.latency").timer().count()).isEqualTo(1);
    }

    private double reservationCount(String outcome) {
        var counter = meterRegistry.find("purchase.reservation").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
