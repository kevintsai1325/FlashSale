package com.flashsale.inventory.application;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P5：「哪些活動正在進行」由 platform 回答，所以那個 client 在這裡是 mock 的。
 *
 * **這是拆分後整合測試的邊界**：一個服務的整合測試只能涵蓋到自己的行程邊緣。
 * 這條測試守的是「發現漂移就以 Postgres 為準覆寫 Redis」，而那整段邏輯都在這個服務裡。
 */
@Sql("/db/testdata/inventory-fixtures.sql")
class InventoryReconciliationSchedulerIT extends AbstractIntegrationTest {

    @Autowired InventoryStockGateway inventoryStockGateway;
    @Autowired InventoryReconciliationScheduler scheduler;

    @MockBean PlatformFlashSaleClient platformFlashSaleClient;

    @BeforeEach
    void stubActiveFlashSales() {
        when(platformFlashSaleClient.activeFlashSaleIds()).thenReturn(List.of(1L));
    }

    @Test
    void driftedRedisValueIsResyncedFromPostgres() {
        // inventory-fixtures.sql seeds flash sale 1 with available_quantity=1. Force Redis to
        // drift away from that authoritative value without going through the gateway's own
        // release/resync path (simulating e.g. a Redis restart that lost the real count).
        inventoryStockGateway.resync(1L, 999);
        assertThat(inventoryStockGateway.currentValue(1L)).contains(999);

        scheduler.reconcileActiveFlashSales();

        assertThat(inventoryStockGateway.currentValue(1L)).contains(1);
    }
}
