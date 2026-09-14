package com.flashsale.purchase.application;

/**
 * purchase-service 只需要「預扣」。釋放、對帳、重新同步留在 backend 的 inventory 模組 ——
 * 那些是庫存的所有者該做的事，不是搶購入口該做的事。
 */
public interface InventoryStockGateway {
    StockReservationResult reserve(Long flashSaleId, int quantity);
}
