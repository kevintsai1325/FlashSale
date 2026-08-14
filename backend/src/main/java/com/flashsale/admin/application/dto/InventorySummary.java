package com.flashsale.admin.application.dto;

/**
 * Mirrors the fields already exposed by {@link com.flashsale.inventory.domain.Inventory}
 * (total/available/reserved/sold quantity) — one entry per flash sale.
 */
public record InventorySummary(
    int totalQuantity,
    int availableQuantity,
    int reservedQuantity,
    int soldQuantity
) {}
