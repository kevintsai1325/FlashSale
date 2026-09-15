package com.flashsale.order.application.dto;

import java.math.BigDecimal;

public record OrderItemView(Long productId, String productName, int quantity, BigDecimal unitPrice) {}
