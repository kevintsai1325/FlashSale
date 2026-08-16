package com.flashsale.admin.application.health;

import java.time.Instant;

public record ServiceHealthView(
    String name, ServiceHealthStatus status, Instant checkedAt, String reason
) {
}
