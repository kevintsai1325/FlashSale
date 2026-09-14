package com.flashsale.purchase.messaging;

public record StoredTraceContext(int version, String traceId, String spanId, boolean sampled) {
}
