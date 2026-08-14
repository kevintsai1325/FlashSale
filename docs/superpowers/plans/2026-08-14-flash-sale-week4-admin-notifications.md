# FlashSale Week 4 (後台與驗證) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build everything in `docs/superpowers/specs/2026-08-14-flash-sale-week4-admin-notifications-design.md`: API audit logging, order status history, a real notification-retry mechanism, the admin backend API, the admin frontend (`/admin`), ArchUnit module-boundary rules, and a k6 correctness script.

**Architecture:** New `admin` module (`com.flashsale.admin.{application,adapter}` — almost no `domain`, it's mostly read-only cross-module queries per main spec §5) alongside the existing seven. `common/web` gains `TraceIdFilter` and `ApiAuditFilter`. Notification retry reuses the Postgres-scheduled-scan pattern already established by `PaymentTimeoutScheduler`/`InventoryReconciliationScheduler` — no new RabbitMQ topology. `order_status_history` gets three explicit write call-sites, no AOP. Frontend gets a parallel `/admin` route tree gated by a new `RequireAdmin`, plus Recharts and TanStack Table (already in the main spec's tech stack, first real use).

**Tech Stack:** Same as Week 1-3 (Java 21, Spring Boot 3.3.4, Spring Data JPA/PostgreSQL, JUnit 5/AssertJ/Mockito, Spring Boot Test/MockMvc, Testcontainers, React 19/TypeScript/Vite/React Router 7/TanStack Query 5), plus ArchUnit (backend, new), Recharts + `@tanstack/react-table` (frontend, new — see Task 8).

## Global Constraints

- No new Flyway migration — `api_audit_logs` and `order_status_history` already exist in `V1__baseline_schema.sql` unused; `notification_deliveries.created_at`/`updated_at` already exist in the same file, just not JPA-mapped yet. Read the design spec §2 before assuming otherwise.
- `admin` module: package as `com.flashsale.admin.application` (query services) and `com.flashsale.admin.adapter.web` (controllers) — skip `domain` entirely unless a task below explicitly needs one; this module is almost entirely cross-module read queries, inventing a domain layer for it would be the kind of unrequested abstraction this codebase's reviewer has flagged before.
- Every new admin repository method reads across module boundaries through the target module's existing `application`-layer repository interface (e.g. `admin` calls `OrderRepository`, `PurchaseRequestRepository`, never touches another module's JPA entities or `adapter` package directly) — Task 1's `ArchitectureTest` enforces this, so violating it fails the build, not just a style nit.
- TDD: Red → Green → Refactor per use case, same as every prior week. No tests for framework code or trivial getters.
- Testing convention unchanged: backend `*IT.java` → Testcontainers Postgres (+Redis+RabbitMQ where the context needs those beans, which it does everywhere by now); frontend → `vi.spyOn` + `MemoryRouter` + Testing Library, no MSW.
- Notification retry does **not** create a new outbox event type or RabbitMQ queue — see design spec §2.1/§5 for why (email delivery doesn't need outbox/DLQ-grade exactly-once guarantees; a `FAILED` row past `MAX_ATTEMPTS` *is* the dead-letter record, queryable and manually retriable from the admin UI).
- Frontend: `role: 'USER' | 'ADMIN' | null` on `AuthContext`, decoded client-side from the JWT payload (`atob()` on the middle base64 segment, no signature verification — this is UX-only routing, not a security boundary; the backend's `hasRole("ADMIN")` on `/api/admin/**` is the real boundary and was already wired in Week 1).

---

## File Structure

```text
backend/src/main/java/com/flashsale/
├─ common/
│  ├─ web/
│  │  ├─ TraceIdFilter.java              # Task 2
│  │  └─ ApiAuditFilter.java             # Task 2
│  └─ exception/
│     └─ GlobalExceptionHandler.java     # Task 2 — modify: stash error code as a request attribute
├─ order/
│  ├─ domain/OrderStatusHistory.java     # Task 3
│  ├─ application/
│  │  ├─ OrderStatusHistoryRepository.java   # Task 3
│  │  └─ OrderCompensationService.java   # Task 3 — modify
│  └─ adapter/
│     ├─ persistence/OrderStatusHistoryJpaRepository.java, OrderStatusHistoryRepositoryImpl.java  # Task 3
│     └─ messaging/OrderPurchaseConsumer.java  # Task 3 — modify
├─ payment/application/SubmitPaymentService.java  # Task 3 — modify
├─ notification/
│  ├─ domain/NotificationDelivery.java   # Task 4 — modify: map created_at/updated_at
│  └─ application/
│     ├─ NotificationRetryService.java   # Task 4
│     └─ NotificationRetryScheduler.java # Task 4
└─ admin/
   ├─ application/
   │  ├─ DashboardQueryService.java      # Task 5
   │  ├─ ApiAuditQueryService.java       # Task 6
   │  ├─ AdminOrderQueryService.java     # Task 6
   │  └─ AdminNotificationService.java   # Task 7
   └─ adapter/web/
      ├─ AdminDashboardController.java  # Task 5
      ├─ AdminApiLogController.java     # Task 6
      ├─ AdminOrderController.java      # Task 6
      └─ AdminNotificationController.java  # Task 7

backend/src/test/java/com/flashsale/
└─ ArchitectureTest.java                 # Task 1

frontend/src/
├─ features/auth/useAuth.tsx             # Task 8 — modify: add role
├─ features/admin/
│  ├─ RequireAdmin.tsx                   # Task 8
│  ├─ AdminNav.tsx                       # Task 8
│  ├─ AdminDashboardPage.tsx             # Task 9
│  ├─ ApiLogsPage.tsx                    # Task 10
│  ├─ AdminOrdersPage.tsx                # Task 10
│  ├─ AdminOrderDetailPage.tsx           # Task 10
│  ├─ AdminNotificationsPage.tsx         # Task 11
│  └─ AdminNotificationDetailPage.tsx    # Task 11
├─ api/adminApi.ts                       # Task 8 (base) + 9/10/11 (extend)
└─ components/AppNav.tsx                 # Task 11 — modify: bell icon for ADMIN

load-tests/purchase-flow.js               # Task 12
```

---

## Task 1: ArchUnit Module Boundary Rules

**Files:**
- Modify: `backend/build.gradle.kts` (add ArchUnit dependency)
- Create: `backend/src/test/java/com/flashsale/ArchitectureTest.java`

**Interfaces:**
- Consumes: nothing (pure static analysis over the compiled `com.flashsale` package tree).
- Produces: nothing consumed elsewhere — a standing regression test.

Do this task first, before Task 5-7 add the `admin` module — the rules should already exist so that as `admin` gets built in later tasks, any accidental direct-adapter-reach into another module fails immediately instead of needing a later audit.

- [ ] **Step 1: Add the ArchUnit dependency**

In `backend/build.gradle.kts`, add to the `testImplementation` block (check the current dependency block first — insert alongside the other `testImplementation` lines, don't create a second block):
```kotlin
testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
```

- [ ] **Step 2: Write the rules**

`backend/src/test/java/com/flashsale/ArchitectureTest.java`:
```java
package com.flashsale;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.flashsale";
    private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
        new ClassFileImporter().importPackages(BASE_PACKAGE);

    @Test
    void domainPackagesDoNotDependOnAdapterPackages() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");
        rule.check(CLASSES);
    }

    @Test
    void domainPackagesDoNotDependOnSpringFramework() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");
        rule.check(CLASSES);
    }

    @Test
    void modulesDoNotReachIntoOtherModulesAdapterPackages() {
        for (String module : new String[]{"identity", "catalog", "flashsale", "inventory", "order", "payment", "notification", "admin"}) {
            ArchRule rule = noClasses().that().resideInAPackage(BASE_PACKAGE + "." + module + "..")
                .and().resideOutsideOfPackage(BASE_PACKAGE + "." + module + ".adapter..")
                .should().dependOnClassesThat().resideInAnyPackage(otherModulesAdapterPackages(module));
            rule.check(CLASSES);
        }
    }

    private String[] otherModulesAdapterPackages(String exclude) {
        return java.util.Arrays.stream(new String[]{"identity", "catalog", "flashsale", "inventory", "order", "payment", "notification", "admin"})
            .filter(m -> !m.equals(exclude))
            .map(m -> BASE_PACKAGE + "." + m + ".adapter..")
            .toArray(String[]::new);
    }
}
```

- [ ] **Step 3: Run and fix any existing violations**

Run: `cd backend && ./gradlew test --tests "com.flashsale.ArchitectureTest"`
Expected: PASS. If it fails against *existing* Week 1-3 code, that's a real finding — read the violation report carefully before touching anything. The likely candidate given the codebase's history: none expected (every module has consistently gone through `application`-layer interfaces so far, per the pattern established since Week 1), but verify rather than assume.

- [ ] **Step 4: Commit**

```bash
git add backend/build.gradle.kts backend/src/test/java/com/flashsale/ArchitectureTest.java
git commit -m "test: add ArchUnit module boundary rules"
```

---

## Task 2: API Audit Filter + Trace ID Filter

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/web/TraceIdFilter.java`, `ApiAuditFilter.java`
- Modify: `backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/flashsale/common/web/ApiAuditLog.java` (entity), `ApiAuditLogJpaRepository.java`, `ApiAuditWriter.java`
- Create: `backend/src/main/java/com/flashsale/common/web/ApiAuditRetentionScheduler.java`
- Test: `backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java`

**Interfaces:**
- Consumes: `SecurityContextHolder` (post-authentication `userId`/`role` claims), `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (path template).
- Produces: `api_audit_logs` rows. Task 6's `ApiAuditQueryService` reads them.

- [ ] **Step 1: Write the failing IT**

`backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java` — boot the full context (Postgres Testcontainers only, no Redis/RabbitMQ needed for this one... but check: does *every* context in this codebase now require Redis/RabbitMQ beans regardless, per Week 2's Global Constraints note about `InventoryStockGateway`/`OutboxPublisher` always being present? If so, include all three containers like every other `*IT` in this codebase does — don't try to slim it down, that's not this task's call to make). Hit a real endpoint (e.g. `GET /api/flash-sales`, already public, no auth needed — simplest to exercise), then poll (`awaitility`, since the write is `@Async`) `api_audit_logs` for a row matching `path_template = '/api/flash-sales'`, `method = 'GET'`, `status = 200`. Assert `user_id IS NULL` for this anonymous request. Add a second case hitting an authenticated endpoint (reuse an existing registered/logged-in user flow from another IT's pattern) and assert `user_id` is populated.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.web.ApiAuditFilterIT"`
Expected: FAIL — nothing writes to `api_audit_logs` yet.

- [ ] **Step 3: Entity + repository + writer**

`ApiAuditLog.java` — JPA entity mapping every column in the existing `api_audit_logs` table (`occurred_at` DB-defaulted, don't set it from Java; `method`, `path_template`, `status`, `user_id`, `request_id`, `trace_id`, `duration_ms`, `client_ip`, `user_agent`, `error_code`). `ApiAuditWriter` — one `@Async` method `record(ApiAuditLog log)` that saves it, catching any exception and logging it (SLF4J, structured — a simple `log.error(...)` with the fields as arguments is enough, don't build a custom structured-logging abstraction, that's Week 5's JSON encoder territory) rather than propagating (a failed audit write must never fail the request it's auditing — but note the request has already completed and responded by the time this runs, since it's async, so "never fail the request" is automatic here, not something you need to code defensively for; just don't let the failure go unnoticed either).

- [ ] **Step 4: TraceIdFilter**

`TraceIdFilter` (`OncePerRequestFilter`, registered to run early — Spring Boot filter ordering, use `@Order` or a `FilterRegistrationBean` if plain `@Component` registration doesn't give it early-enough priority; check how existing filters if any are registered in this codebase, otherwise use `Ordered.HIGHEST_PRECEDENCE`): read incoming `X-Trace-Id` header if present, else generate `UUID.randomUUID().toString()`. Put it in MDC key `traceId` and a request attribute (`ApiAuditFilter` reads the attribute; MDC is for Week 5's future structured logging, harmless to set now even though nothing consumes it yet — it's a one-line `MDC.put`, not scope creep). Set response header `X-Trace-Id` to the same value. Clear the MDC key in a `finally` block (MDC leaks across thread-pool-reused threads otherwise — this matters more than usual here since `@Async` methods run on a pool).

- [ ] **Step 5: ApiAuditFilter**

Registered to run *after* Spring Security's filter chain (so `SecurityContextHolder` is populated) but the exact mechanism for "after Spring Security" in a Spring Boot app with `spring-boot-starter-security` needs checking against how this project's `SecurityConfig` builds its `SecurityFilterChain` — read `SecurityConfig.java` first. Captures start time, calls `filterChain.doFilter(request, response)` unconditionally (exceptions propagate through untouched — this filter observes, it does not handle), then in a `finally` block builds and hands off the `ApiAuditLog` to `ApiAuditWriter.record(...)`. Path template: `(String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)` — this attribute is only set *after* the request has been dispatched to a handler, i.e. it's readable after `filterChain.doFilter()` returns, not before. `userId`: read from `SecurityContextHolder.getContext().getAuthentication()` if present and it's a `JwtAuthenticationToken`, else null. `errorCode`: read the request attribute Step 6 adds.

- [ ] **Step 6: GlobalExceptionHandler — stash the error code**

Read the current file first. Every `@ExceptionHandler` method in this class funnels through a private `build(...)` helper — add one line there: `request.setAttribute("apiAuditErrorCode", code);` before returning the `ProblemDetail`. This is the only change to this file; don't restructure the exception hierarchy.

- [ ] **Step 7: Retention scheduler**

`ApiAuditRetentionScheduler` — `@Scheduled(cron = "0 0 3 * * *")` (once daily, off-peak), deletes rows where `occurred_at < now() - retention_days`. Retention days from `app.audit.retention-days` property (`application.yml`, default `30`) via `@Value`. One `@Modifying @Query` delete, nothing fancier.

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.web.ApiAuditFilterIT"`
Expected: PASS

- [ ] **Step 9: Run full backend suite, commit**

Run: `cd backend && ./gradlew test`
Expected: PASS — every prior test still green. This filter runs on *every* request, so a mistake here has the widest blast radius of anything in this plan; don't skip the full-suite run.

```bash
git add backend/src/main/java/com/flashsale/common/web backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java backend/src/main/resources/application.yml backend/src/test/java/com/flashsale/common/web
git commit -m "feat: add API audit logging and trace ID correlation"
```

---

## Task 3: Order Status History

**Files:**
- Create: `backend/src/main/java/com/flashsale/order/domain/OrderStatusHistory.java`, `order/application/OrderStatusHistoryRepository.java`, `order/adapter/persistence/OrderStatusHistoryJpaRepository.java`, `OrderStatusHistoryRepositoryImpl.java`
- Modify: `backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java`, `backend/src/main/java/com/flashsale/payment/application/SubmitPaymentService.java`, `backend/src/main/java/com/flashsale/order/application/OrderCompensationService.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `order_status_history` rows. Task 6's `AdminOrderQueryService` reads them via `findByOrderIdOrderByChangedAtAsc`.

- [ ] **Step 1: Entity + repository**

`OrderStatusHistory` — JPA entity for the existing `order_status_history` table (`id`, `order_id`, `from_status` nullable, `to_status`, `changed_at` DB-defaulted). No setters beyond what a static factory needs; this is an insert-only record of what happened, nothing about it should be mutable after creation. `OrderStatusHistoryRepository` interface: `void record(Long orderId, OrderStatus from, OrderStatus to)` (note: `from` is `@Nullable OrderStatus`, `to` is not) and `List<OrderStatusHistory> findByOrderId(Long orderId)` (Task 6 needs this, add it now rather than as a follow-up).

- [ ] **Step 2: Write failing tests at the three call sites**

Don't create a new IT file for this — extend the *existing* ITs that already exercise these three call sites (per the design spec §11 testing-strategy note: don't duplicate a whole business-flow test just to add one assertion). Add a status-history assertion to:
- `OrderPurchaseConsumerIT` (`backend/src/test/java/com/flashsale/order/adapter/messaging/`) — after the existing assertions, query `order_status_history` for the created order's ID and assert one row with `from_status IS NULL`, `to_status = 'PENDING_PAYMENT'`.
- `PaymentControllerIT`'s success-payment test — assert a row `from_status = 'PENDING_PAYMENT'`, `to_status = 'PAID'`.
- `OrderCancelIT` — assert a row `from_status = 'PENDING_PAYMENT'`, `to_status = 'CANCELLED'`.
- `PaymentTimeoutSchedulerIT` — assert a row `to_status = 'EXPIRED'`.

Read each of these four files first to find the right insertion point in their existing test bodies before editing.

- [ ] **Step 3: Run tests to verify they fail**

Run each: `./gradlew test --tests "com.flashsale.order.adapter.messaging.OrderPurchaseConsumerIT"` etc.
Expected: FAIL — no rows exist yet in any of them (or compile failure if `OrderStatusHistoryRepository` doesn't exist yet at this point — either is an acceptable Red state).

- [ ] **Step 4: Wire the three call sites**

In `OrderPurchaseConsumer.handle()`, right after `purchaseRequestRepository.save(purchaseRequest)`: `orderStatusHistoryRepository.record(savedOrder.getId(), null, OrderStatus.PENDING_PAYMENT)`.

In `SubmitPaymentService.submit()`'s `PaymentResult.SUCCESS` branch, right after `orderRepository.save(order)`: `orderStatusHistoryRepository.record(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)`.

In `OrderCompensationService.compensate()` (the shared private method — this single call site covers `cancel`/`markExpired`/`failPayment` per the design spec §4), right after `orderRepository.save(order)`: `orderStatusHistoryRepository.record(order.getId(), OrderStatus.PENDING_PAYMENT, order.getStatus())`. All three constructors need `OrderStatusHistoryRepository` added as a new dependency — update each class's constructor and its existing unit/IT test setup accordingly (any test that does `new OrderPurchaseConsumer(...)`/`new SubmitPaymentService(...)`/`new OrderCompensationService(...)` directly needs the new arg; check whether any do, versus relying on Spring DI in `@SpringBootTest` contexts which need no change).

- [ ] **Step 5: Run tests to verify they pass**

Re-run all four IT classes from Step 3.
Expected: PASS

- [ ] **Step 6: Run full suite, commit**

Run: `cd backend && ./gradlew test`
Expected: PASS

```bash
git add backend/src/main/java/com/flashsale/order backend/src/main/java/com/flashsale/payment/application/SubmitPaymentService.java backend/src/test/java/com/flashsale/order backend/src/test/java/com/flashsale/order/adapter/web/PurchaseConcurrencyIT.java
git commit -m "feat: record order status transitions to order_status_history"
```
(The last test file in that `git add` is included defensively in case `PurchaseConcurrencyIT`'s concurrent-purchase flow also needed a constructor-signature update by ripple effect — check whether it actually changed before including it; don't `git add` a file with no diff.)

---

## Task 4: Notification Retry

**Files:**
- Modify: `backend/src/main/java/com/flashsale/notification/domain/NotificationDelivery.java`
- Create: `backend/src/main/java/com/flashsale/notification/application/NotificationRetryService.java`, `NotificationRetryScheduler.java`
- Modify: `backend/src/main/java/com/flashsale/notification/adapter/mail/EmailNotificationSender.java` (extract the shared send-attempt logic, see Step 2)
- Test: `backend/src/test/java/com/flashsale/notification/application/NotificationRetryServiceIT.java`, `NotificationRetrySchedulerIT.java`

**Interfaces:**
- Consumes: `notification_deliveries` rows in `FAILED` status.
- Produces: `NotificationRetryService.retry(Long deliveryId)` — Task 7's `POST /api/admin/notifications/{id}/retry` calls this directly, bypassing the scheduler's backoff/attempt-limit check (an admin-initiated retry is an explicit override, not a "is it time yet" decision).

- [ ] **Step 1: Map the existing timestamp columns**

`NotificationDelivery.java` — read the current file first (it has no `createdAt`/`updatedAt` fields despite the DB columns existing, per design spec §2.1/§5.1). Add:
```java
@Column(name = "created_at", nullable = false)
private Instant createdAt;

@Column(name = "updated_at", nullable = false)
private Instant updatedAt;
```
`pendingEmail(...)` factory sets both to `Instant.now()` on creation. `markSent()`/`markFailed(...)` both additionally set `this.updatedAt = Instant.now()`. Add `getCreatedAt()`/`getUpdatedAt()` getters (Task 7's admin notification list needs `createdAt` for display; the retry scheduler needs `updatedAt` for backoff math).

- [ ] **Step 2: Extract the shared send-attempt logic**

Read `EmailNotificationSender.java`'s current `send(NotificationDelivery delivery)` — it currently assumes a *brand new, unsaved* delivery (saves it first, then attempts, then saves again). Retry needs to reuse an *existing, already-persisted* row without re-inserting. Extract a private `attempt(NotificationDelivery delivery)` method containing everything from `try { ... }` onward (the Thymeleaf render + `mailSender.send(...)` + `markSent()`/`markFailed()` — but NOT the initial `deliveryRepository.save(delivery)` insert, and NOT the final save either, since callers need to control exactly when persistence happens relative to the attempt). `send(NotificationDelivery delivery)` becomes: `NotificationDelivery saved = deliveryRepository.save(delivery); attempt(saved); deliveryRepository.save(saved);` (same net behavior as before — this step is a pure refactor, verify with existing tests before moving on). Expose `attempt(...)` as package-private or via a small interface so `NotificationRetryService` (different package: `notification.application` vs `notification.adapter.mail`) can call it — an interface method is more consistent with this codebase's existing port/adapter seams than reaching into an adapter-package class directly (and Task 1's ArchUnit rule would catch cross-module adapter reaches, though this is *within* the notification module so it wouldn't trip that specific rule — still, follow the same discipline). Add `void retryAttempt(NotificationDelivery delivery)` to the existing `NotificationSender` interface, implemented by delegating to the same private `attempt(...)`.

- [ ] **Step 3: Write the failing NotificationRetryService test**

`NotificationRetryServiceIT.java` — seed a `FAILED` notification delivery directly via JDBC (or via the repository, whichever this codebase's existing notification tests already do — check `EmailNotificationSender`'s or `UserRegisteredNotificationListener`'s existing IT, if any, for the established fixture pattern first), call `retryService.retry(id)`, assert the row transitions to `SENT` (Mailpit test setup — check how existing registration-email tests verify a send happened, reuse that same verification mechanism, likely Mailpit's API or a mock `JavaMailSender`) and that **no new row was inserted** (`SELECT COUNT(*) FROM notification_deliveries` unchanged before/after — this is the specific behavior distinguishing retry from a fresh send, assert it explicitly).

- [ ] **Step 4: Implement NotificationRetryService**

```java
@Service
public class NotificationRetryService {
    // deliveryRepository needs a new findById(Long) method — add it to
    // NotificationDeliveryRepository/NotificationDeliveryRepositoryImpl, it doesn't have one yet.
    public void retry(Long deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new NotFoundException("NOTIFICATION_NOT_FOUND", "Notification " + deliveryId + " does not exist"));
        notificationSender.retryAttempt(delivery);
        deliveryRepository.save(delivery);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.notification.application.NotificationRetryServiceIT"`
Expected: PASS

- [ ] **Step 6: Write the failing scheduler test**

`NotificationRetrySchedulerIT.java` — seed one `FAILED` row with `attemptCount = 1` and `updatedAt` far enough in the past to be due for retry, one `FAILED` row with `updatedAt` = now (not due yet), one `FAILED` row with `attemptCount = 3` (at the cap, per design spec §5.2's `MAX_ATTEMPTS = 3`). `await()` for the scheduler's next tick, then assert: the first row transitioned (to `SENT` or, if Mailpit isn't reachable in this test's setup, at least `attemptCount` incremented / `updatedAt` bumped — pick whichever is actually observable given how this IT's mail sending is configured), the second and third rows untouched.

- [ ] **Step 7: Implement the scheduler**

```java
@Component
public class NotificationRetryScheduler {
    private static final int MAX_ATTEMPTS = 3;
    // backoff(attemptCount): 1 -> 1 minute, 2 -> 5 minutes — a plain switch/if, not a formula
    // dressed up as configurable policy; there are exactly two steps before the cap, don't build
    // a generic backoff-strategy abstraction for two hardcoded numbers.

    @Scheduled(fixedDelay = 60000)
    public void retryDueNotifications() {
        for (NotificationDelivery delivery : deliveryRepository.findFailedWithAttemptsBelow(MAX_ATTEMPTS)) {
            if (isDue(delivery)) {
                notificationSender.retryAttempt(delivery);
                deliveryRepository.save(delivery);
            }
        }
    }
}
```
(`findFailedWithAttemptsBelow(int)` is a new `NotificationDeliveryRepository` method — a plain `status = 'FAILED' AND attempt_count < :max` query, no need to push the backoff-time comparison into SQL when it's two branches of Java `if`.)

- [ ] **Step 8: Run test to verify it passes, run full suite, commit**

Run: `cd backend && ./gradlew test --tests "com.flashsale.notification.application.NotificationRetrySchedulerIT"` then `./gradlew test`
Expected: PASS

```bash
git add backend/src/main/java/com/flashsale/notification backend/src/test/java/com/flashsale/notification
git commit -m "feat: add notification retry with exponential backoff"
```

---

## Task 5: Admin Dashboard API

**Files:**
- Create: `backend/src/main/java/com/flashsale/admin/application/DashboardQueryService.java`, `dto/DashboardSummary.java`, `dto/DashboardTrends.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/AdminDashboardController.java`
- Test: `backend/src/test/java/com/flashsale/admin/adapter/web/AdminDashboardControllerIT.java`

**Interfaces:**
- Consumes: `PurchaseRequestRepository`, `OrderRepository`, `InventoryRepository` (all existing, read-only — add whatever new count/aggregate methods each needs; keep each addition a single-purpose query, not a generic "criteria builder").
- Produces: `GET /api/admin/dashboard/summary` → `DashboardSummary`, `GET /api/admin/dashboard/trends` → `DashboardTrends`.

- [ ] **Step 1: Write the failing IT**

`AdminDashboardControllerIT` — seed a handful of purchase requests/orders/inventory rows via JDBC across different statuses and timestamps, hit both endpoints as an ADMIN-role user (reuse this codebase's established JWT-test-key pattern from any existing `*ControllerIT`), assert the aggregate numbers match the seeded data. Also assert a non-ADMIN (plain `USER` role) JWT gets `403` on both — this is the first real test of the `/api/admin/**` security boundary that's existed since Week 1 with nothing behind it; don't skip this assertion.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.adapter.web.AdminDashboardControllerIT"`
Expected: FAIL to compile — nothing in `admin` exists yet.

- [ ] **Step 3: DTOs**

`DashboardSummary(long totalPurchaseRequests, long succeededPurchaseRequests, Map<String, Long> ordersByStatus, BigDecimal totalPaidAmount, Map<Long, InventorySummary> inventoryByFlashSaleId)` — read the design spec §6 table for exactly what "success rate" and "inventory summary" need to contain, derive the DTO shape from that rather than guessing further fields. `DashboardTrends(List<TrendPoint> lastHour, List<TrendPoint> last24Hours)` where `TrendPoint(Instant bucketStart, long purchaseRequestCount, long orderCount)`.

- [ ] **Step 4: DashboardQueryService + controller**

Implement using whatever repository methods are simplest — direct JPQL `COUNT`/`GROUP BY` queries added to the existing repositories, or in-memory aggregation over `findAll()`-style results if the data volume is small enough that a portfolio demo never needs a real aggregate query (this system has no seed-data generator producing thousands of rows — don't over-engineer the query layer for a scale this project will never reach; a few `@Query` count/group-by methods are enough, this doesn't need a reporting/OLAP layer). Trend bucketing (per-minute for the last hour, per-hour for the last 24 hours) can be done in Java over a single `findByCreatedAtAfter(...)`-style fetch rather than a SQL `date_trunc` — simpler to read, and again, this dataset is never going to be large enough for that to matter.

- [ ] **Step 5: Run test to verify it passes, run full suite, commit**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.adapter.web.AdminDashboardControllerIT"` then `./gradlew test`
Expected: PASS

```bash
git add backend/src/main/java/com/flashsale/admin backend/src/test/java/com/flashsale/admin
git commit -m "feat: add admin dashboard summary and trends API"
```

---

## Task 6: Admin API Logs + Orders API

**Files:**
- Create: `backend/src/main/java/com/flashsale/admin/application/ApiAuditQueryService.java`, `AdminOrderQueryService.java`, `dto/ApiAuditLogView.java`, `dto/AdminOrderDetail.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/AdminApiLogController.java`, `AdminOrderController.java`
- Test: `backend/src/test/java/com/flashsale/admin/adapter/web/AdminApiLogControllerIT.java`, `AdminOrderControllerIT.java`

**Interfaces:**
- Consumes: `api_audit_logs` (Task 2), `orders`/`order_items` (existing `OrderRepository`, add an admin-scoped paged `findAll` if it doesn't have one — the existing interface only has `findAllByUserId`, per-user, not what admin needs), `purchase_requests` (`PurchaseRequestRepository.findByOrderId`, already exists), `order_status_history` (Task 3).
- Produces: `GET /api/admin/api-logs` (filterable, paged), `GET /api/admin/orders` (paged), `GET /api/admin/orders/{id}` → `AdminOrderDetail` (order + items + linked purchase request + status history + related audit-log rows joined by `trace_id`).

- [ ] **Step 1: Write the failing ITs**

`AdminApiLogControllerIT` — seed a few `api_audit_logs` rows directly via JDBC with varying `method`/`path_template`/`status`/`user_id`/`trace_id`/`occurred_at`, hit `GET /api/admin/api-logs` with each filter param individually and assert only matching rows come back; also a combined-filter case.

`AdminOrderControllerIT` — seed an order + items + purchase request + a couple of `order_status_history` rows + an `api_audit_logs` row sharing the same `trace_id` as the order's creation, hit `GET /api/admin/orders` (list) and `GET /api/admin/orders/{id}` (detail), assert the detail response includes the history entries in chronological order and the linked audit-log row.

- [ ] **Step 2: Run tests to verify they fail**

Run both `--tests` targets.
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

`ApiAuditQueryService`/`AdminApiLogController`: straightforward filtered-paged query over `ApiAuditLogJpaRepository` (Task 2 already created this repository — add a `Specification`-based or multiple derived-query-method approach for the filter combinations, whichever is less code; don't build a generic dynamic-query framework for four optional filter fields, a handful of `if (param != null) spec = spec.and(...)` lines is enough).

`AdminOrderQueryService.getDetail(Long orderId)`: fetch the order, its items, `purchaseRequestRepository.findByOrderId(orderId)` for the linked request, `orderStatusHistoryRepository.findByOrderId(orderId)` for the timeline, and for the audit-log correlation — the order's *creation* doesn't have a `trace_id` stored on the `orders` row itself (nothing in this plan adds one), so "audit logs related to this order" has to be derived some other way. Simplest honest approach: don't try to auto-correlate via a stored trace_id on the order (that would need yet another column/migration this plan hasn't scoped) — instead, correlate via the linked `purchase_request`'s `id`/`request_id` if any audit log's `path_template` matches a purchase-request-related path AND falls within the order's creation timeframe, OR — simpler still — just don't attempt automatic correlation in the DTO at all and instead give `AdminOrderDetail` a `traceIds: List<String>` field populated from any audit-log rows whose timestamp falls within a tight window of the order's status-history timestamps, letting the admin UI show "audit logs around this time" as a manual cross-reference link (`/admin/api-logs?traceId=...`) rather than a fully automatic join. **Make a judgment call here and note which approach you took in your self-review** — the design spec describes the *intent* (trace correlation) but the exact mechanism has a real gap (no trace_id stored on `orders`) that the spec didn't fully resolve; don't silently paper over it, and don't scope-creep into adding a new column/migration to fix it perfectly either. A `List<ApiAuditLogView>` filtered by `user_id = order.getUserId()` and a time window around the order's `PENDING_PAYMENT` creation timestamp is a reasonable, honest middle ground.

- [ ] **Step 4: Run tests to verify they pass, run full suite, commit**

Run both `--tests` targets, then `./gradlew test`.
Expected: PASS

```bash
git add backend/src/main/java/com/flashsale/admin backend/src/test/java/com/flashsale/admin
git commit -m "feat: add admin API log and order query endpoints"
```

---

## Task 7: Admin Notifications API

**Files:**
- Create: `backend/src/main/java/com/flashsale/admin/application/AdminNotificationService.java`, `dto/NotificationView.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/AdminNotificationController.java`, `dto/ReadStatusRequest.java`
- Test: `backend/src/test/java/com/flashsale/admin/adapter/web/AdminNotificationControllerIT.java`

**Interfaces:**
- Consumes: `notification_deliveries` (Task 4's timestamp mapping), `NotificationRetryService.retry` (Task 4).
- Produces: `GET /api/admin/notifications` (paged, filterable by user/channel/status/read), `GET /api/admin/notifications/{id}`, `PATCH /api/admin/notifications/read-status` (`{ ids: number[], read: boolean }`), `POST /api/admin/notifications/{id}/retry`, `GET /api/admin/notifications/unread-count` (design spec §6 — the one endpoint added beyond the main spec's literal list, for the nav bell).

- [ ] **Step 1: Write the failing IT**

`AdminNotificationControllerIT` — seed several `notification_deliveries` rows (mixed read/unread, mixed status, mixed channel) via JDBC. Test each endpoint:
- list with each filter combination
- detail by ID
- `PATCH read-status` with a set of IDs → assert exactly those rows flip `is_read`, others untouched
- `POST {id}/retry` on a `FAILED` row → assert it attempts a send (same verification mechanism as Task 4's retry test) regardless of `attempt_count` (seed one at the cap, `attempt_count = 3`, and confirm the manual retry still fires — this is the behavior distinguishing it from the scheduler's auto-retry, assert it explicitly)
- `unread-count` → matches the seeded unread total

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.adapter.web.AdminNotificationControllerIT"`
Expected: FAIL to compile.

- [ ] **Step 3: NotificationDeliveryRepository additions**

Add whatever paged/filtered query methods `AdminNotificationService` needs (list with optional filters, `findByIdIn` for the batch read-status update, `countByRead(false)` for unread-count) — same "a few explicit methods, not a generic query builder" discipline as Task 6.

- [ ] **Step 4: Implement service + controller**

`retry(id)` delegates straight to Task 4's `NotificationRetryService.retry(id)` — don't reimplement the retry logic here, this controller is a thin wrapper.

- [ ] **Step 5: Run test to verify it passes, run full suite, commit**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.adapter.web.AdminNotificationControllerIT"` then `./gradlew test`
Expected: PASS

```bash
git add backend/src/main/java/com/flashsale/admin backend/src/main/java/com/flashsale/notification/application/NotificationDeliveryRepository.java backend/src/test/java/com/flashsale/admin
git commit -m "feat: add admin notification center API"
```

---

## Task 8: Frontend — Role Awareness, RequireAdmin, Admin Shell

**Files:**
- Modify: `frontend/package.json` (add `recharts`, `@tanstack/react-table`)
- Modify: `frontend/src/features/auth/useAuth.tsx`, `frontend/src/App.tsx`
- Create: `frontend/src/features/admin/RequireAdmin.tsx`, `RequireAdmin.test.tsx`, `AdminNav.tsx`, `AdminNav.css`
- Create: `frontend/src/api/adminApi.ts` (base file — only shared types/helpers here; Tasks 9-11 each add their own functions to it)
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: nothing new from the backend directly (decodes the already-issued JWT client-side).
- Produces: `useAuth() -> { ..., role: 'USER' | 'ADMIN' | null }`, `<RequireAdmin />` (Task 9/10/11's pages nest under it).

- [ ] **Step 1: Add the two new frontend dependencies**

```bash
cd frontend
npm install recharts @tanstack/react-table
```
Both are already in the main spec's tech stack (§3) — this isn't a new decision, just the first task that actually needs them (design spec §10). No other new dependencies.

- [ ] **Step 2: Write the failing RequireAdmin test**

`frontend/src/features/admin/RequireAdmin.test.tsx` — same pattern as `RequireAuth.test.tsx` (inject `AuthContext` directly): three cases — `role: null` (not logged in) redirects to `/`, `role: 'USER'` redirects to `/`, `role: 'ADMIN'` renders the nested route via `<Outlet />`.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/admin/RequireAdmin.test.tsx`
Expected: FAIL to compile.

- [ ] **Step 4: Decode role in useAuth.tsx**

Read the current file first (Week 3 already has `isAuthenticated`/`login`/`logout`/`markAuthenticated` here). Add:
```tsx
function decodeRole(accessToken: string): 'USER' | 'ADMIN' | null {
  try {
    const payload = JSON.parse(atob(accessToken.split('.')[1]))
    return payload.role === 'ADMIN' ? 'ADMIN' : payload.role === 'USER' ? 'USER' : null
  } catch {
    return null
  }
}
```
`AuthContextValue` gains `role: 'USER' | 'ADMIN' | null`. `markAuthenticated` changes signature from `() => void` to `(data: { accessToken: string }) => void`, setting both `isAuthenticated` and `role` (decoded from `data.accessToken`) — check `App.tsx`'s `authApi.refresh().then(markAuthenticated)` call: `refresh()` already resolves to `{ accessToken }`, so this just works once the signature changes, no call-site change needed there. `login(email, password)` internally now does `const data = await authApi.login(email, password); markAuthenticated(data)` instead of the current `await authApi.login(...); setIsAuthenticated(true)` — reuse `markAuthenticated` rather than duplicating the decode logic. `logout()` additionally resets `role` to `null`.

- [ ] **Step 5: RequireAdmin**

`frontend/src/features/admin/RequireAdmin.tsx`:
```tsx
import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function RequireAdmin() {
  const { role } = useAuth()
  if (role !== 'ADMIN') {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
```
(Redirects to `/`, not `/login`, per design spec §7.1 — reaching this component at all means `RequireAuth`-style login-state issues don't apply; the failure mode here is "logged in but not an admin," and sending that user to a login form they'd correctly re-authenticate through and land right back at the same wall is a worse experience than just bouncing them to the page they *can* use.)

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/admin/RequireAdmin.test.tsx`
Expected: PASS

- [ ] **Step 7: Wire `/admin` into the router (empty children — Tasks 9-11 fill them in)**

`frontend/src/router.tsx` — add:
```tsx
import { RequireAdmin } from './features/admin/RequireAdmin'
// ...
  {
    element: <RequireAdmin />,
    children: [],
  },
```
(A second top-level layout route alongside the existing `RequireAuth` one — `/admin/*` paths go here, `RequireAuth`'s existing children are untouched.)

- [ ] **Step 8: AdminNav skeleton**

`AdminNav.tsx` — a minimal nav for pages under `/admin` (links to dashboard/api-logs/orders/notifications; Task 11 adds the bell icon count to it, not this task — keep this step to just the navigational shell). Not used by any page yet until Tasks 9-11 render their pages inside it — that's fine, an unused-but-correct component isn't a violation of YAGNI, the alternative (writing it piecemeal across three later tasks) is worse.

- [ ] **Step 9: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS, 100% green, all prior tests unaffected by the `markAuthenticated` signature change (double check `App.tsx`'s usage compiles — `npx tsc --noEmit` too).

```bash
cd frontend
git add package.json package-lock.json src/features/auth/useAuth.tsx src/App.tsx src/features/admin src/api/adminApi.ts src/router.tsx
git commit -m "feat: add role-aware auth context and admin route guard"
```

---

## Task 9: Frontend — Dashboard + Trends

**Files:**
- Modify: `frontend/src/api/adminApi.ts`
- Create: `frontend/src/features/admin/AdminDashboardPage.tsx`, `AdminDashboardPage.css`, `AdminDashboardPage.test.tsx`
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: `GET /api/admin/dashboard/summary`, `GET /api/admin/dashboard/trends` (Task 5).
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: API client functions**

Add to `adminApi.ts`: `getDashboardSummary()`, `getDashboardTrends()`, with TypeScript interfaces matching Task 5's `DashboardSummary`/`DashboardTrends` DTOs field-for-field.

- [ ] **Step 2: Write the failing test**

Mock both API functions, render `AdminDashboardPage`, assert the summary numbers render and (for the trend chart) that *something* Recharts-related is in the DOM — don't try to assert on SVG path data or pixel positions, that's testing the charting library, not this component; asserting the chart container renders and doesn't crash with the mocked data is enough.

- [ ] **Step 3: Implement**

Summary cards (plain `.stat-card`s using the ticket-stub design system's existing tokens — reuse `var(--go)`/`var(--wait)`/`var(--stop)` where a stat has a natural semantic color, e.g. success rate). Two `<LineChart>` or `<BarChart>` (Recharts) for the hour/24-hour trends — keep the chart config minimal (no custom tooltips, no animation config beyond Recharts' defaults, no theming beyond passing the design system's token colors into `stroke`/`fill` props).

- [ ] **Step 4: Route + nav link**

Nest `{ path: '/admin', element: <AdminDashboardPage /> }` under the `RequireAdmin` route added in Task 8. Wrap the page content with `<AdminNav />`.

- [ ] **Step 5: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS

```bash
cd frontend
git add src/api/adminApi.ts src/features/admin/AdminDashboardPage.tsx src/features/admin/AdminDashboardPage.css src/features/admin/AdminDashboardPage.test.tsx src/router.tsx
git commit -m "feat: add admin dashboard page with trend charts"
```

---

## Task 10: Frontend — API Logs + Orders Pages

**Files:**
- Modify: `frontend/src/api/adminApi.ts`
- Create: `frontend/src/features/admin/ApiLogsPage.tsx`, `AdminOrdersPage.tsx`, `AdminOrderDetailPage.tsx` (+ `.css`/`.test.tsx` for each)
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: `GET /api/admin/api-logs`, `GET /api/admin/orders`, `GET /api/admin/orders/{id}` (Task 6).
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: API client functions + tests**

Add `listApiLogs(filters)`, `listAdminOrders(filters)`, `getAdminOrderDetail(id)` to `adminApi.ts`. TDD each page the same way as every prior page in this plan: failing test with mocked API responses first, then implement.

- [ ] **Step 2: ApiLogsPage**

Filter form (time range, path, status, userId, traceId — plain controlled inputs, react-hook-form optional here since there's no validation schema worth Zod for a filter form, five plain `useState`s or one small form object is enough, don't force every form in this codebase through the same heavyweight pattern used for auth forms that actually need validation). Table via `@tanstack/react-table`'s `useReactTable` + `getCoreRowModel`/`getSortedRowModel` — column defs for the fields in `ApiAuditLogView`. No pagination UI beyond what the backend's `Pageable` response already gives you (page/size query params, prev/next buttons) — don't build client-side virtual scrolling.

- [ ] **Step 3: AdminOrdersPage + AdminOrderDetailPage**

List: reuse the `@tanstack/react-table` pattern from Step 2. Detail: order info, linked purchase request, `<StatusPill>` (existing shared component from Week 3, reuse it — admin order statuses are the same `OrderStatus` enum), a status-history timeline (simple ordered list, `from → to` at each `changed_at`, no timeline-visualization library), and the related-audit-logs list from Task 6 (each row links to `/admin/api-logs?traceId=...` — actually check what Task 6 actually shipped for the correlation mechanism, per Task 6 Step 3's note that this had a real design gap resolved by judgment call at implementation time; render whatever field `AdminOrderDetail` actually ended up with, don't assume `traceIds` if the implementer chose a different shape).

- [ ] **Step 4: Routes**

Nest both under `RequireAdmin`'s `children`, alongside `/admin` from Task 9 (add to the array, don't remove the existing entry).

- [ ] **Step 5: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS

```bash
cd frontend
git add src/api/adminApi.ts src/features/admin/ApiLogsPage.* src/features/admin/AdminOrdersPage.* src/features/admin/AdminOrderDetailPage.* src/router.tsx
git commit -m "feat: add admin API log and order query pages"
```

---

## Task 11: Frontend — Notification Center + Nav Bell

**Files:**
- Modify: `frontend/src/api/adminApi.ts`, `frontend/src/components/AppNav.tsx`, `AppNav.css`
- Create: `frontend/src/features/admin/AdminNotificationsPage.tsx`, `AdminNotificationDetailPage.tsx` (+ `.css`/`.test.tsx` for each)
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: `GET /api/admin/notifications`, `GET /api/admin/notifications/{id}`, `PATCH /api/admin/notifications/read-status`, `POST /api/admin/notifications/{id}/retry`, `GET /api/admin/notifications/unread-count` (Task 7).
- Produces: nothing consumed elsewhere — last task in the plan (before Task 12's k6 script, which doesn't depend on this).

- [ ] **Step 1: API client functions**

Add the five corresponding functions to `adminApi.ts`.

- [ ] **Step 2: Nav bell (site-wide, not just under `/admin`)**

Modify `AppNav.tsx` (the shared nav used on every logged-in-facing page since Week 3, not `AdminNav.tsx`) — per design spec §7.4, the bell is visible to any logged-in ADMIN anywhere in the site, not just inside `/admin`. Add: `role === 'ADMIN' && <Link to="/admin/notifications" className="nav-bell">🔔 {unreadCount > 0 && <span className="badge">{unreadCount}</span>}</Link>`, where `unreadCount` comes from `useQuery({ queryKey: ['admin', 'notifications', 'unread-count'], queryFn: getUnreadCount, enabled: role === 'ADMIN', refetchInterval: 30000 })`. Test: extend `AppNav`'s existing tests (there may not be a dedicated `AppNav.test.tsx` yet — check; if the bell has never been directly tested because `AppNav` has always been tested indirectly through the pages that render it, add one focused test here rather than leaving it uncovered, given it's the one piece of this task visible outside `/admin`).

- [ ] **Step 3: AdminNotificationsPage**

Filter form (user/channel/status/read — same lightweight-form approach as Task 10's `ApiLogsPage`, not a react-hook-form+Zod form). List with checkboxes + a "標記已讀"/"標記未讀" batch action bar that appears once ≥1 row is checked, calling `PATCH read-status`. Each row links to the detail page.

- [ ] **Step 4: AdminNotificationDetailPage**

Full record display; a "重新排程" button visible only when `status === 'FAILED'`, calling `POST {id}/retry`, then refetching (or optimistically updating — this one's arguably fine as an exception to Week 3's "no optimistic updates" rule since it's an admin action expecting near-immediate feedback and a stale "still FAILED" flash before the refetch lands would read as broken, but simplest is just to `invalidateQueries` and show a loading state, matching every other mutation in this codebase — don't introduce the app's first optimistic update for this one button unless the plain refetch genuinely feels bad once you see it running).

- [ ] **Step 5: Routes**

Nest both under `RequireAdmin`'s `children`, alongside Tasks 9-10's entries.

- [ ] **Step 6: Run full suite**

Run: `cd frontend && npx vitest run`
Expected: PASS — this is the last frontend task in the plan, so a fully green suite here closes out the whole frontend side of Week 4.

- [ ] **Step 7: Commit**

```bash
cd frontend
git add src/api/adminApi.ts src/components/AppNav.tsx src/components/AppNav.css src/features/admin/AdminNotificationsPage.* src/features/admin/AdminNotificationDetailPage.* src/router.tsx
git commit -m "feat: add admin notification center and nav bell"
```

---

## Task 12: k6 Purchase-Flow Correctness Script

**Files:**
- Create: `load-tests/purchase-flow.js`, `load-tests/README.md`

**Interfaces:**
- Consumes: the full running stack (`docker compose up`), same as every manual walkthrough in this plan's predecessors.
- Produces: nothing consumed elsewhere — last task in the plan.

- [ ] **Step 1: Seed a flash sale with known, small stock**

The script needs a flash sale with a small, known `available_quantity` (e.g. 10) and more virtual users than stock (e.g. 30) to meaningfully exercise the sold-out path. Document in `load-tests/README.md` the exact seed SQL to run first (same shape as the backend's existing `inventory-fixtures.sql` pattern — reuse those values/structure as a reference, don't invent a different fixture convention for this one script).

- [ ] **Step 2: Write the script**

`load-tests/purchase-flow.js` — k6 scenario with N VUs (configurable via `__ENV`, default matching the seed from Step 1). Each VU: register a unique user, log in, `POST /api/flash-sales/{id}/purchase-requests`, poll `GET /api/purchase-requests/{requestId}` until a terminal status (a simple retry loop with `sleep(1)`, matching the frontend's own 1-second polling interval — no need to invent different timing here). Use k6 `check()` to assert per-VU: the purchase-request call itself returns `202` (never a raw 5xx, matching the existing invariant `PurchaseConcurrencyIT` already proves at the backend-test level — this script proves the same thing end-to-end through Nginx).

- [ ] **Step 3: Post-run correctness verification**

After the k6 run completes, verify the aggregate invariants (matches the design spec §9 list) — either as a k6 `handleSummary`/teardown step hitting an admin endpoint, or (simpler, and fine per the design spec's explicit "no CI integration this round") just documented in the README as a manual `psql` check to run after the script: `SELECT COUNT(*) FROM orders` equals the seeded stock count, `SELECT COUNT(*) FROM purchase_requests WHERE status = 'SOLD_OUT'` equals `VU count - stock count`.

- [ ] **Step 4: Manual run against the real stack**

`docker compose up --build -d`, seed per Step 1, run `k6 run load-tests/purchase-flow.js` (document the exact command, including any `-e VUS=...` override, in the README), verify the Step 3 invariants hold. Record the actual observed numbers (request duration, throughput) in the README as-is — per design spec §9/main spec §15, there's no target to hit, this is a correctness check that happens to also produce numbers worth writing down for Week 6's portfolio writeup later.

- [ ] **Step 5: Commit**

```bash
git add load-tests
git commit -m "test: add k6 script proving no oversell/no duplicate orders under load"
```
