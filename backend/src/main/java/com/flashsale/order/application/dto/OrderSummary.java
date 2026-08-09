package com.flashsale.order.application.dto;

import java.math.BigDecimal;

public record OrderSummary(Long id, String orderNo, BigDecimal totalAmount, String status) {}
