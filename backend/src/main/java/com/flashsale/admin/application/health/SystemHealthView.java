package com.flashsale.admin.application.health;

import java.time.Instant;
import java.util.List;

public record SystemHealthView(
    ServiceHealthStatus overallStatus, Instant checkedAt, List<ServiceHealthView> services
) {
}
