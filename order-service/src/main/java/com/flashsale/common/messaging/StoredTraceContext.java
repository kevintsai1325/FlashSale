package com.flashsale.common.messaging;

public record StoredTraceContext(int version, String traceId, String spanId, boolean sampled) {
}
