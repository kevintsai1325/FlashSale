package com.flashsale.order.adapter.web.dto;

import jakarta.validation.constraints.Positive;

public record PurchaseCreateRequest(@Positive int quantity) {}
