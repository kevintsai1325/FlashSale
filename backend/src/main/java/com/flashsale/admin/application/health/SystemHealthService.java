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
            @Value("${app.health.purchase-service-url:}") String purchaseServiceUrl,
            @Value("${app.health.order-service-url:}") String orderServiceUrl,
            @Value("${app.health.analytics-service-url:}") String analyticsServiceUrl,
            @Value("${app.health.mailpit-url:}") String mailpitUrl,
            @Value("${app.health.zipkin-url:}") String zipkinUrl,
            @Value("${app.health.frontend-url:}") String frontendUrl,
            @Value("${app.health.nginx-url:}") String nginxUrl) {
        this(healthEndpoint, Clock.systemUTC(), List.of(
            // P5：三個兄弟服務。它們是獨立的行程，只能用 HTTP 探測 ——
            // 拆分之後「系統健康」不再是單一個 actuator 端點答得出來的問題。
            new NamedProbe("purchase-service", new HttpHealthProbe(purchaseServiceUrl)),
            new NamedProbe("order-service", new HttpHealthProbe(orderServiceUrl)),
            new NamedProbe("analytics-service", new HttpHealthProbe(analyticsServiceUrl)),
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

        List<CompletableFuture<ServiceHealthView>> futures = probes.stream()
            .map(probe -> CompletableFuture.supplyAsync(() -> probe.check(checkedAt), probeExecutor))
            .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        futures.stream().map(CompletableFuture::join).forEach(services::add);

        // 「核心」只算 platform 自己的三項。兄弟服務掛掉確實會讓系統降級，但把它們算進
        // 總體狀態，會讓 analytics（一個純讀取的旁路）壞掉時整個儀表板變紅 ——
        // 那種警報很快就會被無視。它們各自的狀態在清單裡看得到。
        boolean coreDown = services.subList(0, 3).stream()
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
