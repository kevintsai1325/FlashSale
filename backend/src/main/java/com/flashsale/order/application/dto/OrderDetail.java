package com.flashsale.order.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderDetail(Long id, String orderNo, BigDecimal totalAmount, String status, Instant paymentDueAt) {}
