package com.flashsale.common.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * {@link SchedulerLock} 的 Kubernetes Lease 實作，作為 {@link RedissonSchedulerLock} 的對照。
 *
 * <p><b>為什麼不用 Kubernetes Java client：</b>Lease 只是一個平凡的 REST 資源，
 * 整個 leader election 的邏輯就是「讀 → 判斷是否過期 → 帶著 resourceVersion 寫回」。
 * 為了這件事引入官方 client（連同它的十幾個傳遞相依）會讓映像肥一大圈，卻把最值得看懂的
 * 部分藏進函式庫裡。這裡用 JDK 內建的 {@link HttpClient} 直接呼叫 API，總共不到一百行。
 *
 * <p><b>互斥靠的是 API server 的樂觀併發控制。</b>更新 Lease 時必須帶上讀到的
 * {@code resourceVersion}；若期間有別的副本改過，API server 回 409 Conflict，本次取鎖失敗。
 * 這與 Redisson 用 Lua 腳本在 Redis 端做原子操作是完全不同的機制，但達成同一件事。
 *
 * <p><b>租約到期靠計算而非 TTL。</b>Redis 的鎖鍵有 TTL，時間到自己消失；Lease 不會自己消失，
 * 它只是一筆記著 {@code renewTime} 與 {@code leaseDurationSeconds} 的資料。判斷「上一個持有者
 * 是否已經失聯」是**讀取方**的責任：若 {@code now > renewTime + leaseDurationSeconds} 就視為
 * 可搶。這代表 Lease 的過期判定依賴各副本自己的時鐘 —— 在時鐘會往回跳的環境（本專案的
 * WSL2 VM 實測每約 30 秒回跳 1.5 秒）是個真實的風險，詳見
 * {@code docs/portfolio/lock-mechanism-comparison.md}。
 *
 * <p>需要 RBAC 權限：對 {@code coordination.k8s.io/leases} 的 get、create、update。
 * 見 {@code k8s/base/rbac.yaml}。
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.lock", havingValue = "kubernetes")
public class KubernetesLeaseSchedulerLock implements SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(KubernetesLeaseSchedulerLock.class);
    private static final Path SA_ROOT = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final String NAME_PREFIX = "scheduler-";

    // 每個 Pod 一個身分。Lease 的 holderIdentity 記的就是它，因此從 kubectl 直接看得出
    // 現在是誰持有 —— 這是 Lease 相對於 Redisson 的一個實際優勢：Redisson 的鎖 hash 只記
    // <客戶端UUID>:<執行緒ID>，無法對應回 Pod。
    private final String identity = System.getenv().getOrDefault("HOSTNAME", "unknown-" + UUID.randomUUID());

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry;
    private final String namespace;
    private final String token;
    private final String apiBase;

    public KubernetesLeaseSchedulerLock(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.namespace = readServiceAccountFile("namespace", "default");
        this.token = readServiceAccountFile("token", "");
        this.httpClient = buildHttpClient();
        String host = System.getenv().getOrDefault("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc");
        String port = System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443");
        this.apiBase = "https://" + host + ":" + port + "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases/";
        log.info("Kubernetes Lease scheduler lock active: namespace={} identity={}", namespace, identity);
    }

    /**
     * API server 用的是叢集自己的 CA 簽出來的憑證，JVM 的預設信任庫不認得它。
     * 掛載在 Pod 裡的 ca.crt 就是那張 CA —— 把它載進一個只含這張憑證的 TrustManager，
     * 就能正常做 TLS 驗證。**不要用「信任一切」的 TrustManager 去繞過這件事**：
     * 那會讓任何能攔截流量的人冒充 API server，而我們正拿著 ServiceAccount token 在對它說話。
     */
    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        try {
            byte[] caBytes = Files.readAllBytes(SA_ROOT.resolve("ca.crt"));
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(caBytes));
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("kubernetes-ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            builder.sslContext(sslContext);
        }
        catch (Exception e) {
            // 叢集外沒有這個檔案。保留預設的 SSLContext，實際呼叫時會失敗並被記錄。
            log.warn("Could not load the in-cluster CA certificate; Lease API calls will fail outside Kubernetes: {}", e.toString());
        }
        return builder.build();
    }

    private static String readServiceAccountFile(String name, String fallback) {
        try {
            return Files.readString(SA_ROOT.resolve(name)).trim();
        }
        catch (IOException e) {
            // 在叢集外（例如本機整合測試）跑不到這些檔案。這個實作本來就只在叢集內有意義，
            // 因此回傳 fallback 讓 bean 仍能建立，實際呼叫時會失敗並記錄。
            return fallback;
        }
    }

    private void recordOutcome(String lockName, String outcome) {
        Counter.builder("scheduler.lock.outcome")
            .description("Outcome of a scheduler distributed-lock acquisition")
            .tag("lock", lockName)
            .tag("outcome", outcome)
            .tag("mechanism", "kubernetes-lease")
            .register(meterRegistry)
            .increment();
    }

    @Override
    public boolean runIfLocked(String lockName, Duration leaseTime, Runnable task) {
        String leaseName = NAME_PREFIX + lockName.toLowerCase();
        Instant now = Instant.now();
        JsonNode current;
        try {
            current = get(leaseName);
        }
        catch (Exception e) {
            log.warn("Could not read Lease {}: {}", leaseName, e.toString());
            recordOutcome(lockName, "error");
            return false;
        }

        if (current != null && !isExpired(current, now)) {
            String holder = current.path("spec").path("holderIdentity").asText("");
            if (!identity.equals(holder)) {
                log.debug("Lease {} still held by {}; skipping this run", leaseName, holder);
                recordOutcome(lockName, "skipped");
                return false;
            }
        }

        boolean acquired;
        try {
            acquired = (current == null) ? create(leaseName, leaseTime, now) : update(leaseName, leaseTime, now, current);
        }
        catch (Exception e) {
            log.warn("Could not acquire Lease {}: {}", leaseName, e.toString());
            recordOutcome(lockName, "error");
            return false;
        }
        if (!acquired) {
            // 409 Conflict：期間有別的副本改過這筆 Lease。API server 的樂觀併發控制就是互斥。
            recordOutcome(lockName, "skipped");
            return false;
        }

        recordOutcome(lockName, "acquired");
        try {
            task.run();
            return true;
        }
        finally {
            // 不主動釋放。Lease 沒有「刪除即釋放」的語意，下一個持有者是靠「renewTime 加上
            // leaseDurationSeconds 已過」來判斷可搶。主動把 renewTime 往回寫反而會製造混亂。
            // 代價：任務結束後鎖仍名義上被持有到租約結束，下一輪會由同一個副本續約。
        }
    }

    private boolean isExpired(JsonNode lease, Instant now) {
        String renewTime = lease.path("spec").path("renewTime").asText("");
        int durationSeconds = lease.path("spec").path("leaseDurationSeconds").asInt(0);
        if (renewTime.isEmpty() || durationSeconds <= 0) {
            return true;
        }
        try {
            return now.isAfter(Instant.parse(renewTime).plusSeconds(durationSeconds));
        }
        catch (Exception e) {
            return true;
        }
    }

    private JsonNode get(String leaseName) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(apiBase + leaseName)).GET());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET lease returned " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private boolean create(String leaseName, Duration leaseTime, Instant now) throws Exception {
        String body = """
            {"apiVersion":"coordination.k8s.io/v1","kind":"Lease",
             "metadata":{"name":"%s","namespace":"%s"},
             "spec":{"holderIdentity":"%s","leaseDurationSeconds":%d,"acquireTime":"%s","renewTime":"%s"}}
            """.formatted(leaseName, namespace, identity, leaseTime.toSeconds(), iso(now), iso(now));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(apiBase))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        // 409 = 別的副本在同一瞬間建立了同一筆 Lease。那就是它贏了。
        return response.statusCode() == 201;
    }

    private boolean update(String leaseName, Duration leaseTime, Instant now, JsonNode current) throws Exception {
        String resourceVersion = current.path("metadata").path("resourceVersion").asText();
        String body = """
            {"apiVersion":"coordination.k8s.io/v1","kind":"Lease",
             "metadata":{"name":"%s","namespace":"%s","resourceVersion":"%s"},
             "spec":{"holderIdentity":"%s","leaseDurationSeconds":%d,"acquireTime":"%s","renewTime":"%s"}}
            """.formatted(leaseName, namespace, resourceVersion, identity, leaseTime.toSeconds(), iso(now), iso(now));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(apiBase + leaseName))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)));
        // 409 Conflict 代表 resourceVersion 已經過時 —— 別的副本搶先一步。這就是互斥發生的地方。
        return response.statusCode() == 200;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpRequest request = builder
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(5))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String iso(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
}
