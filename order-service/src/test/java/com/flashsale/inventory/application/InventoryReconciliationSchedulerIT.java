package com.flashsale.inventory.application;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@Sql("/db/testdata/inventory-fixtures.sql")
class InventoryReconciliationSchedulerIT extends AbstractIntegrationTest {

    @Autowired InventoryStockGateway inventoryStockGateway;
    @Autowired InventoryReconciliationScheduler scheduler;

    @Test
    void driftedRedisValueIsResyncedFromPostgres() {
        // inventory-fixtures.sql seeds flash sale 1 with available_quantity=1. Force Redis to
        // drift away from that authoritative value without going through the gateway's own
        // reserve/release (simulating e.g. a Redis restart that lost the real count).
        inventoryStockGateway.resync(1L, 999);
        assertThat(inventoryStockGateway.currentValue(1L)).contains(999);

        scheduler.reconcileActiveFlashSales();

        assertThat(inventoryStockGateway.currentValue(1L)).contains(1);
    }
}
