# FlashSale Week 2 (搶購核心) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Week 1's synchronous, pessimistic-lock purchase flow with the async pipeline from `docs/superpowers/specs/2026-08-13-flash-sale-week2-purchase-core-design.md`: Redis Lua stock pre-deduction, RabbitMQ-driven order creation via a transactional outbox, consumer dedup, a DLQ compensation path, simulated payment, order cancellation, payment-timeout compensation, Redis/Postgres reconciliation, and Nginx rate limiting on the purchase endpoint — all backend-only (frontend is Week 3).

**Architecture:** Same modular monolith (`com.flashsale.<module>.{domain,application,adapter}`) as Week 1. New shared infrastructure lives in `common/messaging` (outbox + consumer dedup) and `common/config` (Redis/RabbitMQ topology). `CreatePurchaseRequestService` no longer touches Postgres inventory/orders directly — it only does the idempotency/activity/duplicate-purchase checks it already had, then calls a new `InventoryStockGateway` (Redis) and writes an outbox event. A new `payment` module owns simulated payment. A single `OrderCompensationService` (order module) is the one place that transitions an `Order` to a terminal non-PAID state and releases inventory — reused by cancel, payment failure, and the payment-timeout scheduler, and reachable from the DLQ path too.

**Tech Stack:** Same as Week 1 (Java 21, Spring Boot 3.3.4, Spring Data JPA/PostgreSQL, JUnit 5/AssertJ/Mockito, Spring Boot Test/MockMvc, Testcontainers, Docker Compose, Nginx), plus Spring Data Redis, Spring AMQP (RabbitMQ), Testcontainers RabbitMQ module.

## Global Constraints

- Java 21 toolchain, Spring Boot 3.3.4, Gradle Kotlin DSL wrapper — unchanged from Week 1.
- All schema is already in `backend/src/main/resources/db/migration/V1__baseline_schema.sql` (Week 1 created the full baseline including `outbox_events`, `consumed_messages`, `payment_records`). **No new Flyway migration in this plan** — every table this plan uses already exists.
- Package by module first, then `domain`/`application`/`adapter` within each module. Modules depend only on `common`'s interfaces and other modules' `domain`/`application` — never another module's `adapter`. `common/messaging` and `common/config` are flat (no domain/application/adapter split), matching the main spec §5 package diagram.
- Only create interfaces at real seams (repository, message publisher, inventory reservation). `common/messaging`'s `OutboxEventJpaRepository`/`ConsumedMessageJpaRepository` are used directly (no port/adapter split) since they have no alternate implementation and no test double need — Testcontainers Postgres is the test double.
- Errors are Spring `ProblemDetail` via the existing `GlobalExceptionHandler`/`ProblemDetails`/`DomainException` hierarchy (`NotFoundException`→404, `ConflictException`→409, new `ServiceUnavailableException`→503).
- TDD: Red → Green → Refactor per use case. No tests for framework code or trivial getters.
- Every new Integration Test (`*IT.java`) that boots the full Spring context needs Postgres via Testcontainers (as Week 1's ITs already do); from Task 5 onward it also needs Redis and RabbitMQ via Testcontainers, since `InventoryStockGateway`/`OutboxPublisher` beans are always present in the context. Follow the existing per-class `@Container`/`@DynamicPropertySource` pattern (no shared base class — Week 1 explicitly deferred that DRY cleanup, don't introduce it here either).
- Redis key convention: `stock:{flashSaleId}`, no TTL.
- RabbitMQ: one exchange (`order.exchange`, direct), two queues (`order.create.queue`, `stock.release.queue`), each with its own DLX/DLQ configured via queue arguments (`x-dead-letter-exchange`/`x-dead-letter-routing-key`) — retries are Spring Boot's built-in `spring.rabbitmq.listener.simple.retry` (3 attempts, 1s→2s→4s backoff); when retries are exhausted, the default `RejectAndDontRequeueRecoverer` naturally routes the message to its DLX/DLQ (no custom `MessageRecoverer` needed).
- Consumer dedup uses the existing `consumed_messages.message_id` unique constraint via `INSERT ... ON CONFLICT DO NOTHING`, never try/catch on `DataIntegrityViolationException` inside an ongoing `@Transactional` method (that poisons the transaction in JPA/Hibernate).
- Outbox message bodies are sent as raw JSON bytes (`RabbitTemplate.send`, not `convertAndSend`) with `outboxEventId` as a message header — avoids `Jackson2JsonMessageConverter` type-header mismatches between producer and consumer.

---

## File Structure

```text
backend/src/main/java/com/flashsale/
├─ common/
│  ├─ config/
│  │  ├─ RedisConfig.java              # Task 2 — Lua script bean
│  │  └─ RabbitConfig.java             # Task 3 — exchange/queue/DLX/DLQ topology
│  ├─ exception/
│  │  └─ ServiceUnavailableException.java  # Task 2
│  └─ messaging/
│     ├─ OutboxEvent.java              # Task 3
│     ├─ OutboxEventJpaRepository.java # Task 3
│     ├─ OutboxWriter.java             # Task 3
│     ├─ EventTypes.java               # Task 3
│     ├─ OutboxPublisher.java          # Task 3
│     ├─ ConsumedMessage.java          # Task 4
│     ├─ ConsumedMessageJpaRepository.java  # Task 4
│     └─ ConsumedMessageGuard.java     # Task 4
├─ inventory/
│  ├─ domain/Inventory.java            # Task 2 — add release()
│  ├─ application/
│  │  ├─ InventoryRepository.java      # Task 2 — add findByFlashSaleId
│  │  ├─ InventoryStockGateway.java    # Task 2
│  │  ├─ StockReservationResult.java   # Task 2
│  │  ├─ event/StockReleaseRequestedEvent.java  # Task 8
│  │  └─ InventoryReconciliationScheduler.java  # Task 13
│  └─ adapter/
│     ├─ redis/RedisInventoryStockGateway.java  # Task 2
│     └─ messaging/StockReleaseConsumer.java    # Task 11
├─ order/
│  ├─ domain/
│  │  ├─ Order.java                    # Task 9 — add pay/cancel/markExpired/totalQuantity
│  │  └─ PurchaseRequest.java          # Task 5 — pending/markSucceeded/markFailed
│  ├─ application/
│  │  ├─ PurchaseRequestRepository.java  # Task 5 findById, Task 9 findByOrderId
│  │  ├─ OrderRepository.java          # Task 11 — add findPendingPaymentPastDue
│  │  ├─ CreatePurchaseRequestService.java  # Task 5 — rewritten
│  │  ├─ OrderCompensationService.java # Task 9
│  │  ├─ CancelOrderService.java       # Task 9
│  │  ├─ PaymentTimeoutScheduler.java  # Task 11
│  │  └─ event/CreateOrderRequestedEvent.java  # Task 5
│  └─ adapter/
│     ├─ web/OrderController.java      # Task 9 — add /cancel
│     └─ messaging/
│        ├─ OrderPurchaseConsumer.java # Task 6
│        └─ OrderCreateDlqHandler.java # Task 8
└─ payment/                            # Task 10, new module
   ├─ domain/PaymentRecord.java, PaymentResult.java
   ├─ application/PaymentRecordRepository.java, SubmitPaymentService.java
   └─ adapter/
      ├─ persistence/PaymentRecordJpaRepository.java, PaymentRecordRepositoryImpl.java
      └─ web/PaymentController.java, dto/SubmitPaymentRequest.java
```

---

## Task 1: Redis + RabbitMQ Dependencies, Compose Wiring, Smoke Test

**Files:**
- Modify: `backend/build.gradle.kts` (add `spring-boot-starter-data-redis`, `spring-boot-starter-amqp`, `org.testcontainers:rabbitmq`)
- Modify: `backend/src/main/resources/application.yml` (add `spring.data.redis.*`, `spring.rabbitmq.*`)
- Modify: `compose.yaml` (backend service: Redis/RabbitMQ env vars + `depends_on`)
- Test: `backend/src/test/java/com/flashsale/RedisRabbitConnectivityIT.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `StringRedisTemplate` and `RabbitTemplate` beans (both Spring Boot auto-configured once the starters are on the classpath and `spring.data.redis.*`/`spring.rabbitmq.*` are set) — every later task injects these directly, no wrapper needed.

- [ ] **Step 1: Write the failing smoke test**

`backend/src/test/java/com/flashsale/RedisRabbitConnectivityIT.java`:
```java
package com.flashsale;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class RedisRabbitConnectivityIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void applicationContextLoadsWithRedisAndRabbitMqReachable() {
        redisTemplate.opsForValue().set("smoke-test-key", "ok");
        assertThat(redisTemplate.opsForValue().get("smoke-test-key")).isEqualTo("ok");
        assertThat(rabbitTemplate.getConnectionFactory().createConnection().isOpen()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.RedisRabbitConnectivityIT"`
Expected: FAIL to compile — `StringRedisTemplate`/`RabbitTemplate`/`RabbitMQContainer` are not on the classpath yet.

- [ ] **Step 3: Add dependencies and configuration**

In `backend/build.gradle.kts`, add to the `dependencies` block:
```kotlin
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
```
and to `testImplementation`:
```kotlin
    testImplementation("org.testcontainers:rabbitmq:1.20.1")
```

In `backend/src/main/resources/application.yml`, add under `spring:`:
```yaml
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
  rabbitmq:
    host: ${SPRING_RABBITMQ_HOST:localhost}
    port: ${SPRING_RABBITMQ_PORT:5672}
    username: ${SPRING_RABBITMQ_USERNAME:guest}
    password: ${SPRING_RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000
          multiplier: 2.0
          max-interval: 10000
        default-requeue-rejected: false
```

In `compose.yaml`, update the `backend` service:
```yaml
  backend:
    build: ./backend
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/flashsale
      SPRING_DATASOURCE_USERNAME: flashsale
      SPRING_DATASOURCE_PASSWORD: flashsale
      MAIL_HOST: mailpit
      MAIL_PORT: 1025
      GMAIL_USERNAME: ${GMAIL_USERNAME:-}
      GMAIL_APP_PASSWORD: ${GMAIL_APP_PASSWORD:-}
      JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY}
      JWT_PUBLIC_KEY: ${JWT_PUBLIC_KEY}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: guest
      SPRING_RABBITMQ_PASSWORD: guest
    depends_on:
      postgres:
        condition: service_healthy
      mailpit:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
```
(Only the `environment` and `depends_on` blocks change; leave `build`/`healthcheck` as-is.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.RedisRabbitConnectivityIT"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/resources/application.yml compose.yaml backend/src/test/java/com/flashsale/RedisRabbitConnectivityIT.java
git commit -m "feat: wire Redis and RabbitMQ into the backend"
```

---

## Task 2: Inventory.release() + Redis-Backed InventoryStockGateway

**Files:**
- Modify: `backend/src/main/java/com/flashsale/inventory/domain/Inventory.java` (add `release(int)`)
- Modify: `backend/src/main/java/com/flashsale/inventory/application/InventoryRepository.java` (add `findByFlashSaleId`)
- Modify: `backend/src/main/java/com/flashsale/inventory/adapter/persistence/InventoryJpaRepository.java`, `InventoryRepositoryImpl.java` (implement it)
- Create: `backend/src/main/java/com/flashsale/inventory/application/InventoryStockGateway.java`, `StockReservationResult.java`
- Create: `backend/src/main/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGateway.java`
- Create: `backend/src/main/java/com/flashsale/common/config/RedisConfig.java`
- Create: `backend/src/main/java/com/flashsale/common/exception/ServiceUnavailableException.java`
- Modify: `backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java` (map it to 503)
- Create: `backend/src/main/resources/redis/reserve-stock.lua`
- Test: `backend/src/test/java/com/flashsale/inventory/domain/InventoryTest.java` (extend existing or create if absent — check first)
- Test: `backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayIT.java`

**Interfaces:**
- Consumes: `InventoryRepository` (existing, extended).
- Produces: `Inventory.release(int quantity)`; `InventoryStockGateway { StockReservationResult reserve(Long flashSaleId, int quantity); void release(Long flashSaleId, int quantity); Optional<Integer> currentValue(Long flashSaleId); void resync(Long flashSaleId, int authoritativeQuantity); }`; `StockReservationResult { RESERVED, INSUFFICIENT_STOCK }`; `ServiceUnavailableException(code, detail)`. Task 5 consumes `InventoryStockGateway.reserve`. Task 9's `OrderCompensationService` and Task 11's `StockReleaseConsumer` consume `InventoryStockGateway.release`. Task 13's reconciliation scheduler consumes `currentValue`/`resync`.

- [ ] **Step 1: Write the failing domain test for `Inventory.release()`**

Check whether `backend/src/test/java/com/flashsale/inventory/domain/InventoryTest.java` already exists (Week 1's finding #5 fix added one for `sell()`/`hasStock()`). Add these cases to it (create the file with just these two tests if it doesn't exist):

```java
    @Test
    void releaseAddsQuantityBackToAvailableAndRemovesFromSold() {
        Inventory inventory = Inventory.initialize(1L, 10);
        inventory.sell(3);

        inventory.release(3);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getSoldQuantity()).isEqualTo(0);
    }

    @Test
    void releaseIsTheExactInverseOfSell() {
        Inventory inventory = Inventory.initialize(1L, 5);
        inventory.sell(5);

        inventory.release(2);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
        assertThat(inventory.getSoldQuantity()).isEqualTo(3);
    }
```
(If the file doesn't exist yet, create it with `package com.flashsale.inventory.domain;`, the two imports `org.junit.jupiter.api.Test` and `static org.assertj.core.api.Assertions.assertThat`, and a `class InventoryTest {}` wrapping these two methods.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.domain.InventoryTest"`
Expected: FAIL to compile — `Inventory.release(int)` doesn't exist.

- [ ] **Step 3: Implement `Inventory.release()`**

Add to `backend/src/main/java/com/flashsale/inventory/domain/Inventory.java`, right after `sell()`:
```java
    public void release(int quantity) {
        availableQuantity += quantity;
        soldQuantity -= quantity;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.domain.InventoryTest"`
Expected: PASS

- [ ] **Step 5: Add the non-locking read method to InventoryRepository**

Add to `backend/src/main/java/com/flashsale/inventory/application/InventoryRepository.java`:
```java
    Optional<Inventory> findByFlashSaleId(Long flashSaleId);
```
(interface becomes `{ findByFlashSaleIdForUpdate, findByFlashSaleId, save }`)

Add to `backend/src/main/java/com/flashsale/inventory/adapter/persistence/InventoryJpaRepository.java`:
```java
    Optional<Inventory> findByFlashSaleId(Long flashSaleId);
```
(a plain Spring Data derived query, no `@Lock` — this is `JpaRepository`'s standard derived-query mechanism, distinct from the existing `@Lock(PESSIMISTIC_WRITE)` query above it.)

Add to `backend/src/main/java/com/flashsale/inventory/adapter/persistence/InventoryRepositoryImpl.java`:
```java
    @Override
    public Optional<Inventory> findByFlashSaleId(Long flashSaleId) {
        return jpaRepository.findByFlashSaleId(flashSaleId);
    }
```

- [ ] **Step 6: Write the failing Redis gateway IT**

`backend/src/test/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGatewayIT.java`:
```java
package com.flashsale.inventory.adapter.redis;

import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class RedisInventoryStockGatewayIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired InventoryStockGateway gateway;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedFlashSaleAndInventory() {
        jdbcTemplate.update("DELETE FROM inventory");
        jdbcTemplate.update("DELETE FROM flash_sales");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("INSERT INTO products (id, name, description) VALUES (1, 'Sneakers', 'desc')");
        jdbcTemplate.update("INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "VALUES (42, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "VALUES (1, 42, 3, 3, 0, 0, 0)");
    }

    @Test
    void lazilySeedsFromPostgresThenReservesAtomically() {
        StockReservationResult first = gateway.reserve(42L, 1);
        StockReservationResult second = gateway.reserve(42L, 1);
        StockReservationResult third = gateway.reserve(42L, 1);
        StockReservationResult fourth = gateway.reserve(42L, 1);

        assertThat(first).isEqualTo(StockReservationResult.RESERVED);
        assertThat(second).isEqualTo(StockReservationResult.RESERVED);
        assertThat(third).isEqualTo(StockReservationResult.RESERVED);
        assertThat(fourth).isEqualTo(StockReservationResult.INSUFFICIENT_STOCK);
        assertThat(gateway.currentValue(42L)).contains(0);
    }

    @Test
    void releaseIncrementsStockBackUp() {
        gateway.reserve(42L, 2);

        gateway.release(42L, 2);

        assertThat(gateway.currentValue(42L)).contains(3);
    }

    @Test
    void resyncOverwritesWithAuthoritativeValue() {
        gateway.reserve(42L, 3);

        gateway.resync(42L, 3);

        assertThat(gateway.currentValue(42L)).contains(3);
    }
}
```

- [ ] **Step 7: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.adapter.redis.RedisInventoryStockGatewayIT"`
Expected: FAIL to compile — `InventoryStockGateway` doesn't exist.

- [ ] **Step 8: Implement the Lua script, the gateway interface, and the Redis adapter**

`backend/src/main/resources/redis/reserve-stock.lua`:
```lua
-- KEYS[1] = stock key, ARGV[1] = quantity to reserve
local stock = redis.call('GET', KEYS[1])
if stock == false then
  return -2
end
stock = tonumber(stock)
local qty = tonumber(ARGV[1])
if stock >= qty then
  redis.call('DECRBY', KEYS[1], qty)
  return stock - qty
end
return -1
```

`backend/src/main/java/com/flashsale/common/config/RedisConfig.java`:
```java
package com.flashsale.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> reserveStockScript() {
        return RedisScript.of(new ClassPathResource("redis/reserve-stock.lua"), Long.class);
    }
}
```

`backend/src/main/java/com/flashsale/common/exception/ServiceUnavailableException.java`:
```java
package com.flashsale.common.exception;

public class ServiceUnavailableException extends DomainException {
    public ServiceUnavailableException(String code, String detail) {
        super(code, detail);
    }
}
```

Add to `backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java`:
```java
    @ExceptionHandler(ServiceUnavailableException.class)
    public ProblemDetail handleServiceUnavailable(ServiceUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getCode(), ex.getMessage(), request);
    }
```

`backend/src/main/java/com/flashsale/inventory/application/StockReservationResult.java`:
```java
package com.flashsale.inventory.application;

public enum StockReservationResult {
    RESERVED, INSUFFICIENT_STOCK
}
```

`backend/src/main/java/com/flashsale/inventory/application/InventoryStockGateway.java`:
```java
package com.flashsale.inventory.application;

import java.util.Optional;

public interface InventoryStockGateway {
    StockReservationResult reserve(Long flashSaleId, int quantity);
    void release(Long flashSaleId, int quantity);
    Optional<Integer> currentValue(Long flashSaleId);
    void resync(Long flashSaleId, int authoritativeQuantity);
}
```

`backend/src/main/java/com/flashsale/inventory/adapter/redis/RedisInventoryStockGateway.java`:
```java
package com.flashsale.inventory.adapter.redis;

import com.flashsale.common.exception.ServiceUnavailableException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class RedisInventoryStockGateway implements InventoryStockGateway {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> reserveStockScript;
    private final InventoryRepository inventoryRepository;

    public RedisInventoryStockGateway(StringRedisTemplate redisTemplate, RedisScript<Long> reserveStockScript,
                                       InventoryRepository inventoryRepository) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = reserveStockScript;
        this.inventoryRepository = inventoryRepository;
    }

    private String stockKey(Long flashSaleId) {
        return "stock:" + flashSaleId;
    }

    private void ensureSeeded(Long flashSaleId) {
        if (Boolean.FALSE.equals(redisTemplate.hasKey(stockKey(flashSaleId)))) {
            int available = inventoryRepository.findByFlashSaleId(flashSaleId)
                .map(Inventory::getAvailableQuantity)
                .orElseThrow(() -> new NotFoundException("INVENTORY_NOT_FOUND",
                    "Inventory for flash sale " + flashSaleId + " does not exist"));
            redisTemplate.opsForValue().setIfAbsent(stockKey(flashSaleId), String.valueOf(available));
        }
    }

    @Override
    public StockReservationResult reserve(Long flashSaleId, int quantity) {
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
        return remaining == -1 ? StockReservationResult.INSUFFICIENT_STOCK : StockReservationResult.RESERVED;
    }

    @Override
    public void release(Long flashSaleId, int quantity) {
        redisTemplate.opsForValue().increment(stockKey(flashSaleId), quantity);
    }

    @Override
    public Optional<Integer> currentValue(Long flashSaleId) {
        String value = redisTemplate.opsForValue().get(stockKey(flashSaleId));
        return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
    }

    @Override
    public void resync(Long flashSaleId, int authoritativeQuantity) {
        redisTemplate.opsForValue().set(stockKey(flashSaleId), String.valueOf(authoritativeQuantity));
    }
}
```

- [ ] **Step 9: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.adapter.redis.RedisInventoryStockGatewayIT"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/flashsale/inventory backend/src/main/java/com/flashsale/common/config/RedisConfig.java backend/src/main/java/com/flashsale/common/exception/ServiceUnavailableException.java backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java backend/src/main/resources/redis backend/src/test/java/com/flashsale/inventory
git commit -m "feat: add Inventory.release() and Redis-backed InventoryStockGateway"
```

---

## Task 3: Outbox Infrastructure + RabbitMQ Topology + OutboxPublisher

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/messaging/OutboxEvent.java`, `OutboxEventJpaRepository.java`, `OutboxWriter.java`, `EventTypes.java`, `OutboxPublisher.java`
- Create: `backend/src/main/java/com/flashsale/common/config/RabbitConfig.java`
- Modify: `backend/src/main/resources/application.yml` (enable `@EnableScheduling` prerequisites — see Step 6)
- Test: `backend/src/test/java/com/flashsale/common/messaging/OutboxPublisherIT.java`

**Interfaces:**
- Consumes: `RabbitTemplate` (Task 1).
- Produces: `OutboxWriter.write(String aggregateType, String aggregateId, String eventType, Object payload)` — Task 5 and Task 8/9 call this inside their own `@Transactional` methods to record an event in the same transaction as their domain change. `EventTypes.CREATE_ORDER_REQUESTED`/`STOCK_RELEASE_REQUESTED` — the two event-type string constants every producer/consumer task must use verbatim. `RabbitConfig.ORDER_EXCHANGE`, `CREATE_ORDER_QUEUE`, `CREATE_ORDER_ROUTING_KEY`, `CREATE_ORDER_DLQ`, `STOCK_RELEASE_QUEUE`, `STOCK_RELEASE_ROUTING_KEY`, `STOCK_RELEASE_DLQ` — queue/routing-key names every `@RabbitListener` task binds to.

- [ ] **Step 1: Write the failing OutboxPublisher IT**

`backend/src/test/java/com/flashsale/common/messaging/OutboxPublisherIT.java`:
```java
package com.flashsale.common.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class OutboxPublisherIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired OutboxWriter outboxWriter;
    @Autowired RabbitTemplate rabbitTemplate;

    record Dummy(String note) {}

    @Test
    void writtenEventIsPublishedToRabbitAndMarkedPublished() {
        outboxWriter.write("Test", "1", EventTypes.CREATE_ORDER_REQUESTED, new Dummy("hello"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(com.flashsale.common.config.RabbitConfig.CREATE_ORDER_QUEUE);
            assertThat(message).isNotNull();
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("hello");
            assertThat(message.getMessageProperties().getHeaders()).containsKey("outboxEventId");
        });
    }
}
```

Add the Awaitility test dependency needed by this and later async ITs — add to `testImplementation` in `backend/build.gradle.kts`:
```kotlin
    testImplementation("org.awaitility:awaitility:4.2.2")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.messaging.OutboxPublisherIT"`
Expected: FAIL to compile — `OutboxWriter`, `EventTypes`, `RabbitConfig` don't exist.

- [ ] **Step 3: Implement the RabbitMQ topology**

`backend/src/main/java/com/flashsale/common/config/RabbitConfig.java`:
```java
package com.flashsale.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.exchange";

    public static final String CREATE_ORDER_QUEUE = "order.create.queue";
    public static final String CREATE_ORDER_ROUTING_KEY = "order.create";
    public static final String CREATE_ORDER_DLX = "order.create.dlx";
    public static final String CREATE_ORDER_DLQ = "order.create.queue.dlq";

    public static final String STOCK_RELEASE_QUEUE = "stock.release.queue";
    public static final String STOCK_RELEASE_ROUTING_KEY = "stock.release";
    public static final String STOCK_RELEASE_DLX = "stock.release.dlx";
    public static final String STOCK_RELEASE_DLQ = "stock.release.queue.dlq";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    @Bean
    public DirectExchange createOrderDlx() {
        return new DirectExchange(CREATE_ORDER_DLX);
    }

    @Bean
    public Queue createOrderQueue() {
        return QueueBuilder.durable(CREATE_ORDER_QUEUE)
            .withArgument("x-dead-letter-exchange", CREATE_ORDER_DLX)
            .withArgument("x-dead-letter-routing-key", CREATE_ORDER_ROUTING_KEY)
            .build();
    }

    @Bean
    public Queue createOrderDlq() {
        return QueueBuilder.durable(CREATE_ORDER_DLQ).build();
    }

    @Bean
    public Binding createOrderBinding(Queue createOrderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(createOrderQueue).to(orderExchange).with(CREATE_ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding createOrderDlqBinding(Queue createOrderDlq, DirectExchange createOrderDlx) {
        return BindingBuilder.bind(createOrderDlq).to(createOrderDlx).with(CREATE_ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange stockReleaseDlx() {
        return new DirectExchange(STOCK_RELEASE_DLX);
    }

    @Bean
    public Queue stockReleaseQueue() {
        return QueueBuilder.durable(STOCK_RELEASE_QUEUE)
            .withArgument("x-dead-letter-exchange", STOCK_RELEASE_DLX)
            .withArgument("x-dead-letter-routing-key", STOCK_RELEASE_ROUTING_KEY)
            .build();
    }

    @Bean
    public Queue stockReleaseDlq() {
        return QueueBuilder.durable(STOCK_RELEASE_DLQ).build();
    }

    @Bean
    public Binding stockReleaseBinding(Queue stockReleaseQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(stockReleaseQueue).to(orderExchange).with(STOCK_RELEASE_ROUTING_KEY);
    }

    @Bean
    public Binding stockReleaseDlqBinding(Queue stockReleaseDlq, DirectExchange stockReleaseDlx) {
        return BindingBuilder.bind(stockReleaseDlq).to(stockReleaseDlx).with(STOCK_RELEASE_ROUTING_KEY);
    }
}
```

- [ ] **Step 4: Implement the OutboxEvent entity and its Spring Data repository**

`backend/src/main/java/com/flashsale/common/messaging/OutboxEvent.java`:
```java
package com.flashsale.common.messaging;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    public static OutboxEvent create(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payloadJson;
        event.createdAt = Instant.now();
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
}
```

`backend/src/main/java/com/flashsale/common/messaging/OutboxEventJpaRepository.java`:
```java
package com.flashsale.common.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
        nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatchForUpdate(@Param("limit") int limit);
}
```

`backend/src/main/java/com/flashsale/common/messaging/EventTypes.java`:
```java
package com.flashsale.common.messaging;

public final class EventTypes {
    public static final String CREATE_ORDER_REQUESTED = "CreateOrderRequested";
    public static final String STOCK_RELEASE_REQUESTED = "StockReleaseRequested";

    private EventTypes() {}
}
```

`backend/src/main/java/com/flashsale/common/messaging/OutboxWriter.java`:
```java
package com.flashsale.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(OutboxEvent.create(aggregateType, aggregateId, eventType, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
    }
}
```

`backend/src/main/java/com/flashsale/common/messaging/OutboxPublisher.java`:
```java
package com.flashsale.common.messaging;

import com.flashsale.common.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class OutboxPublisher {

    private static final int BATCH_SIZE = 50;

    private final OutboxEventJpaRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(OutboxEventJpaRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findUnpublishedBatchForUpdate(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setHeader("outboxEventId", event.getId());
            Message message = new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, routingKeyFor(event.getEventType()), message);
            event.markPublished();
        }
    }

    private String routingKeyFor(String eventType) {
        return switch (eventType) {
            case EventTypes.CREATE_ORDER_REQUESTED -> RabbitConfig.CREATE_ORDER_ROUTING_KEY;
            case EventTypes.STOCK_RELEASE_REQUESTED -> RabbitConfig.STOCK_RELEASE_ROUTING_KEY;
            default -> throw new IllegalStateException("Unknown outbox event type: " + eventType);
        };
    }
}
```

- [ ] **Step 5: Enable scheduling**

Check `backend/src/main/java/com/flashsale/FlashSaleApplication.java` — add `@EnableScheduling` to the `@SpringBootApplication` class if not already present (this plan's `OutboxPublisher`, and later `PaymentTimeoutScheduler`/`InventoryReconciliationScheduler`, all rely on `@Scheduled`).

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.messaging.OutboxPublisherIT"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/flashsale/common/messaging backend/src/main/java/com/flashsale/common/config/RabbitConfig.java backend/src/main/java/com/flashsale/FlashSaleApplication.java backend/build.gradle.kts backend/src/test/java/com/flashsale/common/messaging
git commit -m "feat: add transactional outbox and RabbitMQ topology"
```

---

## Task 4: Consumer Dedup — ConsumedMessage + ConsumedMessageGuard

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/messaging/ConsumedMessage.java`, `ConsumedMessageJpaRepository.java`, `ConsumedMessageGuard.java`
- Test: `backend/src/test/java/com/flashsale/common/messaging/ConsumedMessageGuardIT.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ConsumedMessageGuard.tryConsume(String messageId, String consumerName) -> boolean` (`true` = not seen before, caller should process; `false` = duplicate, caller should skip). Task 6, 8, 11's `@RabbitListener` methods call this **without** their own extra `@Transactional` boundary around the guard call — it must run inside the listener's own transaction so a later failure in the same method rolls the "consumed" marker back too.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/flashsale/common/messaging/ConsumedMessageGuardIT.java`:
```java
package com.flashsale.common.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class ConsumedMessageGuardIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired ConsumedMessageGuard guard;

    @Test
    void firstConsumeSucceedsSecondIsDuplicate() {
        boolean first = guard.tryConsume("msg-1", "test-consumer");
        boolean second = guard.tryConsume("msg-1", "test-consumer");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void differentMessageIdsAreIndependent() {
        boolean a = guard.tryConsume("msg-a", "test-consumer");
        boolean b = guard.tryConsume("msg-b", "test-consumer");

        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.messaging.ConsumedMessageGuardIT"`
Expected: FAIL to compile — `ConsumedMessageGuard` doesn't exist.

- [ ] **Step 3: Implement ConsumedMessage, its repository, and the guard**

`backend/src/main/java/com/flashsale/common/messaging/ConsumedMessage.java`:
```java
package com.flashsale.common.messaging;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "consumed_messages")
public class ConsumedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    @Column(name = "consumer_name", nullable = false)
    private String consumerName;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    protected ConsumedMessage() {}
}
```

`backend/src/main/java/com/flashsale/common/messaging/ConsumedMessageJpaRepository.java`:
```java
package com.flashsale.common.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumedMessageJpaRepository extends JpaRepository<ConsumedMessage, Long> {

    @Modifying
    @Query(value = "INSERT INTO consumed_messages (message_id, consumer_name, consumed_at) " +
        "VALUES (:messageId, :consumerName, now()) ON CONFLICT (message_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("messageId") String messageId, @Param("consumerName") String consumerName);
}
```

`backend/src/main/java/com/flashsale/common/messaging/ConsumedMessageGuard.java`:
```java
package com.flashsale.common.messaging;

import org.springframework.stereotype.Component;

@Component
public class ConsumedMessageGuard {

    private final ConsumedMessageJpaRepository repository;

    public ConsumedMessageGuard(ConsumedMessageJpaRepository repository) {
        this.repository = repository;
    }

    public boolean tryConsume(String messageId, String consumerName) {
        return repository.insertIfAbsent(messageId, consumerName) == 1;
    }
}
```

Note why this uses a native `ON CONFLICT DO NOTHING` upsert instead of `save()` + catching `DataIntegrityViolationException`: catching a constraint-violation exception inside an already-open JPA/Hibernate transaction marks that transaction rollback-only, which would silently undo the Order/Inventory writes the caller makes in the rest of the same `@Transactional` method — exactly the correctness bug transactional outbox/consumer-dedup is supposed to prevent.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.messaging.ConsumedMessageGuardIT"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flashsale/common/messaging backend/src/test/java/com/flashsale/common/messaging/ConsumedMessageGuardIT.java
git commit -m "feat: add consumer dedup via ConsumedMessageGuard"
```

---

## Task 5: Rewrite CreatePurchaseRequestService to the Async Flow

**Files:**
- Modify: `backend/src/main/java/com/flashsale/order/domain/PurchaseRequest.java` (replace `succeed()` with `pending()` + `markSucceeded()`/`markFailed()`)
- Modify: `backend/src/main/java/com/flashsale/order/application/PurchaseRequestRepository.java`, `adapter/persistence/PurchaseRequestJpaRepository.java`, `PurchaseRequestRepositoryImpl.java` (add `findById`)
- Create: `backend/src/main/java/com/flashsale/order/application/event/CreateOrderRequestedEvent.java`
- Modify: `backend/src/main/java/com/flashsale/order/application/CreatePurchaseRequestService.java` (full rewrite)
- Modify: `backend/src/test/java/com/flashsale/order/application/CreatePurchaseRequestServiceTest.java` (full rewrite for the new dependencies)
- Modify: `backend/src/test/java/com/flashsale/order/adapter/web/PurchaseControllerIT.java` (assertions now expect `PENDING`/`SOLD_OUT` immediately, not synchronous `SUCCEEDED`)

**Interfaces:**
- Consumes: `InventoryStockGateway.reserve` (Task 2), `OutboxWriter.write` (Task 3), `EventTypes.CREATE_ORDER_REQUESTED` (Task 3).
- Produces: `PurchaseRequest.pending(userId, flashSaleId, idempotencyKey)`, `.markSucceeded(Long orderId)`, `.markFailed()` — Task 6's consumer calls `markSucceeded`, Task 8's DLQ handler calls `markFailed`. `PurchaseRequestRepository.findById(Long id)` — Task 6 and Task 8 both look up the pending request by its Postgres id (carried in the event payload) rather than its public `requestId` UUID. `CreateOrderRequestedEvent(Long purchaseRequestId, Long userId, Long flashSaleId, Long productId, int quantity, BigDecimal unitPrice)` — the exact record Task 6's consumer and Task 8's DLQ handler deserialize.

- [ ] **Step 1: Update `PurchaseRequest` domain — replace `succeed()` with `pending()`/mutators**

In `backend/src/main/java/com/flashsale/order/domain/PurchaseRequest.java`, replace the `succeed(...)` static factory with:
```java
    public static PurchaseRequest pending(Long userId, Long flashSaleId, String idempotencyKey) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.PENDING, null);
    }
```
and add these mutators after the existing factories:
```java
    public void markSucceeded(Long orderId) {
        this.status = PurchaseRequestStatus.SUCCEEDED;
        this.orderId = orderId;
    }

    public void markFailed() {
        this.status = PurchaseRequestStatus.FAILED;
    }
```
(Leave `soldOut(...)` and `reject(...)` untouched — both are still created directly, terminal, in one step, exactly as before.)

- [ ] **Step 2: Add `findById` to PurchaseRequestRepository**

Add to `backend/src/main/java/com/flashsale/order/application/PurchaseRequestRepository.java`:
```java
    Optional<PurchaseRequest> findById(Long id);
```

Add to `backend/src/main/java/com/flashsale/order/adapter/persistence/PurchaseRequestRepositoryImpl.java`:
```java
    @Override
    public Optional<PurchaseRequest> findById(Long id) {
        return jpaRepository.findById(id);
    }
```
(`PurchaseRequestJpaRepository` already extends `JpaRepository<PurchaseRequest, Long>`, so `findById` is already available on it — no change needed there.)

- [ ] **Step 3: Create the CreateOrderRequestedEvent record**

`backend/src/main/java/com/flashsale/order/application/event/CreateOrderRequestedEvent.java`:
```java
package com.flashsale.order.application.event;

import java.math.BigDecimal;

public record CreateOrderRequestedEvent(Long purchaseRequestId, Long userId, Long flashSaleId, Long productId,
                                         int quantity, BigDecimal unitPrice) {}
```

- [ ] **Step 4: Write the failing rewritten service test**

Replace the full contents of `backend/src/test/java/com/flashsale/order/application/CreatePurchaseRequestServiceTest.java`:
```java
package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreatePurchaseRequestServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock InventoryStockGateway inventoryStockGateway;
    @Mock PurchaseRequestRepository purchaseRequestRepository;
    @Mock OutboxWriter outboxWriter;

    CreatePurchaseRequestService service;

    private FlashSale activeSale() {
        return FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
    }

    @Test
    void reservesStockAndWritesOutboxEventWhenStockAvailable() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-1"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 1)).thenReturn(StockReservationResult.RESERVED);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-1");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
        verify(outboxWriter).write(eq("PurchaseRequest"), any(), eq("CreateOrderRequested"), any());
    }

    @Test
    void marksSoldOutWhenRedisReportsInsufficientStock() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-2"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 1)).thenReturn(StockReservationResult.INSUFFICIENT_STOCK);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-2");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SOLD_OUT);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void repeatingSameIdempotencyKeyReturnsSameResultWithoutTouchingRedisOrOutbox() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        PurchaseRequest existing = PurchaseRequest.pending(1L, 10L, "idem-3");
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-3"))
            .thenReturn(Optional.of(existing));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-3");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }

    @Test
    void rejectsWhenUserAlreadyHasSuccessfulOrder() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-4"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(true);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-4");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.REJECTED);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }

    @Test
    void throwsConflictWhenFlashSaleNotActive() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        FlashSale notYetStarted = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-5"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(notYetStarted));

        assertThatThrownBy(() -> service.createPurchaseRequest(1L, 10L, "idem-5"))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.application.CreatePurchaseRequestServiceTest"`
Expected: FAIL — `CreatePurchaseRequestService`'s constructor still takes the old (`InventoryRepository`, `OrderRepository`) dependencies.

- [ ] **Step 6: Rewrite CreatePurchaseRequestService**

Replace the full contents of `backend/src/main/java/com/flashsale/order/application/CreatePurchaseRequestService.java`:
```java
package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CreatePurchaseRequestService {

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryStockGateway inventoryStockGateway;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OutboxWriter outboxWriter;

    public CreatePurchaseRequestService(FlashSaleRepository flashSaleRepository, InventoryStockGateway inventoryStockGateway,
                                         PurchaseRequestRepository purchaseRequestRepository, OutboxWriter outboxWriter) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryStockGateway = inventoryStockGateway;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public PurchaseRequest createPurchaseRequest(Long userId, Long flashSaleId, String idempotencyKey) {
        var existing = purchaseRequestRepository
            .findByUserIdAndFlashSaleIdAndIdempotencyKey(userId, flashSaleId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        FlashSale flashSale = flashSaleRepository.findById(flashSaleId)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "Flash sale " + flashSaleId + " does not exist"));

        if (!flashSale.isPurchasableAt(Instant.now())) {
            throw new ConflictException("FLASH_SALE_NOT_ACTIVE", "Flash sale is not currently active");
        }

        if (purchaseRequestRepository.existsSucceededForUserAndFlashSale(userId, flashSaleId)) {
            return purchaseRequestRepository.save(PurchaseRequest.reject(userId, flashSaleId, idempotencyKey));
        }

        int quantity = flashSale.getPurchaseLimitPerUser();
        StockReservationResult reservation = inventoryStockGateway.reserve(flashSaleId, quantity);
        if (reservation == StockReservationResult.INSUFFICIENT_STOCK) {
            return purchaseRequestRepository.save(PurchaseRequest.soldOut(userId, flashSaleId, idempotencyKey));
        }

        PurchaseRequest request = purchaseRequestRepository.save(PurchaseRequest.pending(userId, flashSaleId, idempotencyKey));

        outboxWriter.write("PurchaseRequest", request.getId().toString(), EventTypes.CREATE_ORDER_REQUESTED,
            new CreateOrderRequestedEvent(request.getId(), userId, flashSaleId, flashSale.getProductId(), quantity, flashSale.getSalePrice()));

        return request;
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.application.CreatePurchaseRequestServiceTest"`
Expected: PASS

- [ ] **Step 8: Adapt PurchaseControllerIT to the async contract**

In `backend/src/test/java/com/flashsale/order/adapter/web/PurchaseControllerIT.java`, the flow no longer resolves to `SUCCEEDED` synchronously — no consumer exists yet to process the outbox event (that arrives in Task 6). Add the Testcontainers Redis/RabbitMQ containers (this class now boots a context containing `InventoryStockGateway`/`OutboxPublisher` beans, same as every IT since Task 2) and change the happy-path assertion. Replace the class's container/property setup:
```java
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static org.testcontainers.containers.GenericContainer<?> redis =
        new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static org.testcontainers.containers.RabbitMQContainer rabbitmq =
        new org.testcontainers.containers.RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
```
Then in `purchaseFlowCoversLockedInventoryIdempotentReplaySoldOutAndOwnershipCheck()`, change the first-purchase assertion block from expecting `SUCCEEDED`/`orderId` present to expecting `PENDING`/`orderId` absent (the request is now just admitted for async processing, not resolved yet):
```java
        MvcResult firstPurchase = mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + firstUserToken)
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.orderId").isEmpty())
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andReturn();

        String firstBody = firstPurchase.getResponse().getContentAsString();
        String firstRequestId = objectMapper.readTree(firstBody).get("requestId").asText();
```
Remove the `firstOrderId` variable and every assertion downstream that depended on it (the replay assertion, the `GET /api/purchase-requests/{requestId}` assertion, and the final `GET /api/orders/me` assertion) — replace them with `PENDING`-based equivalents:
```java
        // Replay with the same idempotency key must return the identical requestId, not create
        // a second PurchaseRequest row or reserve stock twice.
        mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + firstUserToken)
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requestId").value(firstRequestId));

        // A second user competing for the same (now-exhausted, stock=1) inventory must be
        // told SOLD_OUT rather than succeeding or erroring.
        String secondUserToken = registerAndLogin("jack@example.com", "secret123");
        mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + secondUserToken)
                .header("Idempotency-Key", "key-2"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("SOLD_OUT"))
            .andExpect(jsonPath("$.orderId").isEmpty());

        mockMvc.perform(get("/api/purchase-requests/" + firstRequestId)
                .header("Authorization", "Bearer " + firstUserToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        // A different authenticated user must not be able to read another user's purchase
        // request by guessing/obtaining its requestId (IDOR check).
        mockMvc.perform(get("/api/purchase-requests/" + firstRequestId)
                .header("Authorization", "Bearer " + secondUserToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_NOT_FOUND"));
```
Remove the trailing `GET /api/orders/me` assertion block entirely (no order exists yet at this point in the test — that end-to-end path, including the eventual `SUCCEEDED` state and the created order, is covered by Task 6's `OrderPurchaseConsumerIT`). Also remove the class-level `@Transactional` annotation: with `InventoryStockGateway`/`OutboxPublisher` now real beans talking to real Testcontainers Redis/RabbitMQ, wrapping the whole test in one Postgres transaction is no longer necessary for the assertions this class makes, and `@Scheduled` methods (like `OutboxPublisher`) run on a separate thread outside the test's transaction anyway.

- [ ] **Step 9: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.web.PurchaseControllerIT"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/flashsale/order backend/src/test/java/com/flashsale/order
git commit -m "feat: rewrite CreatePurchaseRequestService to reserve via Redis and enqueue via outbox"
```

---

## Task 6: OrderPurchaseConsumer — Async Order Creation

**Files:**
- Create: `backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java`
- Test: `backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerIT.java`

**Interfaces:**
- Consumes: `ConsumedMessageGuard.tryConsume` (Task 4), `CreateOrderRequestedEvent` (Task 5), `RabbitConfig.CREATE_ORDER_QUEUE` (Task 3), existing `InventoryRepository.findByFlashSaleIdForUpdate`/`Inventory.sell()` (Week 1), existing `OrderRepository.save`/`Order.createPendingPayment()` (Week 1), `PurchaseRequestRepository.findById`/`markSucceeded` (Task 5).
- Produces: nothing new consumed by later tasks — this is where `PurchaseRequestStatus.SUCCEEDED` is finally reached, closing the loop Task 5 started.

- [ ] **Step 1: Write the failing end-to-end IT**

`backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerIT.java`:
```java
package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderPurchaseConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    // Test-only RSA key pair (same literal as every other web IT — see PurchaseControllerIT),
    // needed only because JwtKeyConfig requires JWT_PRIVATE_KEY/JWT_PUBLIC_KEY to build the app
    // context. Kept as a plain duplicated literal per this codebase's established convention
    // (see the Global Constraints note on the deferred shared-base-class DRY cleanup).
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

    private String registerAndLogin(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(requestBody(email, password)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(requestBody(email, password)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void pendingPurchaseRequestEventuallyBecomesSucceededWithARealOrder() throws Exception {
        String token = registerAndLogin("consumer-test@example.com", "secret123");

        MvcResult result = mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "consumer-key-1"))
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        String requestId = body.get("requestId").asText();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            MvcResult poll = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/purchase-requests/" + requestId)
                    .header("Authorization", "Bearer " + token))
                .andReturn();
            JsonNode polled = objectMapper.readTree(poll.getResponse().getContentAsString());
            assertThat(polled.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(polled.get("orderId").isNull()).isFalse();
        });

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        assertThat(orderCount).isEqualTo(1);
        Integer remainingStock = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(remainingStock).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerIT"`
Expected: FAIL — the request stays `PENDING` forever, `awaitility` times out after 10s (no consumer exists yet).

- [ ] **Step 3: Implement OrderPurchaseConsumer**

`backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java`:
```java
package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class OrderPurchaseConsumer {

    private static final String CONSUMER_NAME = "order-purchase-consumer";

    private final ConsumedMessageGuard consumedMessageGuard;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderPurchaseConsumer(ConsumedMessageGuard consumedMessageGuard, PurchaseRequestRepository purchaseRequestRepository,
                                  InventoryRepository inventoryRepository, OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.CREATE_ORDER_QUEUE)
    @Transactional
    public void handle(Message message) throws IOException {
        Long outboxEventId = (Long) message.getMessageProperties().getHeaders().get("outboxEventId");
        if (!consumedMessageGuard.tryConsume(String.valueOf(outboxEventId), CONSUMER_NAME)) {
            return;
        }

        CreateOrderRequestedEvent event = objectMapper.readValue(message.getBody(), CreateOrderRequestedEvent.class);

        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(event.purchaseRequestId())
            .orElseThrow(() -> new IllegalStateException("PurchaseRequest " + event.purchaseRequestId() + " not found"));

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(event.flashSaleId())
            .orElseThrow(() -> new IllegalStateException("Inventory for flash sale " + event.flashSaleId() + " not found"));
        if (!inventory.hasStock(event.quantity())) {
            throw new IllegalStateException(
                "Redis/Postgres stock drift: flash sale " + event.flashSaleId() + " has insufficient Postgres stock despite a Redis reservation");
        }
        inventory.sell(event.quantity());
        inventoryRepository.save(inventory);

        Order order = Order.createPendingPayment(event.userId(), event.productId(), event.quantity(), event.unitPrice());
        Order savedOrder = orderRepository.save(order);

        purchaseRequest.markSucceeded(savedOrder.getId());
        purchaseRequestRepository.save(purchaseRequest);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerIT"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java backend/src/test/java/com/flashsale/order/adapter/messaging
git commit -m "feat: create orders asynchronously via OrderPurchaseConsumer"
```

---

## Task 7: Rewrite PurchaseConcurrencyIT for the Async Flow + Consumer Redelivery Dedup Test

**Files:**
- Modify: `backend/src/test/java/com/flashsale/order/adapter/web/PurchaseConcurrencyIT.java` (rewrite: fire concurrent requests, poll to terminal state instead of asserting on the synchronous response)
- Test: `backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerRedeliveryIT.java`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: nothing new — this task is pure verification that the invariants promised by the design spec (§7, §14: no oversell, exactly one order per unit of stock, redelivered messages don't double-create orders) hold end to end.

- [ ] **Step 1: Rewrite PurchaseConcurrencyIT**

In `backend/src/test/java/com/flashsale/order/adapter/web/PurchaseConcurrencyIT.java`, add the Redis/RabbitMQ containers and properties (same block as Task 5 added to `PurchaseControllerIT` — `GenericContainer<?> redis`, `RabbitMQContainer rabbitmq`, and the matching `@DynamicPropertySource` entries), then replace the assertions in `onlyAsManyBuyersSucceedAsThereIsStockUnderRealConcurrentContention()` from checking the synchronous HTTP response status to polling each request to its terminal state:
```java
    @Test
    void onlyAsManyBuyersSucceedAsThereIsStockUnderRealConcurrentContention() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            tokens.add(registerAndLogin("buyer" + i + "@example.com", "secret123"));
        }

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_BUYERS);
        List<Callable<String>> tasks = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            String token = tokens.get(i);
            String idempotencyKey = "concurrent-key-" + i;
            tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey))
                    .andReturn();
                assertThat(result.getResponse().getStatus())
                    .as("purchase-requests must always return 202, never a raw 5xx, even under contention")
                    .isEqualTo(202);
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                String requestId = body.get("requestId").asText();
                String initialStatus = body.get("status").asText();
                // SOLD_OUT is already terminal — only PENDING requests need polling for the
                // consumer to resolve them to SUCCEEDED (or leave them stuck, which the test
                // below will catch via the await timeout).
                if (!"PENDING".equals(initialStatus)) {
                    return initialStatus;
                }
                return pollUntilTerminal(token, requestId);
            });
        }

        List<Future<String>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
        executor.shutdown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get());
        }

        Map<String, Long> counts = outcomes.stream()
            .collect(Collectors.groupingBy(status -> status, Collectors.counting()));

        assertThat(counts.getOrDefault("SUCCEEDED", 0L))
            .as("exactly as many buyers as there was stock must succeed, no more")
            .isEqualTo((long) STOCK);
        assertThat(counts.getOrDefault("SOLD_OUT", 0L))
            .as("every other concurrent buyer must be told SOLD_OUT, not succeed and not error")
            .isEqualTo((long) (CONCURRENT_BUYERS - STOCK));

        Integer remainingStock = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(remainingStock).as("inventory must never go negative or double-sell").isEqualTo(0);

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        assertThat(orderCount).as("exactly one order must exist, matching the single unit of stock").isEqualTo(STOCK);
    }

    private String pollUntilTerminal(String token, String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult poll = mockMvc.perform(get("/api/purchase-requests/" + requestId)
                    .header("Authorization", "Bearer " + token))
                .andReturn();
            String status = objectMapper.readTree(poll.getResponse().getContentAsString()).get("status").asText();
            if (!"PENDING".equals(status)) {
                return status;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Purchase request " + requestId + " never left PENDING within 10s");
    }
```
Add the two missing imports this needs: `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;` and the same Redis/RabbitMQ container imports used in Task 5's `PurchaseControllerIT` change.

- [ ] **Step 2: Run test to verify it passes (it's a rewrite of an already-passing test, not a new Red step)**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.web.PurchaseConcurrencyIT"`
Expected: PASS. If it fails, the most likely cause is the poll deadline being too short for this machine — the fixed 10s budget matches Task 6's IT and should be enough headroom over the 500ms outbox-poll interval plus 3-retry RabbitMQ consumption.

- [ ] **Step 3: Write the failing consumer-redelivery dedup test**

`backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerRedeliveryIT.java`:
```java
package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves {@link OrderPurchaseConsumer}'s {@code ConsumedMessageGuard} check actually prevents a
 * redelivered/duplicated message from creating a second order — simulates the redelivery
 * RabbitMQ would perform after an unacked message by manually publishing the exact same
 * {@code outboxEventId} header + body twice.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderPurchaseConsumerRedeliveryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void redeliveredMessageWithTheSameOutboxEventIdDoesNotCreateASecondOrder() throws Exception {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (999, 'redelivery@example.com', 'x', 'USER', 'ACTIVE')");
        jdbcTemplate.update(
            "insert into purchase_requests (id, request_id, idempotency_key, user_id, flash_sale_id, status) " +
            "values (999, gen_random_uuid(), 'redelivery-key', 999, 1, 'PENDING')");

        String payload = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("purchaseRequestId", 999);
            put("userId", 999);
            put("flashSaleId", 1);
            put("productId", 1);
            put("quantity", 1);
            put("unitPrice", 9.99);
        }});

        for (int i = 0; i < 2; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setHeader("outboxEventId", 555L);
            Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.CREATE_ORDER_ROUTING_KEY, message);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject("select status from purchase_requests where id = 999", String.class);
            assertThat(status).isEqualTo("SUCCEEDED");
        });

        Integer orderCount = jdbcTemplate.queryForObject(
            "select count(*) from orders where user_id = 999", Integer.class);
        assertThat(orderCount).as("a redelivered message with the same outboxEventId must not create a second order").isEqualTo(1);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerRedeliveryIT"`
Expected: FAIL if `ConsumedMessageGuard` isn't wired correctly, or PASS immediately if Task 4/6 were implemented correctly — this is a regression test for behavior Task 6 should have already produced. If it passes on the first run, that confirms Task 6's dedup wiring rather than indicating a missing Red step; either outcome is acceptable evidence, but run it once before Task 6's code existed (or temporarily comment out the `consumedMessageGuard.tryConsume` check) if you want to see it genuinely fail once.

- [ ] **Step 5: Confirm pass**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerRedeliveryIT"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/com/flashsale/order
git commit -m "test: prove async purchase flow never oversells and consumer redelivery is deduped"
```

---

## Task 8: OrderCreateDlqHandler — Compensate After Exhausted Retries

**Files:**
- Create: `backend/src/main/java/com/flashsale/inventory/application/event/StockReleaseRequestedEvent.java`
- Create: `backend/src/main/java/com/flashsale/order/adapter/messaging/OrderCreateDlqHandler.java`
- Test: `backend/src/test/java/com/flashsale/order/adapter/messaging/OrderCreateDlqHandlerIT.java`

**Interfaces:**
- Consumes: `RabbitConfig.CREATE_ORDER_DLQ` (Task 3), `PurchaseRequestRepository.findById`/`markFailed` (Task 5), `OutboxWriter.write` (Task 3).
- Produces: `StockReleaseRequestedEvent(Long flashSaleId, int quantity)` — Task 9's `OrderCompensationService` reuses this exact record (don't redefine it there), and Task 11's `StockReleaseConsumer` deserializes it.

- [ ] **Step 1: Create the StockReleaseRequestedEvent record**

`backend/src/main/java/com/flashsale/inventory/application/event/StockReleaseRequestedEvent.java`:
```java
package com.flashsale.inventory.application.event;

public record StockReleaseRequestedEvent(Long flashSaleId, int quantity) {}
```

- [ ] **Step 2: Write the failing DLQ handler IT**

`backend/src/test/java/com/flashsale/order/adapter/messaging/OrderCreateDlqHandlerIT.java`:
```java
package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Simulates a message that already exhausted its retries and landed on the DLQ directly (rather
 * than actually forcing {@link OrderPurchaseConsumer} to fail 3 times) — publishes straight to
 * {@code order.create.queue.dlq} and asserts the handler's compensation.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderCreateDlqHandlerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void dlqMessageMarksPurchaseRequestFailedAndWritesStockReleaseOutboxEvent() throws Exception {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (998, 'dlq@example.com', 'x', 'USER', 'ACTIVE')");
        jdbcTemplate.update(
            "insert into purchase_requests (id, request_id, idempotency_key, user_id, flash_sale_id, status) " +
            "values (998, gen_random_uuid(), 'dlq-key', 998, 1, 'PENDING')");

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("purchaseRequestId", 998);
            put("userId", 998);
            put("flashSaleId", 1);
            put("productId", 1);
            put("quantity", 1);
            put("unitPrice", 9.99);
        }});
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(RabbitConfig.CREATE_ORDER_DLX, RabbitConfig.CREATE_ORDER_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject("select status from purchase_requests where id = 998", String.class);
            assertThat(status).isEqualTo("FAILED");
        });

        Integer releaseEventCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'StockReleaseRequested' and payload::text like '%\"flashSaleId\":1%'",
            Integer.class);
        assertThat(releaseEventCount).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderCreateDlqHandlerIT"`
Expected: FAIL — `OrderCreateDlqHandler` doesn't exist, nothing consumes `order.create.queue.dlq`, so the assertion times out.

- [ ] **Step 4: Implement OrderCreateDlqHandler**

`backend/src/main/java/com/flashsale/order/adapter/messaging/OrderCreateDlqHandler.java`:
```java
package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class OrderCreateDlqHandler {

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public OrderCreateDlqHandler(PurchaseRequestRepository purchaseRequestRepository, OutboxWriter outboxWriter,
                                  ObjectMapper objectMapper) {
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.CREATE_ORDER_DLQ)
    @Transactional
    public void handle(Message message) throws IOException {
        CreateOrderRequestedEvent event = objectMapper.readValue(message.getBody(), CreateOrderRequestedEvent.class);

        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(event.purchaseRequestId())
            .orElseThrow(() -> new IllegalStateException("PurchaseRequest " + event.purchaseRequestId() + " not found"));

        if (purchaseRequest.getStatus() != PurchaseRequestStatus.PENDING) {
            // Already resolved (e.g. a duplicate DLQ delivery) — nothing left to compensate.
            return;
        }

        purchaseRequest.markFailed();
        purchaseRequestRepository.save(purchaseRequest);

        outboxWriter.write("PurchaseRequest", purchaseRequest.getId().toString(), EventTypes.STOCK_RELEASE_REQUESTED,
            new StockReleaseRequestedEvent(event.flashSaleId(), event.quantity()));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderCreateDlqHandlerIT"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flashsale/inventory/application/event backend/src/main/java/com/flashsale/order/adapter/messaging/OrderCreateDlqHandler.java backend/src/test/java/com/flashsale/order/adapter/messaging/OrderCreateDlqHandlerIT.java
git commit -m "feat: compensate purchase requests whose order creation exhausted retries"
```

---

## Task 9: Order State Machine + OrderCompensationService + Cancel Endpoint

**Files:**
- Modify: `backend/src/main/java/com/flashsale/order/domain/Order.java` (add `pay()`, `cancel()`, `markExpired()`, `totalQuantity()`)
- Modify: `backend/src/main/java/com/flashsale/order/application/PurchaseRequestRepository.java`, `adapter/persistence/PurchaseRequestJpaRepository.java`, `PurchaseRequestRepositoryImpl.java` (add `findByOrderId`)
- Create: `backend/src/main/java/com/flashsale/order/application/OrderCompensationService.java`, `CancelOrderService.java`
- Modify: `backend/src/main/java/com/flashsale/order/adapter/web/OrderController.java` (add `POST /{orderId}/cancel`)
- Test: `backend/src/test/java/com/flashsale/order/domain/OrderTest.java` (new)
- Test: `backend/src/test/java/com/flashsale/order/adapter/web/OrderCancelIT.java`

**Interfaces:**
- Consumes: `InventoryRepository.findByFlashSaleIdForUpdate`/`Inventory.release()` (Task 2), `OutboxWriter.write` (Task 3), `StockReleaseRequestedEvent` (Task 8).
- Produces: `Order.pay()`, `.cancel()`, `.markExpired()` (each throws `ConflictException` if `status != PENDING_PAYMENT`), `Order.totalQuantity()`. `PurchaseRequestRepository.findByOrderId(Long orderId)`. `OrderCompensationService { void cancel(Order order); void markExpired(Order order); void failPayment(Order order); }` — Task 10's `SubmitPaymentService` calls `failPayment`, Task 11's `PaymentTimeoutScheduler` calls `markExpired`. `POST /api/orders/{orderId}/cancel` (200, `OrderDetail`).

- [ ] **Step 1: Write the failing Order state-machine test**

`backend/src/test/java/com/flashsale/order/domain/OrderTest.java`:
```java
package com.flashsale.order.domain;

import com.flashsale.common.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class OrderTest {

    private Order pendingOrder() {
        return Order.createPendingPayment(1L, 10L, 2, new BigDecimal("9.99"));
    }

    @Test
    void payTransitionsPendingPaymentToPaid() {
        Order order = pendingOrder();
        order.pay();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void cancelTransitionsPendingPaymentToCancelled() {
        Order order = pendingOrder();
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void markExpiredTransitionsPendingPaymentToExpired() {
        Order order = pendingOrder();
        order.markExpired();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void cannotCancelAnAlreadyPaidOrder() {
        Order order = pendingOrder();
        order.pay();
        assertThatThrownBy(order::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void cannotPayAnAlreadyCancelledOrder() {
        Order order = pendingOrder();
        order.cancel();
        assertThatThrownBy(order::pay).isInstanceOf(ConflictException.class);
    }

    @Test
    void totalQuantitySumsAllItems() {
        Order order = Order.createPendingPayment(1L, 10L, 3, new BigDecimal("9.99"));
        assertThat(order.totalQuantity()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.domain.OrderTest"`
Expected: FAIL to compile — `pay()`/`cancel()`/`markExpired()`/`totalQuantity()` don't exist.

- [ ] **Step 3: Implement the Order state machine**

Add to `backend/src/main/java/com/flashsale/order/domain/Order.java`, after `createPendingPayment(...)`:
```java
    public void pay() {
        requirePendingPayment("ORDER_NOT_PAYABLE");
        status = OrderStatus.PAID;
    }

    public void cancel() {
        requirePendingPayment("ORDER_NOT_CANCELLABLE");
        status = OrderStatus.CANCELLED;
    }

    public void markExpired() {
        requirePendingPayment("ORDER_NOT_EXPIRABLE");
        status = OrderStatus.EXPIRED;
    }

    public int totalQuantity() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    private void requirePendingPayment(String code) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new com.flashsale.common.exception.ConflictException(code,
                "Order " + id + " is not awaiting payment (status=" + status + ")");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.domain.OrderTest"`
Expected: PASS

- [ ] **Step 5: Add findByOrderId to PurchaseRequestRepository**

Add to `backend/src/main/java/com/flashsale/order/application/PurchaseRequestRepository.java`:
```java
    Optional<PurchaseRequest> findByOrderId(Long orderId);
```

Add to `backend/src/main/java/com/flashsale/order/adapter/persistence/PurchaseRequestJpaRepository.java`:
```java
    Optional<PurchaseRequest> findByOrderId(Long orderId);
```

Add to `backend/src/main/java/com/flashsale/order/adapter/persistence/PurchaseRequestRepositoryImpl.java`:
```java
    @Override
    public Optional<PurchaseRequest> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }
```

- [ ] **Step 6: Write the failing cancel-endpoint IT**

`backend/src/test/java/com/flashsale/order/adapter/web/OrderCancelIT.java`:
```java
package com.flashsale.order.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class OrderCancelIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private String registerAndLogin(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("email", email);
            put("password", "secret123");
        }});
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void cancellingAPendingOrderReleasesInventoryAndWritesCompensationEvent() throws Exception {
        String token = registerAndLogin("cancel-test@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (777, 'ORD-777', (select id from users where email = 'cancel-test@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, quantity, unit_price) values (777, 1, 1, 9.99)");
        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, order_id, status) " +
            "values (gen_random_uuid(), 'cancel-key', (select id from users where email = 'cancel-test@example.com'), 1, 777, 'SUCCEEDED')");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        mockMvc.perform(post("/api/orders/777/cancel").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);

        Integer releaseEventCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'StockReleaseRequested'", Integer.class);
        assertThat(releaseEventCount).isEqualTo(1);
    }

    @Test
    void cancellingSomeoneElsesOrderIsNotFoundNotForbidden() throws Exception {
        String owner = registerAndLogin("owner@example.com");
        String stranger = registerAndLogin("stranger@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (778, 'ORD-778', (select id from users where email = 'owner@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");

        mockMvc.perform(post("/api/orders/778/cancel").header("Authorization", "Bearer " + stranger))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 7: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.web.OrderCancelIT"`
Expected: FAIL — `POST /api/orders/{orderId}/cancel` doesn't exist (404 with no route, not the ownership 404 the second test expects at the right layer, and 200 never reached in the first test).

- [ ] **Step 8: Implement OrderCompensationService, CancelOrderService, and the controller endpoint**

`backend/src/main/java/com/flashsale/order/application/OrderCompensationService.java`:
```java
package com.flashsale.order.application;

import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCompensationService {

    private final OrderRepository orderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final InventoryRepository inventoryRepository;
    private final OutboxWriter outboxWriter;

    public OrderCompensationService(OrderRepository orderRepository, PurchaseRequestRepository purchaseRequestRepository,
                                     InventoryRepository inventoryRepository, OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.inventoryRepository = inventoryRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public void cancel(Order order) {
        order.cancel();
        compensate(order);
    }

    @Transactional
    public void markExpired(Order order) {
        order.markExpired();
        compensate(order);
    }

    @Transactional
    public void failPayment(Order order) {
        // Payment failure reuses the same CANCELLED terminal state as a user-initiated cancel —
        // PaymentRecord (Task 10) is what distinguishes "cancelled" from "payment failed" for
        // reporting purposes; the Order state machine itself only needs one non-PAID outcome.
        order.cancel();
        compensate(order);
    }

    private void compensate(Order order) {
        orderRepository.save(order);
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findByOrderId(order.getId())
            .orElseThrow(() -> new IllegalStateException("No purchase request linked to order " + order.getId()));
        int quantity = order.totalQuantity();

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(purchaseRequest.getFlashSaleId())
            .orElseThrow(() -> new IllegalStateException("Inventory for flash sale " + purchaseRequest.getFlashSaleId() + " not found"));
        inventory.release(quantity);
        inventoryRepository.save(inventory);

        outboxWriter.write("Order", order.getId().toString(), EventTypes.STOCK_RELEASE_REQUESTED,
            new StockReleaseRequestedEvent(purchaseRequest.getFlashSaleId(), quantity));
    }
}
```

`backend/src/main/java/com/flashsale/order/application/CancelOrderService.java`:
```java
package com.flashsale.order.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.domain.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelOrderService {

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;

    public CancelOrderService(OrderRepository orderRepository, OrderCompensationService compensationService) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
    }

    @Transactional
    public Order cancel(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
            .filter(o -> o.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " does not exist"));
        compensationService.cancel(order);
        return order;
    }
}
```

Add to `backend/src/main/java/com/flashsale/order/adapter/web/OrderController.java` (new dependency in the constructor, new endpoint):
```java
    private final CancelOrderService cancelOrderService;

    public OrderController(OrderRepository orderRepository, CancelOrderService cancelOrderService) {
        this.orderRepository = orderRepository;
        this.cancelOrderService = cancelOrderService;
    }

    @PostMapping("/{orderId}/cancel")
    public OrderDetail cancel(@PathVariable Long orderId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        Order order = cancelOrderService.cancel(orderId, userId);
        return new OrderDetail(order.getId(), order.getOrderNo(), order.getTotalAmount(), order.getStatus().name(), order.getPaymentDueAt());
    }
```
(Replace the existing single-argument constructor with this two-argument one; the rest of the class — `myOrders`, `detail` — is unchanged.)

- [ ] **Step 9: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.web.OrderCancelIT"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/flashsale/order backend/src/test/java/com/flashsale/order
git commit -m "feat: add Order cancellation with unified inventory compensation"
```

---

## Task 10: Payment Module — Simulated Payment Endpoint

**Files:**
- Create: `backend/src/main/java/com/flashsale/payment/domain/PaymentRecord.java`, `PaymentResult.java`
- Create: `backend/src/main/java/com/flashsale/payment/application/PaymentRecordRepository.java`, `SubmitPaymentService.java`
- Create: `backend/src/main/java/com/flashsale/payment/adapter/persistence/PaymentRecordJpaRepository.java`, `PaymentRecordRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/payment/adapter/web/PaymentController.java`, `dto/SubmitPaymentRequest.java`
- Test: `backend/src/test/java/com/flashsale/payment/application/SubmitPaymentServiceTest.java`
- Test: `backend/src/test/java/com/flashsale/payment/adapter/web/PaymentControllerIT.java`

**Interfaces:**
- Consumes: `OrderRepository` (Week 1), `OrderCompensationService.failPayment` (Task 9).
- Produces: `POST /api/orders/{orderId}/payments` (200, `OrderDetail`) — this is the last new HTTP endpoint this plan adds.

- [ ] **Step 1: Write the failing service test**

`backend/src/test/java/com/flashsale/payment/application/SubmitPaymentServiceTest.java`:
```java
package com.flashsale.payment.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.payment.domain.PaymentRecord;
import com.flashsale.payment.domain.PaymentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmitPaymentServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderCompensationService compensationService;
    @Mock PaymentRecordRepository paymentRecordRepository;

    SubmitPaymentService service;

    private Order pendingOrderOwnedBy(Long userId) {
        return Order.createPendingPayment(userId, 10L, 1, new BigDecimal("9.99"));
    }

    @Test
    void successfulPaymentMarksOrderPaid() {
        service = new SubmitPaymentService(orderRepository, compensationService, paymentRecordRepository);
        Order order = pendingOrderOwnedBy(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(paymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.submit(5L, 1L, PaymentResult.SUCCESS);

        assertThat(result.getStatus().name()).isEqualTo("PAID");
        verify(orderRepository).save(order);
        verify(compensationService, never()).failPayment(any());
    }

    @Test
    void failedPaymentTriggersCompensation() {
        service = new SubmitPaymentService(orderRepository, compensationService, paymentRecordRepository);
        Order order = pendingOrderOwnedBy(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(paymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submit(5L, 1L, PaymentResult.FAILURE);

        verify(compensationService).failPayment(order);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void anotherUsersOrderIsNotFound() {
        service = new SubmitPaymentService(orderRepository, compensationService, paymentRecordRepository);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(pendingOrderOwnedBy(1L)));

        assertThatThrownBy(() -> service.submit(5L, 2L, PaymentResult.SUCCESS))
            .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.payment.application.SubmitPaymentServiceTest"`
Expected: FAIL to compile — the `payment` package doesn't exist yet.

- [ ] **Step 3: Implement the payment domain and application layer**

`backend/src/main/java/com/flashsale/payment/domain/PaymentResult.java`:
```java
package com.flashsale.payment.domain;

public enum PaymentResult {
    SUCCESS, FAILURE
}
```

`backend/src/main/java/com/flashsale/payment/domain/PaymentRecord.java`:
```java
package com.flashsale.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_records")
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentResult result;

    @Column(name = "simulated_transaction_id", nullable = false)
    private String simulatedTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentRecord() {}

    public static PaymentRecord record(Long orderId, PaymentResult result) {
        PaymentRecord record = new PaymentRecord();
        record.orderId = orderId;
        record.result = result;
        record.simulatedTransactionId = "SIM-" + UUID.randomUUID();
        record.createdAt = Instant.now();
        return record;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public PaymentResult getResult() { return result; }
    public String getSimulatedTransactionId() { return simulatedTransactionId; }
}
```

`backend/src/main/java/com/flashsale/payment/application/PaymentRecordRepository.java`:
```java
package com.flashsale.payment.application;

import com.flashsale.payment.domain.PaymentRecord;

public interface PaymentRecordRepository {
    PaymentRecord save(PaymentRecord record);
}
```

`backend/src/main/java/com/flashsale/payment/application/SubmitPaymentService.java`:
```java
package com.flashsale.payment.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.OrderCompensationService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.payment.domain.PaymentRecord;
import com.flashsale.payment.domain.PaymentResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitPaymentService {

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;
    private final PaymentRecordRepository paymentRecordRepository;

    public SubmitPaymentService(OrderRepository orderRepository, OrderCompensationService compensationService,
                                 PaymentRecordRepository paymentRecordRepository) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
        this.paymentRecordRepository = paymentRecordRepository;
    }

    @Transactional
    public Order submit(Long orderId, Long userId, PaymentResult result) {
        Order order = orderRepository.findById(orderId)
            .filter(o -> o.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " does not exist"));

        paymentRecordRepository.save(PaymentRecord.record(orderId, result));

        if (result == PaymentResult.SUCCESS) {
            order.pay();
            orderRepository.save(order);
        } else {
            compensationService.failPayment(order);
        }
        return order;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.payment.application.SubmitPaymentServiceTest"`
Expected: PASS

- [ ] **Step 5: Write the failing controller IT**

`backend/src/test/java/com/flashsale/payment/adapter/web/PaymentControllerIT.java`:
```java
package com.flashsale.payment.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class PaymentControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private String registerAndLogin(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("email", email);
            put("password", "secret123");
        }});
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void successfulSimulatedPaymentMarksOrderPaid() throws Exception {
        String token = registerAndLogin("pay-success@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (881, 'ORD-881', (select id from users where email = 'pay-success@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, quantity, unit_price) values (881, 1, 1, 9.99)");

        mockMvc.perform(post("/api/orders/881/payments").contentType(APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content("{\"result\":\"SUCCESS\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"));

        Integer paymentRecordCount = jdbcTemplate.queryForObject(
            "select count(*) from payment_records where order_id = 881 and result = 'SUCCESS'", Integer.class);
        assertThat(paymentRecordCount).isEqualTo(1);
    }

    @Test
    void failedSimulatedPaymentCancelsOrderAndReleasesInventory() throws Exception {
        String token = registerAndLogin("pay-fail@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (882, 'ORD-882', (select id from users where email = 'pay-fail@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, quantity, unit_price) values (882, 1, 1, 9.99)");
        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, order_id, status) " +
            "values (gen_random_uuid(), 'pay-fail-key', (select id from users where email = 'pay-fail@example.com'), 1, 882, 'SUCCEEDED')");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        mockMvc.perform(post("/api/orders/882/payments").contentType(APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content("{\"result\":\"FAILURE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.payment.adapter.web.PaymentControllerIT"`
Expected: FAIL — no route registered for `POST /api/orders/{orderId}/payments`, and `PaymentRecordJpaRepository`/`PaymentRecordRepositoryImpl` don't exist so `SubmitPaymentService` can't even be wired.

- [ ] **Step 7: Implement the persistence adapter and web controller**

`backend/src/main/java/com/flashsale/payment/adapter/persistence/PaymentRecordJpaRepository.java`:
```java
package com.flashsale.payment.adapter.persistence;

import com.flashsale.payment.domain.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecordJpaRepository extends JpaRepository<PaymentRecord, Long> {}
```

`backend/src/main/java/com/flashsale/payment/adapter/persistence/PaymentRecordRepositoryImpl.java`:
```java
package com.flashsale.payment.adapter.persistence;

import com.flashsale.payment.application.PaymentRecordRepository;
import com.flashsale.payment.domain.PaymentRecord;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRecordRepositoryImpl implements PaymentRecordRepository {

    private final PaymentRecordJpaRepository jpaRepository;

    public PaymentRecordRepositoryImpl(PaymentRecordJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PaymentRecord save(PaymentRecord record) {
        return jpaRepository.save(record);
    }
}
```

`backend/src/main/java/com/flashsale/payment/adapter/web/dto/SubmitPaymentRequest.java`:
```java
package com.flashsale.payment.adapter.web.dto;

import com.flashsale.payment.domain.PaymentResult;

public record SubmitPaymentRequest(PaymentResult result) {}
```

`backend/src/main/java/com/flashsale/payment/adapter/web/PaymentController.java`:
```java
package com.flashsale.payment.adapter.web;

import com.flashsale.order.application.dto.OrderDetail;
import com.flashsale.order.domain.Order;
import com.flashsale.payment.adapter.web.dto.SubmitPaymentRequest;
import com.flashsale.payment.application.SubmitPaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class PaymentController {

    private final SubmitPaymentService submitPaymentService;

    public PaymentController(SubmitPaymentService submitPaymentService) {
        this.submitPaymentService = submitPaymentService;
    }

    @PostMapping("/{orderId}/payments")
    public OrderDetail submit(@PathVariable Long orderId, @RequestBody SubmitPaymentRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        Order order = submitPaymentService.submit(orderId, userId, request.result());
        return new OrderDetail(order.getId(), order.getOrderNo(), order.getTotalAmount(), order.getStatus().name(), order.getPaymentDueAt());
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.payment.adapter.web.PaymentControllerIT"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/flashsale/payment backend/src/test/java/com/flashsale/payment
git commit -m "feat: add simulated payment module"
```

---

## Task 11: StockReleaseConsumer + PaymentTimeoutScheduler — Close the Compensation Loop

**Files:**
- Create: `backend/src/main/java/com/flashsale/inventory/adapter/messaging/StockReleaseConsumer.java`
- Modify: `backend/src/main/java/com/flashsale/order/application/OrderRepository.java`, `adapter/persistence/OrderJpaRepository.java`, `OrderRepositoryImpl.java` (add `findPendingPaymentPastDue`)
- Create: `backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java`
- Test: `backend/src/test/java/com/flashsale/inventory/adapter/messaging/StockReleaseConsumerIT.java`
- Test: `backend/src/test/java/com/flashsale/order/application/PaymentTimeoutSchedulerIT.java`

**Interfaces:**
- Consumes: `ConsumedMessageGuard.tryConsume` (Task 4), `InventoryStockGateway.release` (Task 2), `StockReleaseRequestedEvent` (Task 8), `RabbitConfig.STOCK_RELEASE_QUEUE` (Task 3), `OrderCompensationService.markExpired` (Task 9).
- Produces: nothing new consumed elsewhere — this is where every `StockReleaseRequested` outbox event (written by Task 8's DLQ handler and Task 9's `OrderCompensationService`) finally increments the Redis counter back up, and where overdue `PENDING_PAYMENT` orders get swept automatically.

- [ ] **Step 1: Write the failing StockReleaseConsumer IT**

`backend/src/test/java/com/flashsale/inventory/adapter/messaging/StockReleaseConsumerIT.java`:
```java
package com.flashsale.inventory.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.inventory.application.InventoryStockGateway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class StockReleaseConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired InventoryStockGateway inventoryStockGateway;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void stockReleaseMessageIncrementsRedisBackUp() throws Exception {
        // Seed Redis at 0 (fully reserved) via a real reservation, then release 1 via the queue.
        inventoryStockGateway.reserve(1L, 1);
        assertThat(inventoryStockGateway.currentValue(1L)).contains(0);

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("flashSaleId", 1);
            put("quantity", 1);
        }});
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("outboxEventId", 4242L);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.STOCK_RELEASE_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(inventoryStockGateway.currentValue(1L)).contains(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.adapter.messaging.StockReleaseConsumerIT"`
Expected: FAIL — nothing consumes `stock.release.queue` yet, so Redis stays at 0.

- [ ] **Step 3: Implement StockReleaseConsumer**

`backend/src/main/java/com/flashsale/inventory/adapter/messaging/StockReleaseConsumer.java`:
```java
package com.flashsale.inventory.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.ConsumedMessageGuard;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.event.StockReleaseRequestedEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
public class StockReleaseConsumer {

    private static final String CONSUMER_NAME = "stock-release-consumer";

    private final ConsumedMessageGuard consumedMessageGuard;
    private final InventoryStockGateway inventoryStockGateway;
    private final ObjectMapper objectMapper;

    public StockReleaseConsumer(ConsumedMessageGuard consumedMessageGuard, InventoryStockGateway inventoryStockGateway,
                                 ObjectMapper objectMapper) {
        this.consumedMessageGuard = consumedMessageGuard;
        this.inventoryStockGateway = inventoryStockGateway;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.STOCK_RELEASE_QUEUE)
    @Transactional
    public void handle(Message message) throws IOException {
        Long outboxEventId = (Long) message.getMessageProperties().getHeaders().get("outboxEventId");
        if (!consumedMessageGuard.tryConsume(String.valueOf(outboxEventId), CONSUMER_NAME)) {
            return;
        }
        StockReleaseRequestedEvent event = objectMapper.readValue(message.getBody(), StockReleaseRequestedEvent.class);
        inventoryStockGateway.release(event.flashSaleId(), event.quantity());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.adapter.messaging.StockReleaseConsumerIT"`
Expected: PASS

- [ ] **Step 5: Add findPendingPaymentPastDue to OrderRepository**

Add to `backend/src/main/java/com/flashsale/order/application/OrderRepository.java`:
```java
    List<Order> findPendingPaymentPastDue(java.time.Instant now);
```

Add to `backend/src/main/java/com/flashsale/order/adapter/persistence/OrderJpaRepository.java`:
```java
    List<Order> findByStatusAndPaymentDueAtBefore(com.flashsale.order.domain.OrderStatus status, java.time.Instant instant);
```

Add to `backend/src/main/java/com/flashsale/order/adapter/persistence/OrderRepositoryImpl.java`:
```java
    @Override
    public List<Order> findPendingPaymentPastDue(java.time.Instant now) {
        return jpaRepository.findByStatusAndPaymentDueAtBefore(com.flashsale.order.domain.OrderStatus.PENDING_PAYMENT, now);
    }
```

- [ ] **Step 6: Write the failing PaymentTimeoutScheduler IT**

`backend/src/test/java/com/flashsale/order/application/PaymentTimeoutSchedulerIT.java`:
```java
package com.flashsale.order.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class PaymentTimeoutSchedulerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void overduePendingPaymentOrderIsExpiredAndInventoryReleased() {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (997, 'timeout@example.com', 'x', 'USER', 'ACTIVE')");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (883, 'ORD-883', 997, 9.99, 'PENDING_PAYMENT', now() - interval '1 minute')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, quantity, unit_price) values (883, 1, 1, 9.99)");
        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, order_id, status) " +
            "values (gen_random_uuid(), 'timeout-key', 997, 1, 883, 'SUCCEEDED')");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject("select status from orders where id = 883", String.class);
            assertThat(status).isEqualTo("EXPIRED");
        });

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);
    }
}
```
(35s budget: the scheduler's `fixedDelay` is 30s, plus headroom for the outbox/consumer round trip.)

- [ ] **Step 7: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.application.PaymentTimeoutSchedulerIT"`
Expected: FAIL — `PaymentTimeoutScheduler` doesn't exist, nothing ever expires the order.

- [ ] **Step 8: Implement PaymentTimeoutScheduler**

`backend/src/main/java/com/flashsale/order/application/PaymentTimeoutScheduler.java`:
```java
package com.flashsale.order.application;

import com.flashsale.order.domain.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class PaymentTimeoutScheduler {

    private final OrderRepository orderRepository;
    private final OrderCompensationService compensationService;

    public PaymentTimeoutScheduler(OrderRepository orderRepository, OrderCompensationService compensationService) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
    }

    @Scheduled(fixedDelay = 30000)
    public void expireOverduePayments() {
        List<Order> overdue = orderRepository.findPendingPaymentPastDue(Instant.now());
        for (Order order : overdue) {
            compensationService.markExpired(order);
        }
    }
}
```

- [ ] **Step 9: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.application.PaymentTimeoutSchedulerIT"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/flashsale/inventory/adapter/messaging backend/src/main/java/com/flashsale/order backend/src/test/java/com/flashsale/inventory backend/src/test/java/com/flashsale/order/application/PaymentTimeoutSchedulerIT.java
git commit -m "feat: close the compensation loop with StockReleaseConsumer and PaymentTimeoutScheduler"
```

---

## Task 12: Nginx Rate Limiting on the Purchase Endpoint

**Files:**
- Modify: `nginx/nginx.conf`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks — this is a standalone infra change verified manually (Nginx config isn't exercised by the backend's JUnit/Testcontainers suite, matching how Week 1 Task 9 verified the `/api/auth/*` rate limit).

- [ ] **Step 1: Add the purchase_limit zone and location block**

In `nginx/nginx.conf`, add a second `limit_req_zone` next to the existing `auth_limit` one:
```nginx
    limit_req_zone $binary_remote_addr zone=auth_limit:10m rate=5r/s;
    limit_req_zone $binary_remote_addr zone=purchase_limit:10m rate=50r/s;
```
Then add a new `location` block for the purchase endpoint, placed **before** the generic `location /api/` block (Nginx matches the most specific applicable regex location, but keeping it above the catch-all keeps the file readable in the same style as the existing `/api/auth/(login|register)` block):
```nginx
        location ~ ^/api/flash-sales/\d+/purchase-requests$ {
            limit_req zone=purchase_limit burst=100 nodelay;
            proxy_pass http://backend:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
```

- [ ] **Step 2: Verify manually against the running stack**

```bash
docker compose up --build -d
```
Register+login a user, obtain an access token and a valid `Idempotency-Key`-bearing loop, then fire more than 150 requests in under a second against `https://localhost:8443/api/flash-sales/1/purchase-requests` (e.g. a short shell loop with `curl -k -s -o /dev/null -w "%{http_code}\n"`, varying the `Idempotency-Key` header each time so requests aren't just replay-deduped before they'd hit the rate limiter). Expect the first ~150 (burst 100 + steady 50r/s during the loop's wall-clock duration) to return `202`/`409`/`200`-range codes and the rest to return `503` (Nginx's default rate-limit rejection status) once the zone's burst is exhausted. Confirm `/api/auth/login` and ordinary `GET /api/flash-sales` calls are unaffected during the same burst (different zone, unlimited).

- [ ] **Step 3: Commit**

```bash
git add nginx/nginx.conf
git commit -m "feat: rate-limit the purchase endpoint at the Nginx layer"
```

---

## Task 13: InventoryReconciliationScheduler — Redis/Postgres Drift Correction

**Files:**
- Create: `backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java`
- Test: `backend/src/test/java/com/flashsale/inventory/application/InventoryReconciliationSchedulerIT.java`

**Interfaces:**
- Consumes: `FlashSaleRepository.findAll` (Week 1), `FlashSale.isPurchasableAt` (Week 1), `InventoryRepository.findByFlashSaleId` (Task 2), `InventoryStockGateway.currentValue`/`resync` (Task 2).
- Produces: nothing new consumed elsewhere — this is the plan's final safety net, closing out the design spec's §7 requirement.

- [ ] **Step 1: Write the failing reconciliation IT**

`backend/src/test/java/com/flashsale/inventory/application/InventoryReconciliationSchedulerIT.java`:
```java
package com.flashsale.inventory.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class InventoryReconciliationSchedulerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
    }

    @Autowired InventoryStockGateway inventoryStockGateway;

    @Test
    void driftedRedisValueIsResyncedFromPostgres() {
        // inventory-fixtures.sql seeds flash sale 1 with available_quantity=1. Force Redis to
        // drift away from that authoritative value without going through the gateway's own
        // reserve/release (simulating e.g. a Redis restart that lost the real count).
        inventoryStockGateway.resync(1L, 999);
        assertThat(inventoryStockGateway.currentValue(1L)).contains(999);

        await().atMost(Duration.ofSeconds(65)).untilAsserted(() ->
            assertThat(inventoryStockGateway.currentValue(1L)).contains(1));
    }
}
```
(65s budget: the scheduler's `fixedDelay` is 60s.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.application.InventoryReconciliationSchedulerIT"`
Expected: FAIL (times out) — nothing corrects the drift yet.

- [ ] **Step 3: Implement InventoryReconciliationScheduler**

`backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java`:
```java
package com.flashsale.inventory.application;

import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.domain.Inventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class InventoryReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationScheduler.class);

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryStockGateway inventoryStockGateway;

    public InventoryReconciliationScheduler(FlashSaleRepository flashSaleRepository, InventoryRepository inventoryRepository,
                                             InventoryStockGateway inventoryStockGateway) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryStockGateway = inventoryStockGateway;
    }

    @Scheduled(fixedDelay = 60000)
    public void reconcileActiveFlashSales() {
        Instant now = Instant.now();
        for (FlashSale flashSale : flashSaleRepository.findAll()) {
            if (!flashSale.isPurchasableAt(now)) {
                continue;
            }
            inventoryRepository.findByFlashSaleId(flashSale.getId()).ifPresent(inventory ->
                reconcileOne(flashSale.getId(), inventory));
        }
    }

    private void reconcileOne(Long flashSaleId, Inventory inventory) {
        int authoritative = inventory.getAvailableQuantity();
        inventoryStockGateway.currentValue(flashSaleId).ifPresentOrElse(redisValue -> {
            if (!redisValue.equals(authoritative)) {
                log.warn("Inventory drift detected for flash sale {}: redis={} postgres={}, resyncing to postgres",
                    flashSaleId, redisValue, authoritative);
                inventoryStockGateway.resync(flashSaleId, authoritative);
            }
        }, () -> inventoryStockGateway.resync(flashSaleId, authoritative));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.inventory.application.InventoryReconciliationSchedulerIT"`
Expected: PASS

- [ ] **Step 5: Run the full backend test suite**

Run: `cd backend && ./gradlew test`
Expected: PASS — every test from Tasks 1–13 green together, including Week 1's untouched suites (`AuthController*IT`, `FlashSaleControllerIT`, `FlywayMigrationIT`, `SecurityFilterChainSingletonIT`, `SecurityErrorResponseIT`).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flashsale/inventory/application/InventoryReconciliationScheduler.java backend/src/test/java/com/flashsale/inventory/application
git commit -m "feat: add Redis/Postgres inventory reconciliation scheduler"
```
