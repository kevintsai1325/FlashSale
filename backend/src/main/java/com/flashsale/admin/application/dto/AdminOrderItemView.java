package com.flashsale.admin.application.dto;

import java.math.BigDecimal;

public record AdminOrderItemView(Long productId, int quantity, BigDecimal unitPrice) {}
