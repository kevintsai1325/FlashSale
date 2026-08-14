package com.flashsale.admin.application.dto;

import java.time.Instant;

public record OrderStatusHistoryView(String fromStatus, String toStatus, Instant changedAt) {}
