package com.flashsale.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    @Test
    void sellsRequestedQuantityWhenStockAvailable() {
        Inventory inventory = Inventory.initialize(1L, 10);

        inventory.sell(3);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(7);
        assertThat(inventory.getSoldQuantity()).isEqualTo(3);
    }

    @Test
    void throwsWhenAvailableQuantityIsBelowRequestedQuantity() {
        Inventory inventory = Inventory.initialize(1L, 2);

        assertThatThrownBy(() -> inventory.sell(3)).isInstanceOf(IllegalStateException.class);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
        assertThat(inventory.getSoldQuantity()).isEqualTo(0);
    }

    @Test
    void hasStockIsFalseWhenAvailableQuantityIsBelowRequestedQuantity() {
        Inventory inventory = Inventory.initialize(1L, 2);

        assertThat(inventory.hasStock(3)).isFalse();
        assertThat(inventory.hasStock(2)).isTrue();
    }

    @Test
    void releaseAddsQuantityBackToAvailableAndRemovesFromSold() {
        Inventory inventory = Inventory.initialize(1L, 10);
        inventory.sell(3);

        inventory.release(3);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getSoldQuantity()).isEqualTo(0);
    }

    @Test
    void releaseIsTheExactInverseOfSell() {
        Inventory inventory = Inventory.initialize(1L, 5);
        inventory.sell(5);

        inventory.release(2);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
        assertThat(inventory.getSoldQuantity()).isEqualTo(3);
    }
}
