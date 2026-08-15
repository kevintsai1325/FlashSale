package com.flashsale.order.adapter.web.dto;

import jakarta.validation.constraints.Positive;

public record PurchaseCreateRequest(@Positive(message = "數量必須大於 0") int quantity) {}
