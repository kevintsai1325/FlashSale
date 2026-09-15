package com.flashsale.admin.application.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemHealthServiceTest {

    @Test
    void optionalProbeFailureDoesNotMakeCoreOverallStatusDown() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.healthForPath(anyString())).thenReturn(Health.up().build());
        Instant now = Instant.parse("2026-08-16T03:00:00Z");
        HttpHealthProbe downProbe = mock(HttpHealthProbe.class);
        when(downProbe.check()).thenReturn(
            new HttpHealthProbe.Result(ServiceHealthStatus.DOWN, "狀態異常"));
        SystemHealthService service = new SystemHealthService(endpoint,
            Clock.fixed(now, ZoneOffset.UTC), List.of(
                // P5：兄弟服務也是 HTTP 探測。它們掛掉不該讓總體狀態變紅 ——
                // 這條測試守的就是那個分界。
                new SystemHealthService.NamedProbe("order-service", downProbe),
                new SystemHealthService.NamedProbe("Mailpit", downProbe),
                new SystemHealthService.NamedProbe("Zipkin", downProbe),
                new SystemHealthService.NamedProbe("Frontend", downProbe),
                new SystemHealthService.NamedProbe("Nginx", downProbe)));

        SystemHealthView result = service.snapshot();
        service.shutdown();

        assertThat(result.overallStatus()).isEqualTo(ServiceHealthStatus.UP);
        assertThat(result.checkedAt()).isEqualTo(now);
        // 3 個本地核心（Backend / PostgreSQL / Redis）加上 5 個探測。
        assertThat(result.services()).hasSize(8);
    }

    @Test
    void aCoreFailureMakesOverallStatusDown() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.healthForPath(anyString())).thenReturn(Health.up().build());
        when(endpoint.healthForPath("redis")).thenReturn(Health.down().build());
        HttpHealthProbe upProbe = mock(HttpHealthProbe.class);
        when(upProbe.check()).thenReturn(new HttpHealthProbe.Result(ServiceHealthStatus.UP, "可用"));
        SystemHealthService service = new SystemHealthService(endpoint, Clock.systemUTC(), List.of(
            new SystemHealthService.NamedProbe("Mailpit", upProbe)));

        SystemHealthView result = service.snapshot();
        service.shutdown();

        assertThat(result.overallStatus()).isEqualTo(ServiceHealthStatus.DOWN);
    }
}
