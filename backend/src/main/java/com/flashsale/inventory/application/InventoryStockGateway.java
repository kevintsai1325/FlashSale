package com.flashsale.inventory.application;

import java.util.Optional;

public interface InventoryStockGateway {
    StockReservationResult reserve(Long flashSaleId, int quantity);
    void release(Long flashSaleId, int quantity);
    Optional<Integer> currentValue(Long flashSaleId);
    void resync(Long flashSaleId, int authoritativeQuantity);
}
