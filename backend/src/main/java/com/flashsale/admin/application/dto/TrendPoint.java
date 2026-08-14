package com.flashsale.admin.application.dto;

import java.time.Instant;

public record TrendPoint(Instant bucketStart, long purchaseRequestCount, long orderCount) {}
