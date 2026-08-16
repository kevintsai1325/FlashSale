package com.flashsale.admin.application.health;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SystemHealthService {

    private final HealthEndpoint healthEndpoint;
    private final Clock clock;
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(4);
    private final List<NamedProbe> probes;

    @Autowired
    public SystemHealthService(
            HealthEndpoint healthEndpoint,
            @Value("${app.health.mailpit-url:}") String mailpitUrl,
            @Value("${app.health.zipkin-url:}") String zipkinUrl,
            @Value("${app.health.frontend-url:}") String frontendUrl,
            @Value("${app.health.nginx-url:}") String nginxUrl) {
        this(healthEndpoint, Clock.systemUTC(), List.of(
            new NamedProbe("Mailpit", new HttpHealthProbe(mailpitUrl)),
            new NamedProbe("Zipkin", new HttpHealthProbe(zipkinUrl)),
            new NamedProbe("Frontend", new HttpHealthProbe(frontendUrl)),
            new NamedProbe("Nginx", new HttpHealthProbe(nginxUrl))));
    }

    SystemHealthService(HealthEndpoint healthEndpoint, Clock clock, List<NamedProbe> probes) {
        this.healthEndpoint = healthEndpoint;
        this.clock = clock;
        this.probes = probes;
    }

    public SystemHealthView snapshot() {
        Instant checkedAt = clock.instant();
        List<ServiceHealthView> services = new ArrayList<>();
        services.add(core("Backend", "readinessState", checkedAt));
        services.add(core("PostgreSQL", "db", checkedAt));
        services.add(core("Redis", "redis", checkedAt));
        services.add(core("RabbitMQ", "rabbit", checkedAt));

        List<CompletableFuture<ServiceHealthView>> futures = probes.stream()
            .map(probe -> CompletableFuture.supplyAsync(() -> probe.check(checkedAt), probeExecutor))
            .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        futures.stream().map(CompletableFuture::join).forEach(services::add);

        boolean coreDown = services.subList(0, 4).stream()
            .anyMatch(service -> service.status() != ServiceHealthStatus.UP);
        return new SystemHealthView(coreDown ? ServiceHealthStatus.DOWN : ServiceHealthStatus.UP,
            checkedAt, List.copyOf(services));
    }

    private ServiceHealthView core(String name, String path, Instant checkedAt) {
        try {
            HealthComponent health = healthEndpoint.healthForPath(path);
            ServiceHealthStatus status = health != null && "UP".equals(health.getStatus().getCode())
                ? ServiceHealthStatus.UP : ServiceHealthStatus.DOWN;
            return new ServiceHealthView(name, status, checkedAt,
                status == ServiceHealthStatus.UP ? "可用" : "狀態異常");
        } catch (RuntimeException exception) {
            return new ServiceHealthView(name, ServiceHealthStatus.UNKNOWN, checkedAt, "無法連線");
        }
    }

    @PreDestroy
    void shutdown() {
        probeExecutor.shutdownNow();
    }

    record NamedProbe(String name, HttpHealthProbe probe) {
        ServiceHealthView check(Instant checkedAt) {
            HttpHealthProbe.Result result = probe.check();
            return new ServiceHealthView(name, result.status(), checkedAt, result.reason());
        }
    }
}
