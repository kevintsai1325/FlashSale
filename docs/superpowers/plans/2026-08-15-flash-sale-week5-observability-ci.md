# FlashSale Week 5(可觀測性與 CI)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把主規格 §13 列出、延後至今的可觀測性基礎設施(Actuator health probes、自訂搶購指標、
Micrometer Tracing + Zipkin、結構化 JSON 日誌)與 GitHub Actions CI(build+test)補齊,並移除
Week 4 權宜用的 `TraceIdFilter`。

**Architecture:** 全部是既有系統的橫切基礎設施補強,不新增業務模組。指標/tracing 共用元件放進
`common..`(避免重演 Week 4 handoff 記錄過的跨模組 adapter 耦合債);Zipkin 只在
`compose.yaml` 多一個服務,不進 Nginx 反代;CI 是純新增的 `.github/workflows/ci.yml`,不改動
既有部署路徑。

**Tech Stack:** Spring Boot 3.3 Actuator、Micrometer + Micrometer Tracing(Brave)、Zipkin
(`openzipkin/zipkin:3`)、`logstash-logback-encoder`、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-08-15-flash-sale-week5-observability-ci-design.md`

## Global Constraints

- 不加 `micrometer-registry-prometheus` 或 `/actuator/prometheus`(Prometheus/Grafana 是第二階段,spec §7)。
- 不做 CI image build/push/deploy,只 build+test(spec §1)。
- 不做結構化日誌的 profile 分流(單一 JSON console encoder,spec §6)。
- 排程背景工作各自起獨立 trace,不偽造跟上游請求的因果關係(spec §11)。
- Zipkin 不透過 Nginx 反代,只給本機 debug 用(spec §5.4)。
- `PurchaseMetrics` 放 `common/metrics`,不放進任一業務模組的 `adapter` package(spec §4,
  避免重演 Week 4 handoff 記錄的跨模組 adapter 耦合債)。
- push 到 GitHub remote(包含 Task 6 驗證 CI 是否綠燈那一步)需要另外向使用者確認,不自動執行
  (既有的 commit/push 需先取得同意的規矩)。

---

## Task 1: Actuator Health Probes

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java`
- Modify: `compose.yaml`
- Test: `backend/src/test/java/com/flashsale/common/web/ActuatorHealthIT.java`(new)

**Interfaces:**
- Consumes:無(本任務只動 config + security matcher)
- Produces:`/actuator/health/liveness`、`/actuator/health/readiness`(公開)、
  `/actuator/metrics/**`(需要 `ROLE_ADMIN`)三個 HTTP 端點,供 Task 8 README/docker healthcheck
  使用。

- [ ] **Step 1: 寫失敗測試 `ActuatorHealthIT`**

新建檔案,內容:

```java
package com.flashsale.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the actuator health probe groups and the metrics endpoint's authorization boundary.
 * Needs all three infra Testcontainers so readiness actually reflects PostgreSQL/Redis/RabbitMQ
 * being reachable (same container set as OrderPurchaseConsumerIT). Admin access is obtained the
 * same way AdminApiLogControllerIT does it — register+login, flip role to ADMIN in the DB, then
 * re-login to get a JWT that actually carries the ADMIN role claim — rather than @WithMockUser,
 * to stay consistent with how every other admin-gated endpoint is tested in this codebase.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class ActuatorHealthIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private static final String TEST_PRIVATE_KEY = """
        -----BEGIN PRIVATE KEY-----
        MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC4Y9PChpJUXfJi
        EKskFLGDQ1AABZoj6OTbUXa0KyTUc5Y67oDW6YogCYsf562xnQHRwOLD1KNaXj06
        hKMDmC8HQL0GBDF6FTCR83uvyqfUJ4Hpssp8YQov29EK6XmvtzCgJnuqAmA8J8CX
        W3ghiURk0cuAEM5FcZJpS8Ff3sgq/iNR/TOoOmlLoF87tCapz7eAAvO1p0TkB/wr
        OklOEM8YNs+3Kz4hkKjddXz4TP6nJ+4yh9E7KlLhwaF3KkSRKgEhFJGrTDW97Ecj
        wMRsjKwTWOKED1eS6E5hXsg5JjBMTgYqLkME8gRH5jW+os71syfOj5Ao6Nc7DyFZ
        oUj3Q1pfAgMBAAECggEAB0Rv+S/Cnq6hOfo8NIzYPjst8QJHg/jO5FH+orU8m17+
        4c26qD3GIuMdZ6GC+AgfJTw788nyskIama7WmfKqj9eeW5lYtd4V7vqwukn7eWIh
        Pau9TU+pzh8UyyBOmn1W3kkGALpdPqG2doC1aGT3nB2krqR67MPAKIRC19t4+jC+
        3HPVLiIdMy2ayF3xOoTVKAxJjGIKTGSPbEZu8ANOjeWBZ6xTgjYa0pTGgFzy5yg3
        hleW0X6htbcik5cOhwEH0+zhmXGNzF4ktbSihOU+fyTCdL/QpsUPtbImHM/Gf2GT
        NMKI37R1uErJcYzLIQNB8srehWyqtAbRd8Pj0zNxoQKBgQDng7TUMa8uBJqSOh/M
        fX/S8SN3oS7EYdGeChapm2rjfxiuevF17aaXyfz8vBMxw7S26Eifl9IkPI+X4iGH
        jSaHT9RSW4LGLMQq2t7t2yEFv9BE7U7WRecfUewHg5Th3yR3XTTjG0k68jFiuVI9
        pBJrWTzumYJLQAcmVa+j7dWT0QKBgQDL5DdcylyEluyhf/iw8oZvbCQCZ/Y0WX4K
        y2xywcmkhDMcddXDMaxRbeYCQNbc1al13zJ98bIN550+u20GjIgAgvu16eMUyllC
        2nCh/MeVozE9mbLJyoF7sjM+FjXljZBgFj3lSltwW+ZnwDY8gBJrLvkapJdlKPdh
        Vnd7svqHLwKBgDwnGGDZ1+5Y++BqgcCcCw4/4TtAAeq8j75EWMcQvqEFcOBEyWAe
        s15U+Qqhw0r20omDqPruc4c+xQBtnNCfeBdIQp5zcHMVRpLr82hRuy7HO9Hs5sL9
        vqOAoZcCNTjKxarN6OPpPwm1y+cex6OEcdS6hv5nnFb49+KZ+Nza+tdBAoGAcbEQ
        Le2pKUX/LQ7u3bxeukLS0YSnBQnh/qLwFg15IwOUfIo4aF+Kdt2RJDCDnyCFHfUX
        cqMTZi2AwTpB0SULsT1YnleNCErM+zpTFACgShB1pKPPzjXdfdwgNr6rzxThLLM6
        UGDmHAEiuTe1BodjveCzhufAg+gUCXLtrUxf5oECgYAcf3bOkoST/7m3wDArGZHi
        bxa6mDItSNNoQo45H4liID3CMD4pnUGorhZ/oTF1dWwO5Jqeg4DTufkV+lHmVECZ
        HkpcA3daUkxZ5xjGl9I9L84A2e4z2ZJKerTvR+KlLP64A70YDawGpTxO8QH6fjPG
        1sN0RkZQZpMJPN4/1lsz6g==
        -----END PRIVATE KEY-----
        """;

    private static final String TEST_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuGPTwoaSVF3yYhCrJBSx
        g0NQAAWaI+jk21F2tCsk1HOWOu6A1umKIAmLH+etsZ0B0cDiw9SjWl49OoSjA5gv
        B0C9BgQxehUwkfN7r8qn1CeB6bLKfGEKL9vRCul5r7cwoCZ7qgJgPCfAl1t4IYlE
        ZNHLgBDORXGSaUvBX97IKv4jUf0zqDppS6BfO7Qmqc+3gALztadE5Af8KzpJThDP
        GDbPtys+IZCo3XV8+Ez+pyfuMofROypS4cGhdypEkSoBIRSRq0w1vexHI8DEbIys
        E1jihA9XkuhOYV7IOSYwTE4GKi5DBPIER+Y1vqLO9bMnzo+QKOjXOw8hWaFI90Na
        XwIDAQAB
        -----END PUBLIC KEY-----
        """;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
        registry.add("JWT_PRIVATE_KEY", () -> TEST_PRIVATE_KEY);
        registry.add("JWT_PUBLIC_KEY", () -> TEST_PUBLIC_KEY);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private String requestBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
    }

    private String registerAndLogin(String email) throws Exception {
        String body = requestBody(email, "secret123");
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String registerAdminAndLogin(String email) throws Exception {
        String token = registerAndLogin(email);
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email);
        // Role is baked into the JWT at login time, so re-login after the promotion.
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content(requestBody(email, "secret123")))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void livenessIsUpAndPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessIsUpWhenDependenciesAreReachable() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void metricsEndpointRejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsEndpointAllowsAdmin() throws Exception {
        String adminToken = registerAdminAndLogin("actuator-admin@example.com");

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 執行測試,確認全部失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.web.ActuatorHealthIT"`
Expected: 4 個測試都 FAIL——`liveness`/`readiness` sub-path 目前回 404(這兩個 endpoint 還沒開);
`metricsEndpointRejectsAnonymousCallers` 目前對 `/actuator/metrics` 回 401 是符合預期的通過
(`anyRequest().authenticated()` 目前就會擋掉),但 `metricsEndpointAllowsAdmin` 會 FAIL 因為
`/actuator/metrics` 完全沒開 exposure,就算通過 Security 也會 404。

- [ ] **Step 3: 調整 `application.yml`**

把 §45-53 行(`management:`/`logging:` 區塊)改成:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when-authorized
  health:
    readinessstate:
      enabled: true
    livenessstate:
      enabled: true
```

- [ ] **Step 4: 調整 `SecurityConfig`**

`backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java:47-50`,把

```java
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/actuator/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/flash-sales/**").permitAll()
```

改成:

```java
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/admin/**", "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/flash-sales/**").permitAll()
```

（`/actuator/health` 跟 `/actuator/health/**` 都列,涵蓋 bare path 跟 `/liveness`、`/readiness`
sub-path 兩種情況;metrics 比照 `/api/admin/**` 一樣要求 `ROLE_ADMIN`,理由見 spec §3。）

- [ ] **Step 5: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.web.ActuatorHealthIT"`
Expected: 4 個測試全部 PASS。

- [ ] **Step 6: 調整 `compose.yaml` 的 backend healthcheck**

`compose.yaml:68-72`,把

```yaml
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
```

改成:

```yaml
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 10
```

- [ ] **Step 7: 跑一次全套 backend 測試,確認沒有連帶弄壞其他測試**

Run: `cd backend && ./gradlew test`
Expected: 全部測試 PASS(既有測試數量 + 本任務新增的 4 個)。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/application.yml \
  backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java \
  compose.yaml \
  backend/src/test/java/com/flashsale/common/web/ActuatorHealthIT.java
git commit -m "feat: expose actuator health probes and metrics endpoint"
```

---

## Task 2: 自訂搶購業務指標(`PurchaseMetrics`)

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/metrics/PurchaseMetrics.java`
- Modify: `backend/src/main/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGateway.java`
- Modify: `backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java`
- Test: `backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayIT.java`(add cases)
- Test: `backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerIT.java`(add case)

**Interfaces:**
- Consumes:`io.micrometer.core.instrument.MeterRegistry`(Spring Boot Actuator 自動裝配的
  bean,已經在 classpath 上,不需要新依賴)
- Produces:`PurchaseMetrics`(`@Component`,`com.flashsale.common.metrics` package)三個 public
  方法——`recordReservationOutcome(String outcome)`、`Timer.Sample startReservationTimer()` /
  `stopReservationTimer(Timer.Sample sample)`、`orderCreated()`——供 Task 4 以外的既有 adapter
  呼叫,不供其他任務新增依賴。

- [ ] **Step 1: 寫失敗測試(先在 `RedisInventoryStockGatewayIT` 加兩個案例)**

在 `backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayIT.java`
補 import(`io.micrometer.core.instrument.MeterRegistry`)、新增欄位 `@Autowired MeterRegistry
meterRegistry;`,並在既有測試 class 底部加:

```java
    @Test
    void reserveIncrementsReservedCounterAndRecordsLatency() {
        // @BeforeEach seedFlashSaleAndInventory() already seeds flash sale id 42 with
        // available_quantity 3 — reserving 1 succeeds without any extra setup.
        gateway.reserve(42L, 1);

        assertThat(meterRegistry.get("purchase.reservation").tag("outcome", "reserved").counter().count())
            .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("purchase.reservation.latency").timer().count())
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void reserveIncrementsInsufficientStockCounterWhenSoldOut() {
        gateway.reserve(42L, 3); // exhausts the seeded available_quantity of 3

        gateway.reserve(42L, 1); // now sold out

        assertThat(meterRegistry.get("purchase.reservation").tag("outcome", "insufficient_stock").counter().count())
            .isGreaterThanOrEqualTo(1.0);
    }
```

- [ ] **Step 2: 執行測試,確認失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.adapter.redis.RedisInventoryStockGatewayIT"`
Expected: 兩個新案例 FAIL(`meterRegistry.get("purchase.reservation")` 找不到這個 meter
name,拋 `MeterNotFoundException`)。

- [ ] **Step 3: 新增 `PurchaseMetrics`**

```java
package com.flashsale.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting purchase-flow metrics, called from both the inventory module's Redis gateway and
 * the order module's purchase consumer. Lives under {@code common} rather than either module's
 * own {@code adapter} package so neither module has to reach into the other's adapter layer to
 * use it (see design spec §4).
 */
@Component
public class PurchaseMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter orderCreatedCounter;

    public PurchaseMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.orderCreatedCounter = Counter.builder("purchase.order.created")
            .description("Orders successfully created from a Redis-reserved purchase request")
            .register(meterRegistry);
    }

    public void recordReservationOutcome(String outcome) {
        Counter.builder("purchase.reservation")
            .description("Outcome of a Redis stock reservation attempt")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startReservationTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopReservationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("purchase.reservation.latency")
            .description("Latency of the Redis Lua stock reservation call")
            .register(meterRegistry));
    }

    public void orderCreated() {
        orderCreatedCounter.increment();
    }
}
```

- [ ] **Step 4: 接進 `RedisInventoryStockGateway`**

`backend/src/main/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGateway.java`,
建構子多注入 `PurchaseMetrics`:

```java
import com.flashsale.common.metrics.PurchaseMetrics;
import io.micrometer.core.instrument.Timer;
```

```java
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> reserveStockScript;
    private final InventoryRepository inventoryRepository;
    private final PurchaseMetrics purchaseMetrics;

    public RedisInventoryStockGateway(StringRedisTemplate redisTemplate, RedisScript<Long> reserveStockScript,
                                       InventoryRepository inventoryRepository, PurchaseMetrics purchaseMetrics) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = reserveStockScript;
        this.inventoryRepository = inventoryRepository;
        this.purchaseMetrics = purchaseMetrics;
    }
```

`reserve()` 整個方法改成:

```java
    @Override
    public StockReservationResult reserve(Long flashSaleId, int quantity) {
        Timer.Sample sample = purchaseMetrics.startReservationTimer();
        try {
            ensureSeeded(flashSaleId);
            Long remaining = redisTemplate.execute(reserveStockScript, List.of(stockKey(flashSaleId)), String.valueOf(quantity));
            if (remaining != null && remaining == -2) {
                ensureSeeded(flashSaleId);
                remaining = redisTemplate.execute(reserveStockScript, List.of(stockKey(flashSaleId)), String.valueOf(quantity));
            }
            if (remaining == null || remaining == -2) {
                throw new ServiceUnavailableException("STOCK_GATEWAY_UNAVAILABLE",
                    "Unable to reach Redis to reserve stock for flash sale " + flashSaleId);
            }
            StockReservationResult result = remaining == -1 ? StockReservationResult.INSUFFICIENT_STOCK : StockReservationResult.RESERVED;
            purchaseMetrics.recordReservationOutcome(result == StockReservationResult.RESERVED ? "reserved" : "insufficient_stock");
            return result;
        } finally {
            purchaseMetrics.stopReservationTimer(sample);
        }
    }
```

（`ServiceUnavailableException` 分支不呼叫 `recordReservationOutcome`——那是基礎設施錯誤,不是
業務結果,spec §4 已明講;`finally` 確保計時器不管哪個分支都會停。）

- [ ] **Step 5: 修正 `RedisInventoryStockGatewayTest`(既有的純 Mockito 單元測試,建構子多了一個
參數會編譯失敗)**

`backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayTest.java`
補 import:

```java
import com.flashsale.common.metrics.PurchaseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

在 `@Mock ValueOperations<String, String> valueOperations;` 那行後面加一個欄位(用真正的
`SimpleMeterRegistry` 而不是 mock——`PurchaseMetrics` 本身沒有值得 mock 的行為,用真的
registry 比較不會有 Mockito strict-stubbing 誤判未使用 stub 的問題):

```java
    PurchaseMetrics purchaseMetrics = new PurchaseMetrics(new SimpleMeterRegistry());
```

把檔案裡全部 4 處

```java
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(redisTemplate, reserveStockScript, inventoryRepository);
```

改成:

```java
        RedisInventoryStockGateway gateway = new RedisInventoryStockGateway(redisTemplate, reserveStockScript, inventoryRepository, purchaseMetrics);
```

（`throwsServiceUnavailableWhenLuaScriptReturnsMinusTwoBothAttempts`、
`reservesSuccessfullyWhenLuaReturnsRemainderQuantity`、
`returnInsufficientStockWhenLuaReturnsMinusOne`、
`retriesSeededOnceWhenScriptInitiallyReturnsMinusTwo` 這 4 個測試方法各一處。）

- [ ] **Step 6: 執行測試,確認通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.adapter.redis.RedisInventoryStockGatewayTest" --tests "com.flashsale.inventory.adapter.redis.RedisInventoryStockGatewayIT"`
Expected: 全部 PASS(先前 Step 1-2 寫的兩個新案例、既有的 4 個既有單元測試、既有的 IT 測試
全部綠燈)。

- [ ] **Step 7: 寫失敗測試(`OrderPurchaseConsumerIT` 加一個案例)**

在 `backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerIT.java` 補
`@Autowired MeterRegistry meterRegistry;` 與 import,新增:

```java
    @Test
    void successfulOrderCreationIncrementsOrderCreatedCounter() {
        double before = meterRegistry.find("purchase.order.created").counter() == null
            ? 0.0 : meterRegistry.find("purchase.order.created").counter().count();

        // Same flow as pendingPurchaseRequestEventuallyBecomesSucceededWithARealOrder() above:
        // register+login, submit a purchase request, wait for it to resolve to a real order.
        String token = registerAndLogin("consumer-metrics-test@example.com", "secret123");
        mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "consumer-metrics-key-1"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(meterRegistry.get("purchase.order.created").counter().count()).isGreaterThan(before));
    }
```

（`meterRegistry.find(...)` 用來在 counter 可能還沒被任何請求建立過時,安全取得起始值 0,避免
`get(...)` 因為 meter 還不存在而拋例外。這個測試方法要放進 class 裡,能存取既有的
`registerAndLogin(String, String)` private helper 與 `mockMvc`/`objectMapper` 欄位。）

- [ ] **Step 8: 執行測試,確認失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerIT"`
Expected: 新案例 FAIL(counter 不存在或數值沒有增加)。

- [ ] **Step 9: 接進 `OrderPurchaseConsumer`**

`backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java`,建構子
多注入 `PurchaseMetrics`:

```java
import com.flashsale.common.metrics.PurchaseMetrics;
```

```java
    private final ConsumedMessageGuard consumedMessageGuard;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;
    private final PurchaseMetrics purchaseMetrics;

    public OrderPurchaseConsumer(ConsumedMessageGuard consumedMessageGuard, PurchaseRequestRepository purchaseRequestRepository,
                                  InventoryRepository inventoryRepository, OrderRepository orderRepository,
                                  OrderStatusHistoryRepository orderStatusHistoryRepository, ObjectMapper objectMapper,
                                  PurchaseMetrics purchaseMetrics) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.objectMapper = objectMapper;
        this.purchaseMetrics = purchaseMetrics;
    }
```

`handle()` 方法最後、`purchaseRequestRepository.save(purchaseRequest);` 之後補一行:

```java
        purchaseRequest.markSucceeded(savedOrder.getId());
        purchaseRequestRepository.save(purchaseRequest);
        purchaseMetrics.orderCreated();
```

- [ ] **Step 10: 執行測試,確認通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerIT"`
Expected: PASS。

- [ ] **Step 11: 跑一次全套 backend 測試**

Run: `cd backend && ./gradlew test`
Expected: 全部 PASS(`OrderPurchaseConsumer` 建構子也多了一個參數——Step 6 已經確認過
`RedisInventoryStockGateway` 唯一手動 `new` 的地方已經修好,`OrderPurchaseConsumer` 目前只由
Spring 建構子注入使用,沒有其他手動建構的地方)。

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/flashsale/common/metrics/PurchaseMetrics.java \
  backend/src/main/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGateway.java \
  backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java \
  backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayTest.java \
  backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayIT.java \
  backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerIT.java
git commit -m "feat: record custom purchase metrics (reservation outcome/latency, orders created)"
```

---

## Task 3: Micrometer Tracing + Zipkin(取代 `TraceIdFilter`)

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/resources/application.yml`
- Delete: `backend/src/main/java/com/flashsale/common/web/TraceIdFilter.java`
- Modify: `backend/src/main/java/com/flashsale/common/web/ApiAuditFilter.java`
- Modify: `backend/src/test/resources/application-integration-test.yml`
- Modify: `compose.yaml`
- Test: `backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java`(add case)

**Interfaces:**
- Consumes:`io.micrometer.tracing.Tracer`(Spring Boot 自動裝配,一旦
  `micrometer-tracing-bridge-brave` 在 classpath 上就會出現這個 bean)
- Produces:`X-Trace-Id` response header 與 `api_audit_logs.trace_id`/`request_id` 的值改由
  Brave 產生(小寫 hex 字串),取代 Task 3 之前的 UUID 格式——這是 Task 8 手動驗證 Zipkin trace
  時要核對的欄位。

- [ ] **Step 1: 加依賴**

`backend/build.gradle.kts`,`dependencies { ... }` 區塊裡 `implementation("org.springframework.boot:spring-boot-starter-amqp")` 那行後面加:

```kotlin
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")
```

（兩者版本都由 `io.spring.dependency-management` plugin 引入的 Spring Boot BOM 管理,不用寫
版本號。）

- [ ] **Step 2: 調整 `application.yml`**

Task 1 已經把 `management:` 區塊改成 health/metrics 那份。在同一個 `management:` 區塊底下(跟
`endpoints:`/`endpoint:`/`health:` 同層)加:

```yaml
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: ${MANAGEMENT_ZIPKIN_TRACING_ENDPOINT:http://localhost:9411/api/v2/spans}
```

再新增一個獨立的 `spring.rabbitmq` 補充區塊(跟 `application.yml` 已有的 `spring.rabbitmq`
區塊合併,不要新開一個 `spring:` 頂層 key)——在既有 `rabbitmq:` 區塊的 `listener: simple:
retry:` 同層加 `template:`/`observation-enabled`:

```yaml
  rabbitmq:
    host: ${SPRING_RABBITMQ_HOST:localhost}
    port: ${SPRING_RABBITMQ_PORT:5672}
    username: ${SPRING_RABBITMQ_USERNAME:guest}
    password: ${SPRING_RABBITMQ_PASSWORD:guest}
    template:
      observation-enabled: true
    listener:
      simple:
        observation-enabled: true
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000
          multiplier: 2.0
          max-interval: 10000
        default-requeue-rejected: false
```

（縮排比照 `application.yml` 現有的 `spring.rabbitmq` 區塊——`rabbitmq:` 跟 `spring:` 差 2
格,底下每一層再差 2 格,上面這段直接比對既有檔案的層級貼進去即可。）

（沙盒說明:上面整段是既有 `spring.rabbitmq` 區塊 + 新增的 `template.observation-enabled` /
`listener.simple.observation-enabled` 兩個 key,其餘 `host`/`port`/`username`/`password`/
`retry`/`default-requeue-rejected` 都是原本就有的,原樣保留不要刪掉。）

`sampling.probability: 1.0` 的理由與 `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` 環境變數預設值比照
`SPRING_DATA_REDIS_HOST`/`SPRING_RABBITMQ_HOST` 既有 pattern(本機/IDE 執行預設打
`localhost:9411`,docker-compose 用環境變數蓋成 `zipkin:9411`),理由見 spec §5.1、§5.4。

- [ ] **Step 3: 刪除 `TraceIdFilter`**

刪除整個檔案 `backend/src/main/java/com/flashsale/common/web/TraceIdFilter.java`。

- [ ] **Step 4: 改寫 `ApiAuditFilter`**

`backend/src/main/java/com/flashsale/common/web/ApiAuditFilter.java` 整個檔案改成:

```java
package com.flashsale.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Writes one audit row per HTTP request via {@link ApiAuditWriter}. Observes only — it calls
 * {@code filterChain.doFilter} unconditionally and lets exceptions propagate through untouched;
 * it never handles them.
 *
 * <p>Registered to run <b>after</b> Spring Security's filter chain so {@code SecurityContextHolder}
 * is populated by the time this filter's {@code finally} block reads it. Spring Boot registers the
 * security filter chain at {@code SecurityProperties.DEFAULT_FILTER_ORDER} — despite the name, this
 * is <b>not</b> {@code Ordered.HIGHEST_PRECEDENCE + 100} (that would be {@code Integer.MIN_VALUE +
 * 100}); its actual value is {@code OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 100}, i.e.
 * {@code -100}. This filter uses one more than that ({@code -99}) so it always runs later in the
 * chain (lower order = earlier).
 *
 * <p>Never captures Authorization headers, passwords, JWT contents, or request/response bodies —
 * by construction: nothing here reads the Authorization header, the request/response body streams
 * are never touched, and the only identity data captured is the numeric {@code userId} claim
 * already verified by Spring Security.
 *
 * <p>Trace id comes from Micrometer Tracing's {@link Tracer} (Spring Boot auto-configures a span
 * around the whole request before this filter runs, once {@code micrometer-tracing-bridge-brave}
 * is on the classpath) rather than a bespoke filter — see design spec §5.2.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class ApiAuditFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String ERROR_CODE_ATTRIBUTE = "apiAuditErrorCode";
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final ApiAuditWriter auditWriter;
    private final Tracer tracer;

    public ApiAuditFilter(ApiAuditWriter auditWriter, Tracer tracer) {
        this.auditWriter = auditWriter;
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startMillis = System.currentTimeMillis();
        Span currentSpan = tracer.currentSpan();
        String traceId = currentSpan != null ? currentSpan.context().traceId() : null;
        if (traceId != null) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startMillis;

            // Only populated after the request has been dispatched to a handler, i.e. only
            // readable here, after filterChain.doFilter() returns.
            String pathTemplate = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pathTemplate == null) {
                pathTemplate = request.getRequestURI();
            }

            // Design spec 3.3/5.2: request_id and trace_id store the same value this round — they're
            // only meant to diverge once a real span/trace concept needs a separate per-hop request id.
            ApiAuditLog log = new ApiAuditLog(
                request.getMethod(),
                pathTemplate,
                response.getStatus(),
                resolveUserId(),
                traceId,
                traceId,
                (int) durationMs,
                resolveClientIp(request),
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH),
                (String) request.getAttribute(ERROR_CODE_ATTRIBUTE));

            auditWriter.record(log);
        }
    }

    /**
     * In the deployed stack, requests reach this backend through the Nginx reverse proxy
     * ({@code nginx/nginx.conf}), so {@link HttpServletRequest#getRemoteAddr()} is always Nginx's
     * own container address, not the real client. Nginx forwards the real client address via the
     * {@code X-Real-IP} header (see {@code proxy_set_header X-Real-IP $remote_addr;} in
     * {@code nginx/nginx.conf}), so prefer that when present. Falls back to
     * {@code getRemoteAddr()} for requests that reach the backend directly, bypassing Nginx (e.g.
     * integration tests using MockMvc/TestRestTemplate).
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return (realIp != null && !realIp.isBlank()) ? realIp : request.getRemoteAddr();
    }

    private Long resolveUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Object userId = jwtAuthentication.getToken().getClaim("userId");
            if (userId instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
```

- [ ] **Step 5: 讓測試環境不要嘗試把 span 送到不存在的 Zipkin**

`backend/src/test/resources/application-integration-test.yml` 開頭 `spring:` 區塊(`jpa:`/
`flyway:` 那兩行)之後加:

```yaml
  management:
    tracing:
      sampling:
        probability: 0
```

（機率設 0 只影響「要不要真的送出去」,`Tracer.currentSpan()` 在請求處理過程中仍然有值——Brave
即使 sampler 決定不採樣,還是會建立 trace context,只是標記不匯出。這樣測試不會因為連不到
`localhost:9411` 而在背景一直重試/噴警告 log,`ApiAuditFilterIT` 既有的
`request_id`/`trace_id` 相等斷言不受影響。）

- [ ] **Step 6: 在 `ApiAuditFilterIT` 加一個驗證「trace id 真的換掉了」的案例**

`backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java`,在既有 import 區塊補
`import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;`,並在
`clientIpFallsBackToRemoteAddrWhenXRealIpHeaderAbsent()` 之後加:

```java
    @Test
    void traceIdIsARealBraveTraceIdNotAUuid() throws Exception {
        mockMvc.perform(get("/api/flash-sales"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{16,32}$")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from api_audit_logs where path_template = '/api/flash-sales' " +
                    "and method = 'GET' and status = 200 order by id desc limit 1");
            assertThat((String) row.get("trace_id")).matches("^[0-9a-f]{16,32}$");
        });
    }
```

（Brave 預設 64-bit trace id 是 16 個小寫 hex 字元,若之後啟用 128-bit trace id 會變 32 個——
用 `{16,32}` 涵蓋兩種情況,不 overfit 到目前的預設值。這個斷言在 `TraceIdFilter` 還在的時候會
FAIL,因為 UUID 格式帶 `-` 且是 32 hex + 4 個連字號,不符合這個 pattern——這正是本任務要驗證的
行為改變。）

- [ ] **Step 7: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.web.ApiAuditFilterIT"`
Expected: 全部 PASS,包含既有的 `request_id`/`trace_id` 相等斷言與新加的格式斷言。

- [ ] **Step 8: 跑一次全套 backend 測試**

Run: `cd backend && ./gradlew test`
Expected: 全部 PASS——`ApiAuditFilter` 建構子多了 `Tracer` 參數,確認沒有任何測試手動
`new ApiAuditFilter(...)`(這個類別目前只由 Spring 建構子注入使用)。

- [ ] **Step 9: `compose.yaml` 新增 `zipkin` 服務**

`compose.yaml`,在 `mailpit:` 服務區塊後面(`backend:` 服務前面)加:

```yaml
  zipkin:
    image: openzipkin/zipkin:3
    ports:
      - "9411:9411"
```

`backend:` 服務的 `environment:` 區塊(`SPRING_RABBITMQ_PASSWORD: guest` 那行後)加:

```yaml
      MANAGEMENT_ZIPKIN_TRACING_ENDPOINT: http://zipkin:9411/api/v2/spans
```

`backend:` 服務的 `depends_on:` 區塊(`rabbitmq: condition: service_healthy` 後)加:

```yaml
      zipkin:
        condition: service_started
```

（Zipkin 官方 image 沒有內建簡單的 healthcheck 指令可用,`service_started` 已經足夠——backend
即使在 Zipkin 完全就緒前送出第一批 span,Brave 的 reporter 會重試,不影響 backend 本身的啟動與
可用性。）

- [ ] **Step 10: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/resources/application.yml \
  backend/src/main/java/com/flashsale/common/web/ApiAuditFilter.java \
  backend/src/test/resources/application-integration-test.yml \
  backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java \
  compose.yaml
git rm backend/src/main/java/com/flashsale/common/web/TraceIdFilter.java
git commit -m "feat: replace TraceIdFilter with Micrometer Tracing + Zipkin"
```

---

## Task 4: 排程背景工作的獨立 Trace(`@Observed`)

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/flashsale/common/config/ObservationConfig.java`
- Modify: `backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java`
- Modify: `backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java`
- Modify: `backend/src/main/java/com/flashsale/notification/application/NotificationRetryScheduler.java`
- Modify: `backend/src/main/java/com/flashsale/common/web/ApiAuditRetentionScheduler.java`
- Test: `backend/src/test/java/com/flashsale/common/config/SchedulerObservabilityTest.java`(new)

**Interfaces:**
- Consumes:Task 3 引入的 `micrometer-tracing-bridge-brave`(讓 `@Observed` 產生的
  observation 同時變成一條 Zipkin span,不只是本地 metric)
- Produces:無新的 public API——四個既有排程方法的外部呼叫方式不變,只是每次執行會多產生一個
  observation/trace,供 Task 8 手動到 Zipkin UI 核對。

- [ ] **Step 1: 加 AOP 依賴**

`@Observed` 註解要生效需要 Spring AOP 的 AspectJ 支援(`ObservedAspect` 是一個
`@Aspect`-annotated bean,沒有 `aspectjweaver` 在 classpath 上,Spring Boot 的
`AopAutoConfiguration` 不會啟用 AspectJ auto-proxy,註解會被忽略)。`backend/build.gradle.kts`
新增:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-aop")
    testImplementation("io.micrometer:micrometer-observation-test")
```

- [ ] **Step 2: 寫失敗測試 `SchedulerObservabilityTest`**

新建檔案:

```java
package com.flashsale.common.config;

import com.flashsale.common.web.ApiAuditLogJpaRepository;
import com.flashsale.common.web.ApiAuditRetentionScheduler;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.inventory.application.InventoryReconciliationScheduler;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.application.NotificationRetryScheduler;
import com.flashsale.notification.application.NotificationSender;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.PaymentTimeoutScheduler;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies each scheduled background job is wrapped with {@code @Observed} and actually produces
 * an observation when it runs — using {@link AspectJProxyFactory} to apply {@link ObservedAspect}
 * directly to a manually constructed instance, without booting a Spring context or any
 * Testcontainers (see design spec §5.3 / §10: Micrometer's own recommended way to test
 * {@code @Observed} aspects in isolation).
 */
class SchedulerObservabilityTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void paymentTimeoutSchedulerRunIsObserved() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findPendingPaymentPastDue(any(Instant.class))).thenReturn(List.of());
        PaymentTimeoutScheduler scheduler = observedProxy(
            new PaymentTimeoutScheduler(orderRepository, mock(OrderCompensationService.class)));

        scheduler.expireOverduePayments();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.expireOverduePayments");
    }

    @Test
    void inventoryReconciliationSchedulerRunIsObserved() {
        FlashSaleRepository flashSaleRepository = mock(FlashSaleRepository.class);
        when(flashSaleRepository.findAll()).thenReturn(List.of());
        InventoryReconciliationScheduler scheduler = observedProxy(
            new InventoryReconciliationScheduler(flashSaleRepository, mock(InventoryRepository.class), mock(InventoryStockGateway.class)));

        scheduler.reconcileActiveFlashSales();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.reconcileActiveFlashSales");
    }

    @Test
    void notificationRetrySchedulerRunIsObserved() {
        NotificationDeliveryRepository deliveryRepository = mock(NotificationDeliveryRepository.class);
        when(deliveryRepository.findFailedWithAttemptsBelow(anyInt())).thenReturn(List.of());
        NotificationRetryScheduler scheduler = observedProxy(
            new NotificationRetryScheduler(deliveryRepository, mock(NotificationSender.class)));

        scheduler.retryDueNotifications();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.retryDueNotifications");
    }

    @Test
    void apiAuditRetentionSchedulerRunIsObserved() {
        ApiAuditLogJpaRepository repository = mock(ApiAuditLogJpaRepository.class);
        when(repository.deleteByOccurredAtBefore(any(Instant.class))).thenReturn(0);
        ApiAuditRetentionScheduler scheduler = observedProxy(new ApiAuditRetentionScheduler(repository, 30));

        scheduler.purgeExpiredLogs();

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo("scheduler.purgeExpiredLogs");
    }

    private <T> T observedProxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObservedAspect(registry));
        return factory.getProxy();
    }
}
```

- [ ] **Step 3: 執行測試,確認全部失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.config.SchedulerObservabilityTest"`
Expected: 4 個測試都 FAIL(`hasObservationWithNameEqualTo` 找不到對應名稱,因為四個排程方法還
沒有 `@Observed`)。

- [ ] **Step 4: 新增 `ObservationConfig`**

```java
package com.flashsale.common.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code @Observed}-annotation aspect. Spring Boot auto-configures an
 * {@link ObservationRegistry} bean but does not register {@link ObservedAspect} itself — it has to
 * be added explicitly to activate the {@code @Observed} annotation on scheduled jobs.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
```

- [ ] **Step 5: 幫四個排程方法加 `@Observed`**

`PaymentTimeoutScheduler.java`,import `io.micrometer.observation.annotation.Observed;`,方法
簽名改成:

```java
    @Scheduled(fixedDelay = 30000)
    @Observed(name = "scheduler.expireOverduePayments")
    public void expireOverduePayments() {
```

`InventoryReconciliationScheduler.java`,同樣 import,方法簽名改成:

```java
    @Scheduled(fixedDelay = 60000)
    @Observed(name = "scheduler.reconcileActiveFlashSales")
    public void reconcileActiveFlashSales() {
```

`NotificationRetryScheduler.java`,同樣 import,方法簽名改成:

```java
    @Scheduled(fixedDelay = 60000)
    @Observed(name = "scheduler.retryDueNotifications")
    public void retryDueNotifications() {
```

`ApiAuditRetentionScheduler.java`,同樣 import,方法簽名改成(跟既有的 `@Transactional` 疊加):

```java
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    @Observed(name = "scheduler.purgeExpiredLogs")
    public void purgeExpiredLogs() {
```

- [ ] **Step 6: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.config.SchedulerObservabilityTest"`
Expected: 全部 PASS。

- [ ] **Step 7: 跑一次全套 backend 測試**

Run: `cd backend && ./gradlew test`
Expected: 全部 PASS。

- [ ] **Step 8: Commit**

```bash
git add backend/build.gradle.kts \
  backend/src/main/java/com/flashsale/common/config/ObservationConfig.java \
  backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java \
  backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java \
  backend/src/main/java/com/flashsale/notification/application/NotificationRetryScheduler.java \
  backend/src/main/java/com/flashsale/common/web/ApiAuditRetentionScheduler.java \
  backend/src/test/java/com/flashsale/common/config/SchedulerObservabilityTest.java
git commit -m "feat: give each scheduled background job its own trace via @Observed"
```

---

## Task 5: 結構化 JSON 日誌

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes:無
- Produces:console 輸出格式從純文字改成 JSON(每行一個 JSON object),供 Task 8 手動驗證。不影響
  任何既有程式碼的行為或簽名,純粹是輸出格式的改變。

- [ ] **Step 1: 加依賴**

`backend/build.gradle.kts` 新增:

```kotlin
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
```

- [ ] **Step 2: 新增 `logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

    <logger name="com.flashsale" level="INFO"/>
</configuration>
```

（`includeMdcKeyName` 明確列出要放進 JSON 的 MDC key,而不是整包 MDC 都丟進去——`traceId`/
`spanId` 是 Micrometer Tracing 的 Brave bridge 透過 `MDCScopeDecorator` 自動寫入的兩個 key
（Task 3 已經生效),明確列出這兩個可以避免未來 MDC 裡不小心混進其他不該進 log 的 key 時被整包
帶出去。root logger level 跟 `com.flashsale` logger level 都維持 `INFO`,對應
`application.yml` 原本 `logging.level.com.flashsale: INFO` 那行的效果——`logback-spring.xml`
接手之後那行設定值不再生效(Logback XML 設定優先),所以直接把同樣的 level 寫進 XML 裡,不要
兩邊都留造成誤會。）

`backend/src/main/resources/application.yml` 把 `logging:` 區塊(`level: com.flashsale:
INFO`)整段刪除——level 設定已經搬進 `logback-spring.xml`,原本這兩行留著也不會生效,留著只會
讓人誤以為它還在起作用。

- [ ] **Step 3: 本機驗證輸出格式(不是自動化測試——見 spec §10 的理由)**

Run: `cd backend && ./gradlew test --tests "com.flashsale.FlashSaleApplicationTests" --info | grep -A2 "INFO"`
（`--info` 讓 Gradle 把測試過程中的應用程式 log 輸出到 console,不用另外啟動一份完整的
`bootRun`。)
Expected: console 印出的每一行應用程式 log 都是合法 JSON(`{"@timestamp":...,"level":"INFO",
"logger_name":...,"message":...}` 這種形狀),不是原本的純文字 pattern。

- [ ] **Step 4: 跑一次全套 backend 測試,確認沒有連帶弄壞其他測試**

Run: `cd backend && ./gradlew test`
Expected: 全部 PASS——這個任務不改變任何測試斷言依賴的行為,只改 log 輸出格式。

- [ ] **Step 5: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/resources/logback-spring.xml \
  backend/src/main/resources/application.yml
git commit -m "feat: emit structured JSON logs via logstash-logback-encoder"
```

---

## Task 6: GitHub Actions CI(build + test)

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `frontend/package.json`

**Interfaces:**
- Consumes:無(獨立的 CI 設定,不影響應用程式行為)
- Produces:兩個 GitHub Actions job(`backend`、`frontend`),供 Task 8 README 加 CI badge 連結。

- [ ] **Step 1: 補 `frontend/package.json` 的 `test` script**

`frontend/package.json`,`"scripts"` 區塊(目前是 `dev`/`build`/`lint`/`preview`)加:

```json
    "test": "vitest run",
```

放在 `"lint": "oxlint",` 之後、`"preview": "vite preview"` 之前。

- [ ] **Step 2: 本機驗證新 script 可用**

Run(這台機器 node/npm 不在 PATH,用既有的 Docker node image 模式):
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npm run test"`
Expected: 跟直接跑 `npx vitest run` 結果一致,全部既有 frontend 測試 PASS。

- [ ] **Step 3: 新增 `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  backend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle
      - name: Ensure gradlew is executable
        run: chmod +x gradlew
      - name: Run backend tests
        run: ./gradlew test

  frontend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - name: Install dependencies
        run: npm ci
      - name: Lint
        run: npm run lint
      - name: Type-check and build
        run: npm run build
      - name: Run frontend tests
        run: npm test
```

（`backend` job 沒有額外的 Postgres/Redis/RabbitMQ service container 設定——`ubuntu-latest`
runner 預裝 Docker daemon,Testcontainers 會自己啟動需要的容器,跟本機用 Docker Desktop 跑
`./gradlew test` 是同一套機制,見 spec §8。兩個 job 互相獨立、平行執行,沒有相依關係。）

- [ ] **Step 4: Commit(先不 push)**

```bash
git add .github/workflows/ci.yml frontend/package.json
git commit -m "ci: add GitHub Actions build+test workflow"
```

- [ ] **Step 5: 向使用者確認後再 push、驗證 workflow 真的綠燈**

這一步需要把本地分支 push 到 `origin`(GitHub remote:`kevintsai1325/FlashSale`)才能真正觸發
CI 執行——依照既有規矩,push 需要先取得使用者同意,不在本任務裡自動執行。取得同意後:

```bash
git push origin <branch-name>
gh run watch
```

Expected:`backend` 與 `frontend` 兩個 job 都顯示綠燈通過。若失敗,依照 `gh run view --log`
的實際錯誤訊息修正(常見情況:gradlew 執行權限、npm script 名稱打錯、workflow YAML 縮排錯誤)。

---

## Task 7: `RequireAuth.tsx` 補上 `isRestoring` 判斷

**Files:**
- Modify: `frontend/src/features/auth/RequireAuth.tsx`
- Test: `frontend/src/features/auth/RequireAuth.test.tsx`

**Interfaces:**
- Consumes:`useAuth()` 既有回傳的 `isRestoring: boolean`(`frontend/src/features/auth/useAuth.tsx:15`,
  Week 4 已經存在,`RequireAdmin.tsx` 已經在用)
- Produces:無新的對外介面,行為修正——刷新頁面時已登入的一般使用者不會被誤導回 `/login`。

- [ ] **Step 1: 寫失敗測試**

`frontend/src/features/auth/RequireAuth.test.tsx`,在既有 `describe('RequireAuth', ...)` 區塊
最後加:

```tsx
  it('does not redirect while auth restore is in progress, even though isAuthenticated is still false', () => {
    render(
      <AuthContext.Provider value={{ isAuthenticated: false, role: null, isRestoring: true, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn(), finishRestoring: vi.fn() }}>
        <MemoryRouter initialEntries={['/protected']}>
          <Routes>
            <Route path="/login" element={<div>login page</div>} />
            <Route element={<RequireAuth />}>
              <Route path="/protected" element={<div>secret content</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    )

    expect(screen.queryByText('login page')).not.toBeInTheDocument()
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
  })
```

- [ ] **Step 2: 執行測試,確認失敗**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/features/auth/RequireAuth.test.tsx"`
Expected: 新案例 FAIL——目前 `RequireAuth` 沒有檢查 `isRestoring`,`isAuthenticated: false` 會
直接導向 `/login`,`screen.queryByText('login page')` 會斷言失敗(找到了不該找到的內容)。

- [ ] **Step 3: 修正 `RequireAuth.tsx`**

整個檔案改成:

```tsx
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './useAuth'

export function RequireAuth() {
  const { isAuthenticated, isRestoring } = useAuth()
  const location = useLocation()

  if (isRestoring) {
    // Same reasoning as RequireAdmin: App.tsx's authApi.refresh() call hasn't settled yet, so
    // isAuthenticated still holds its initial "logged out" value even for a genuinely logged-in
    // user. Render nothing rather than redirecting, otherwise every page refresh bounces a real
    // logged-in user back to /login before refresh() has a chance to resolve.
    return null
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }
  return <Outlet />
}
```

- [ ] **Step 4: 執行測試,確認全部通過**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/features/auth/RequireAuth.test.tsx"`
Expected: 全部 PASS(既有 2 個案例 + 新增 1 個案例)。

- [ ] **Step 5: 跑一次全套 frontend 測試**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npm test"`
Expected: 全部 PASS,`tsc -b` 也乾淨。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/auth/RequireAuth.tsx frontend/src/features/auth/RequireAuth.test.tsx
git commit -m "fix: don't redirect RequireAuth to /login while auth restore is in progress"
```

---

## Task 8: README 更新與全棧手動驗證

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes:Task 1-7 完成後的整個 stack
- Produces:無程式碼變更,只有文件——但這個任務的「測試」是實際跑起整個 `docker compose` stack
  並記錄操作結果,不是自動化測試(理由見 spec §10)。

- [ ] **Step 1: 啟動整個 stack**

Run: `docker compose up --build`(需要 Docker Desktop 先啟動;背景執行或另開一個 terminal
監看,不要用背景 + 輪詢的方式等它「完成」——這是一個常駐服務,不是一次性指令)。
Expected:所有服務(含新增的 `zipkin`)都成功啟動,`backend` healthcheck 轉綠(這一步已經在
Task 1 改成打 `/actuator/health/readiness`)。

- [ ] **Step 2: 驗證 Actuator 端點**

Run:
```bash
curl -k https://localhost:8443/actuator/health/liveness
curl -k https://localhost:8443/actuator/health/readiness
```
Expected:兩者都回 `{"status":"UP"}`。

- [ ] **Step 3: 驗證 JSON 日誌**

Run: `docker compose logs backend --tail 50`
Expected:每一行都是合法 JSON(可以用 `docker compose logs backend --tail 50 | jq .` 確認能
解析,若某一行解析失敗代表 Task 5 的 encoder 設定有問題)。

- [ ] **Step 4: 觸發幾個請求,驗證 Zipkin trace**

用瀏覽器或 curl 打幾個 API(例如 `GET /api/flash-sales`、走一次完整搶購流程觸發
`OrderPurchaseConsumer`),然後打開 `http://localhost:9411/zipkin/` 搜尋最近的 trace。
Expected:能看到至少一條 HTTP 請求的 trace;若有實際跑過一次搶購流程,應該能看到同一個
trace 底下同時有 HTTP span 跟 RabbitMQ 相關的 span(驗證 Task 3 §5.3 提到的 producer/consumer
關聯有生效)。排程類的 trace(`scheduler.expireOverduePayments` 等)因為 fixedDelay
30-60 秒才跑一次,可能要等一下才會出現在 Zipkin,不用特地等滿——看到至少一條排程 trace 出現
即可視為驗證通過。

- [ ] **Step 5: 更新 README**

`README.md`,在既有「### 壓力測試」小節(第 116-119 行)之後加一個新的頂層章節:

```markdown

## 可觀測性 (Observability)

- **Actuator**:`https://localhost:8443/actuator/health/liveness`、
  `/actuator/health/readiness` 公開可查;`/actuator/metrics` 系列端點需要 `ADMIN` 角色的
  JWT(比照後台 API 的授權方式)。
- **Zipkin**:`http://localhost:9411/zipkin/`(只在本機 debug 用,沒有透過 Nginx 反代,不對外
  暴露)。每個 HTTP 請求都會產生一條 trace,並延續到 RabbitMQ producer/consumer 與排程背景
  工作,可以用來追蹤一次搶購請求從進站到訂單建立的完整呼叫鏈。
- **結構化日誌**:`docker compose logs backend` 輸出的每一行都是 JSON,可以用 `jq` 過濾/解析,
  每一行都帶有 `traceId`/`spanId`,可以拿 Zipkin 上看到的 trace id 回頭到 log 裡搜尋同一次
  請求的完整處理過程。
- **自訂搶購指標**:`purchase.reservation`(tag `outcome=reserved|insufficient_stock`)、
  `purchase.reservation.latency`、`purchase.order.created` 這三個 Micrometer 指標可以透過
  `/actuator/metrics/{name}` 查詢,反映 Redis 預扣成功/售罄次數與延遲分布。

## CI

[![CI](https://github.com/kevintsai1325/FlashSale/actions/workflows/ci.yml/badge.svg)](https://github.com/kevintsai1325/FlashSale/actions/workflows/ci.yml)

GitHub Actions 在每次 push 到 `main` 或開 PR 時,平行執行 backend(`./gradlew test`,涵蓋
unit/application/integration/API/ArchUnit 測試)與 frontend(lint、型別檢查、build、
vitest)兩個 job。CI 只驗證 build+test 通過,不包含映像檔建置/推送/部署。
```

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: document observability stack and CI in README"
```

- [ ] **Step 7: 關閉本機 stack**

Run: `docker compose down`

---

## 執行順序與收尾

Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8(Task 7 跟 1-6 沒有依賴關係,可以視執行方式調整順序,但
Task 8 一定要放最後,因為它要驗證前面全部任務的整體效果)。全部任務完成、個別 review 都過了以後,
依照 `superpowers:finishing-a-development-branch` 的既有流程(跟 Week 2-4 一樣)做一次
whole-branch final review,通過後 merge 回本地 `main`——merge 前跟 push 一樣需要先跟使用者
確認。
