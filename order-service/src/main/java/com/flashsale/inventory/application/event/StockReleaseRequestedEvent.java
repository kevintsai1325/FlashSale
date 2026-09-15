package com.flashsale.inventory.application.event;

public record StockReleaseRequestedEvent(Long flashSaleId, int quantity) {}
