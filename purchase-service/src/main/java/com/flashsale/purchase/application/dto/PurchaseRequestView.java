package com.flashsale.purchase.application.dto;

import java.util.UUID;

public record PurchaseRequestView(UUID requestId, String status, Long orderId) {}
