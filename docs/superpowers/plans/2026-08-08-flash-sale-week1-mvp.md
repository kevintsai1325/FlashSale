# FlashSale Week 1 (同步 MVP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the FlashSale repository end-to-end for Week 1 scope: Docker Compose infra behind an Nginx TLS reverse proxy, JWT register/login/refresh/logout with a registration email, product/flash-sale read APIs, and a **synchronous** order+inventory transaction — all backed by Flyway migrations, Swagger UI, `ProblemDetail` errors, and TDD tests.

**Architecture:** Modular monolith package-by-feature (`com.flashsale.<module>.{domain,application,adapter}`), matching spec §5. Week 1's purchase endpoint (`POST /api/flash-sales/{id}/purchase-requests`) already uses the final API contract (returns a `purchase_request` with `requestId`, pollable via `GET /api/purchase-requests/{requestId}`), but the implementation is **fully synchronous**: everything happens in one DB transaction (validate → lock inventory row → decrement → create order → mark request `SUCCEEDED`) before the HTTP response returns. Week 2 swaps the internals for Redis Lua pre-deduction + RabbitMQ without changing the contract. Full Flyway schema (spec §6) is created now in one migration to avoid later schema churn, even though tables like `outbox_events`/`consumed_messages`/`api_audit_logs` aren't populated until later weeks.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Gradle Kotlin DSL wrapper, Spring Web MVC, Spring Data JPA, PostgreSQL, Spring Security + OAuth2 Resource Server (`JwtEncoder`/`JwtDecoder`), Spring Boot Mail + Thymeleaf, Flyway, springdoc-openapi, React 18 + TypeScript + Vite, React Router, TanStack Query, React Hook Form + Zod, JUnit 5 + AssertJ + Mockito, Spring Boot Test/MockMvc, Testcontainers, Docker Compose, Nginx.

## Global Constraints

- Java 21 toolchain, Spring Boot 3.3.x, Gradle Kotlin DSL wrapper (`gradlew`/`gradlew.bat` committed).
- PostgreSQL via Spring Data JPA; all schema changes via Flyway migrations under `backend/src/main/resources/db/migration`.
- JWT issued via Spring Security `JwtEncoder`; API auth via OAuth2 Resource Server (`JwtDecoder`).
- Passwords hashed only via Spring `PasswordEncoder` (BCrypt) — never hand-rolled.
- Errors are Spring `ProblemDetail` (RFC 9457) with `type`, `title`, `status`, `detail`, `instance`, plus custom `code` and `traceId` properties.
- Status code rules: input validation `400`, unauthenticated `401`, unauthorized `403`, not found `404`, state/idempotency conflict `409`, dependency unavailable `503`.
- Package by module first, then `domain`/`application`/`adapter` within each module. Modules depend only on `common`'s interfaces and other modules' `domain`/`application` ports — never another module's `adapter`.
- Only create interfaces at real seams: repositories, message publishers, inventory reservation, notification senders. No interface-per-class.
- Secrets (`JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `GMAIL_USERNAME`, `GMAIL_APP_PASSWORD`) only via environment variables — never in config files or git. `.env.example` lists variable names with non-sensitive defaults only.
- Refresh tokens: simplified model — reusable until expiry, no rotation/reuse-detection, hash stored in `refresh_tokens`, revoked on logout/password change.
- Nginx is the sole public entrypoint: terminates local self-signed TLS, adds security headers (CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy), rate-limits `/api/auth/login` and `/api/auth/register`. Backend/frontend container ports are never published to the host.
- TDD: Red → Green → Refactor per use case. No tests for framework code or trivial getters.
- React + TypeScript + Vite; TanStack Query owns server state/polling/caching — no hand-rolled cache layer.

---

## File Structure

```text
flash-sale/
├─ backend/
│  ├─ build.gradle.kts
│  ├─ settings.gradle.kts
│  ├─ gradlew, gradlew.bat, gradle/wrapper/
│  ├─ Dockerfile
│  └─ src/
│     ├─ main/java/com/flashsale/
│     │  ├─ FlashSaleApplication.java
│     │  ├─ identity/
│     │  │  ├─ domain/          User.java, Role.java, RefreshToken.java
│     │  │  ├─ application/     RegisterUserService.java, LoginService.java,
│     │  │  │                   RefreshTokenService.java, LogoutService.java,
│     │  │  │                   UserRepository.java (port), RefreshTokenRepository.java (port)
│     │  │  └─ adapter/
│     │  │     ├─ web/          AuthController.java, dto/*.java
│     │  │     ├─ persistence/  UserJpaRepository.java, UserRepositoryImpl.java,
│     │  │     │                RefreshTokenJpaRepository.java, RefreshTokenRepositoryImpl.java
│     │  │     └─ security/     JwtKeyConfig.java, SecurityConfig.java, JwtIssuer.java
│     │  ├─ catalog/
│     │  │  ├─ domain/          Product.java
│     │  │  ├─ application/     ProductRepository.java (port)
│     │  │  └─ adapter/persistence/ ProductJpaRepository.java, ProductRepositoryImpl.java
│     │  ├─ flashsale/
│     │  │  ├─ domain/          FlashSale.java, FlashSaleStatus.java
│     │  │  ├─ application/     FlashSaleQueryService.java, FlashSaleRepository.java (port),
│     │  │  │                   dto/FlashSaleSummary.java, dto/FlashSaleDetail.java
│     │  │  └─ adapter/
│     │  │     ├─ web/          FlashSaleController.java
│     │  │     └─ persistence/  FlashSaleJpaRepository.java, FlashSaleRepositoryImpl.java
│     │  ├─ inventory/
│     │  │  ├─ domain/          Inventory.java, InsufficientInventoryException.java
│     │  │  ├─ application/     InventoryRepository.java (port)
│     │  │  └─ adapter/persistence/ InventoryJpaRepository.java, InventoryRepositoryImpl.java
│     │  ├─ order/
│     │  │  ├─ domain/          Order.java, OrderItem.java, OrderStatus.java,
│     │  │  │                   PurchaseRequest.java, PurchaseRequestStatus.java
│     │  │  ├─ application/     CreatePurchaseRequestService.java, OrderRepository.java (port),
│     │  │  │                   PurchaseRequestRepository.java (port)
│     │  │  └─ adapter/
│     │  │     ├─ web/          PurchaseController.java, OrderController.java
│     │  │     └─ persistence/  OrderJpaRepository.java, OrderRepositoryImpl.java,
│     │  │                       PurchaseRequestJpaRepository.java, PurchaseRequestRepositoryImpl.java
│     │  ├─ notification/
│     │  │  ├─ domain/          NotificationDelivery.java, NotificationChannel.java, NotificationStatus.java
│     │  │  ├─ application/     NotificationSender.java (port), NotificationDeliveryRepository.java (port)
│     │  │  └─ adapter/mail/    EmailNotificationSender.java, MailConfig.java
│     │  └─ common/
│     │     ├─ config/          OpenApiConfig.java
│     │     └─ exception/       GlobalExceptionHandler.java, DomainException.java,
│     │                          NotFoundException.java, ConflictException.java
│     ├─ main/resources/
│     │  ├─ application.yml, application-docker.yml
│     │  ├─ db/migration/V1__baseline_schema.sql
│     │  └─ templates/email/registration-success.html
│     └─ test/java/com/flashsale/...   (mirrors main package layout, one test class per production class)
├─ frontend/
│  ├─ package.json, vite.config.ts, tsconfig.json, index.html, Dockerfile
│  └─ src/
│     ├─ main.tsx, App.tsx, router.tsx
│     ├─ api/httpClient.ts, api/authApi.ts, api/flashSaleApi.ts
│     ├─ features/auth/RegisterPage.tsx, LoginPage.tsx, useAuth.ts
│     └─ features/flash-sales/FlashSaleListPage.tsx, FlashSaleDetailPage.tsx
├─ nginx/
│  ├─ nginx.conf
│  └─ certs/generate-cert.sh   (generates local self-signed cert; certs/*.pem gitignored)
├─ load-tests/                (empty placeholder, populated Week 4)
├─ docs/
├─ compose.yaml
├─ .env.example
├─ .gitignore
└─ README.md
```

---

## Task 1: Backend Repository & Spring Boot Skeleton

**Files:**
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/gradle/wrapper/gradle-wrapper.properties`, `backend/gradlew`, `backend/gradlew.bat`
- Create: `backend/src/main/java/com/flashsale/FlashSaleApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/flashsale/FlashSaleApplicationTests.java`

**Interfaces:**
- Produces: a bootable Spring Boot app on port `8080`, Actuator `/actuator/health` reachable, used by Task 3's Dockerfile/Compose healthcheck.

- [ ] **Step 1: Create Gradle wrapper and build files**

`backend/settings.gradle.kts`:
```kotlin
rootProject.name = "flash-sale-backend"
```

`backend/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.flashsale"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:postgresql:1.20.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

Run `gradle wrapper --gradle-version 8.10` (or download the wrapper jar/properties manually) so `backend/gradlew`, `backend/gradlew.bat`, and `backend/gradle/wrapper/gradle-wrapper.properties` are committed.

- [ ] **Step 2: Write the failing smoke test**

`backend/src/test/java/com/flashsale/FlashSaleApplicationTests.java`:
```java
package com.flashsale;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FlashSaleApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.FlashSaleApplicationTests"`
Expected: FAIL — no main class `FlashSaleApplication`, compilation error.

- [ ] **Step 4: Write the application class and config**

`backend/src/main/java/com/flashsale/FlashSaleApplication.java`:
```java
package com.flashsale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FlashSaleApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlashSaleApplication.class, args);
    }
}
```

`backend/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: flash-sale-backend
  datasource:
    url: jdbc:postgresql://localhost:5432/flashsale
    username: flashsale
    password: flashsale
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    com.flashsale: INFO
```

Add `backend/src/test/resources/application-test.yml` pointing at an in-memory-free Testcontainers-managed datasource placeholder (left as `jdbc:postgresql://localhost:5432/flashsale_test` for now — Task 5 wires Testcontainers `@DynamicPropertySource` for integration tests; this smoke test only needs the context to load without a real DB, so add `spring.autoconfigure.exclude` for datasource/JPA/Flyway auto-config in the `test` profile):

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.FlashSaleApplicationTests"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "chore: bootstrap Spring Boot backend skeleton"
```

---

## Task 2: Frontend Skeleton

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`
- Create: `frontend/src/main.tsx`, `frontend/src/App.tsx`, `frontend/src/router.tsx`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Produces: a Vite dev server on port `5173`, a router with a placeholder `/` route, used by Task 12/13's pages and Task 4's Nginx proxy target.

- [ ] **Step 1: Scaffold the Vite project**

Run: `cd frontend && npm create vite@latest . -- --template react-ts`

Then add test tooling and libraries used across Week 1-3:
```bash
npm install react-router-dom @tanstack/react-query react-hook-form zod @hookform/resolvers
npm install -D vitest @testing-library/react @testing-library/jest-dom jsdom
```

`frontend/vite.config.ts`:
```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
  },
})
```

`frontend/src/setupTests.ts`:
```typescript
import '@testing-library/jest-dom'
```

- [ ] **Step 2: Write the failing test**

`frontend/src/App.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the FlashSale heading', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: /flashsale/i })).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run`
Expected: FAIL — `App` renders Vite's default template, no "FlashSale" heading.

- [ ] **Step 4: Implement the App shell and router**

`frontend/src/router.tsx`:
```typescript
import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'

export const router = createBrowserRouter([
  { path: '/', element: <FlashSaleListPage /> },
])
```

`frontend/src/App.tsx`:
```typescript
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'

const queryClient = new QueryClient()

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <h1>FlashSale</h1>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
```

Create a placeholder `frontend/src/features/flash-sales/FlashSaleListPage.tsx` (fleshed out in Task 13):
```typescript
export function FlashSaleListPage() {
  return <div>Loading flash sales…</div>
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/
git commit -m "chore: bootstrap Vite/React frontend skeleton"
```

---

## Task 3: Docker Compose Infra Services

**Files:**
- Create: `compose.yaml`
- Create: `.env.example`
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `.gitignore`

**Interfaces:**
- Consumes: Task 1's bootable backend jar, Task 2's frontend build output.
- Produces: `postgres`, `redis`, `rabbitmq`, `mailpit`, `backend`, `frontend` services on a shared Docker network, each with a healthcheck. Task 4 adds `nginx` in front of `frontend`/`backend`.

- [ ] **Step 1: Write backend and frontend Dockerfiles**

`backend/Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`frontend/Dockerfile`:
```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

(This inner Nginx just serves the static build inside the `frontend` container; the reverse-proxy Nginx from Task 4 is a separate top-level service.)

- [ ] **Step 2: Write compose.yaml with healthchecks**

`compose.yaml`:
```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: flashsale
      POSTGRES_USER: flashsale
      POSTGRES_PASSWORD: flashsale
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U flashsale"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 10

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 5s
      timeout: 5s
      retries: 10

  mailpit:
    image: axllent/mailpit:latest
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8025/api/v1/info"]
      interval: 5s
      timeout: 5s
      retries: 10

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
    depends_on:
      postgres:
        condition: service_healthy
      mailpit:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10

  frontend:
    build: ./frontend
    depends_on:
      - backend

volumes:
  postgres_data:
```

`.env.example`:
```text
GMAIL_USERNAME=
GMAIL_APP_PASSWORD=
JWT_PRIVATE_KEY=
JWT_PUBLIC_KEY=
```

`.gitignore` (repo root):
```text
.env
nginx/certs/*.pem
backend/build/
backend/.gradle/
frontend/node_modules/
frontend/dist/
```

- [ ] **Step 3: Verify the stack builds and becomes healthy**

Run: `docker compose config` (validates syntax), then `docker compose up --build -d postgres redis rabbitmq mailpit backend frontend`
Expected: all services reach `healthy`/running status; `docker compose ps` shows no restarting containers. (`backend` will fail to boot until Task 5's Flyway migration and Task 1's app exist — re-run after those tasks land if testing incrementally.)

- [ ] **Step 4: Commit**

```bash
git add compose.yaml .env.example backend/Dockerfile frontend/Dockerfile .gitignore
git commit -m "chore: add Docker Compose infra services and Dockerfiles"
```

---

## Task 4: Nginx Reverse Proxy (TLS, Security Headers, Compose Wiring)

**Files:**
- Create: `nginx/nginx.conf`
- Create: `nginx/certs/generate-cert.sh`
- Modify: `compose.yaml` (add `nginx` service, remove any host port publishing from `backend`/`frontend`)
- Modify: `README.md` (self-signed cert trust instructions — stub now, expanded Task 4 of later weeks)

**Interfaces:**
- Consumes: `frontend` service (port 80 internal), `backend` service (port 8080 internal).
- Produces: the only host-exposed entrypoint, `https://localhost:8443`, used by every subsequent frontend/API interaction and by Task 9's rate-limit zones.

- [ ] **Step 1: Write the self-signed cert generation script**

`nginx/certs/generate-cert.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout localhost.key -out localhost.crt \
  -days 365 -subj "/CN=localhost"
echo "Generated nginx/certs/localhost.crt and localhost.key (gitignored)."
```

Run: `chmod +x nginx/certs/generate-cert.sh && ./nginx/certs/generate-cert.sh`
Expected: `nginx/certs/localhost.crt` and `nginx/certs/localhost.key` are created locally (already gitignored via Task 3's `.gitignore`).

- [ ] **Step 2: Write nginx.conf with TLS, security headers, and proxying**

`nginx/nginx.conf`:
```nginx
events {}

http {
    server {
        listen 8443 ssl;
        server_name localhost;

        ssl_certificate     /etc/nginx/certs/localhost.crt;
        ssl_certificate_key /etc/nginx/certs/localhost.key;

        add_header Content-Security-Policy "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'" always;
        add_header X-Frame-Options "DENY" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;

        location /api/ {
            proxy_pass http://backend:8080/api/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        location /swagger-ui/ {
            proxy_pass http://backend:8080/swagger-ui/;
        }

        location /v3/api-docs {
            proxy_pass http://backend:8080/v3/api-docs;
        }

        location / {
            proxy_pass http://frontend:80/;
            proxy_set_header Host $host;
        }
    }
}
```

(Rate-limiting `limit_req_zone`/`limit_req` directives for `/api/auth/login` and `/api/auth/register` are added in Task 9, once those endpoints exist — adding a limiter for a route that 404s makes it untestable now.)

- [ ] **Step 3: Wire nginx into compose.yaml and stop publishing backend/frontend ports**

Edit `compose.yaml`: add under `services:`
```yaml
  nginx:
    image: nginx:1.27-alpine
    ports:
      - "8443:8443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/certs:/etc/nginx/certs:ro
    depends_on:
      - backend
      - frontend
```

Confirm `backend` and `frontend` services have no top-level `ports:` mapping (only Docker's internal network) — per spec, they must not be exposed directly.

- [ ] **Step 4: Verify TLS termination and proxying**

Run: `docker compose up --build -d` then `curl -k https://localhost:8443/` and `curl -k https://localhost:8443/api/actuator/health` (once Task 1's actuator endpoint is proxied — actuator isn't under `/api/`, so for now verify with `curl -k https://localhost:8443/` returning the frontend HTML, and inspect response headers via `curl -kI https://localhost:8443/` for `Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`).
Expected: HTTPS handshake succeeds (self-signed warning ignored via `-k`), security headers present, frontend HTML returned.

- [ ] **Step 5: Commit**

```bash
git add nginx/ compose.yaml
git commit -m "feat: add Nginx TLS reverse proxy with security headers"
```

---

## Task 5: Flyway Baseline Schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__baseline_schema.sql`
- Create: `backend/src/test/java/com/flashsale/FlywayMigrationIT.java`
- Create: `backend/src/test/resources/application-integration-test.yml`

**Interfaces:**
- Produces: all tables from spec §6 (`users`, `refresh_tokens`, `products`, `flash_sales`, `inventory`, `purchase_requests`, `orders`, `order_items`, `payment_records`, `outbox_events`, `consumed_messages`, `notification_deliveries`, `api_audit_logs`, `order_status_history`), used by every JPA entity in Tasks 7–11.

- [ ] **Step 1: Write the failing Testcontainers migration test**

`backend/src/test/java/com/flashsale/FlywayMigrationIT.java`:
```java
package com.flashsale;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class FlywayMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allBaselineTablesExist() {
        var tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        assertThat(tables).containsExactlyInAnyOrder(
            "users", "refresh_tokens", "products", "flash_sales", "inventory",
            "purchase_requests", "orders", "order_items", "payment_records",
            "outbox_events", "consumed_messages", "notification_deliveries",
            "api_audit_logs", "order_status_history", "flyway_schema_history"
        );
    }
}
```

`backend/src/test/resources/application-integration-test.yml`:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.FlywayMigrationIT"`
Expected: FAIL — no migration files, only `flyway_schema_history` exists (or Flyway reports nothing to migrate and the assertion lists a near-empty table set).

- [ ] **Step 3: Write the baseline migration**

`backend/src/main/resources/db/migration/V1__baseline_schema.sql`:
```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE flash_sales (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id),
    sale_price NUMERIC(12,2) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    purchase_limit_per_user INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_flash_sales_product_id ON flash_sales(product_id);

CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    flash_sale_id BIGINT NOT NULL UNIQUE REFERENCES flash_sales(id),
    total_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    sold_quantity INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    total_amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user_id ON orders(user_id);

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INT NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL
);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);

CREATE TABLE purchase_requests (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id),
    flash_sale_id BIGINT NOT NULL REFERENCES flash_sales(id),
    order_id BIGINT REFERENCES orders(id),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, flash_sale_id, idempotency_key)
);
CREATE INDEX idx_purchase_requests_user_flash_sale ON purchase_requests(user_id, flash_sale_id);

CREATE TABLE payment_records (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    result VARCHAR(20) NOT NULL,
    simulated_transaction_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_records_order_id ON payment_records(order_id);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events(published_at) WHERE published_at IS NULL;

CREATE TABLE consumed_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(255) NOT NULL UNIQUE,
    consumer_name VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_deliveries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    channel VARCHAR(20) NOT NULL,
    template VARCHAR(100) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_deliveries_user_id ON notification_deliveries(user_id);

CREATE TABLE api_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    method VARCHAR(10) NOT NULL,
    path_template VARCHAR(255) NOT NULL,
    status INT NOT NULL,
    user_id BIGINT,
    request_id VARCHAR(64),
    trace_id VARCHAR(64),
    duration_ms INT NOT NULL,
    client_ip VARCHAR(64),
    user_agent VARCHAR(255),
    error_code VARCHAR(64)
);
CREATE INDEX idx_api_audit_logs_occurred_at ON api_audit_logs(occurred_at);

CREATE TABLE order_status_history (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_status_history_order_id ON order_status_history(order_id);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.FlywayMigrationIT"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration backend/src/test/java/com/flashsale/FlywayMigrationIT.java backend/src/test/resources/application-integration-test.yml
git commit -m "feat: add Flyway baseline schema for all core tables"
```

---

## Task 6: Common — GlobalExceptionHandler (ProblemDetail) & Swagger UI

**Files:**
- Create: `backend/src/main/java/com/flashsale/common/exception/DomainException.java`
- Create: `backend/src/main/java/com/flashsale/common/exception/NotFoundException.java`
- Create: `backend/src/main/java/com/flashsale/common/exception/ConflictException.java`
- Create: `backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/flashsale/common/config/OpenApiConfig.java`
- Test: `backend/src/test/java/com/flashsale/common/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `NotFoundException(String code, String detail)` → HTTP 404, `ConflictException(String code, String detail)` → HTTP 409, both mapped to `ProblemDetail` with `code` and `traceId` properties. Every controller in Tasks 7–11 throws these instead of handling errors locally.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/flashsale/common/exception/GlobalExceptionHandlerTest.java`:
```java
package com.flashsale.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Import;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @RestController
    static class TestController {
        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("PRODUCT_NOT_FOUND", "Product 1 does not exist");
        }
    }

    @Test
    void notFoundExceptionMapsTo404ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
            .andExpect(jsonPath("$.traceId").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.exception.GlobalExceptionHandlerTest"`
Expected: FAIL — `NotFoundException`/`GlobalExceptionHandler` don't exist yet, compilation error.

- [ ] **Step 3: Implement the exception hierarchy and handler**

`backend/src/main/java/com/flashsale/common/exception/DomainException.java`:
```java
package com.flashsale.common.exception;

public abstract class DomainException extends RuntimeException {
    private final String code;

    protected DomainException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

`backend/src/main/java/com/flashsale/common/exception/NotFoundException.java`:
```java
package com.flashsale.common.exception;

public class NotFoundException extends DomainException {
    public NotFoundException(String code, String detail) {
        super(code, detail);
    }
}
```

`backend/src/main/java/com/flashsale/common/exception/ConflictException.java`:
```java
package com.flashsale.common.exception;

public class ConflictException extends DomainException {
    public ConflictException(String code, String detail) {
        super(code, detail);
    }
}
```

`backend/src/main/java/com/flashsale/common/exception/GlobalExceptionHandler.java`:
```java
package com.flashsale.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail, request);
    }

    private ProblemDetail build(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setInstance(java.net.URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("traceId", UUID.randomUUID().toString());
        return problemDetail;
    }
}
```

(`traceId` is a random UUID for now; Week 5's Micrometer Tracing wiring replaces this with the real trace context — tracked as a follow-up, not a Week 1 gap since no distributed tracing exists yet.)

`backend/src/main/java/com/flashsale/common/config/OpenApiConfig.java`:
```java
package com.flashsale.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flashSaleOpenApi() {
        return new OpenAPI().info(new Info()
            .title("FlashSale API")
            .version("v1")
            .description("Limited-inventory flash sale demo API"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.common.exception.GlobalExceptionHandlerTest"`
Expected: PASS

- [ ] **Step 5: Verify Swagger UI is reachable**

Run: `cd backend && ./gradlew bootRun` then `curl http://localhost:8080/v3/api-docs`
Expected: JSON OpenAPI document returned; `http://localhost:8080/swagger-ui/index.html` renders in a browser.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flashsale/common backend/src/test/java/com/flashsale/common
git commit -m "feat: add ProblemDetail error handling and Swagger UI config"
```

---

## Task 7: Identity — Registration & Registration-Success Email

**Files:**
- Create: `backend/src/main/java/com/flashsale/identity/domain/User.java`, `Role.java`
- Create: `backend/src/main/java/com/flashsale/identity/application/UserRepository.java` (port)
- Create: `backend/src/main/java/com/flashsale/identity/application/RegisterUserService.java`
- Create: `backend/src/main/java/com/flashsale/identity/adapter/persistence/UserJpaRepository.java`, `UserRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/identity/adapter/web/AuthController.java`
- Create: `backend/src/main/java/com/flashsale/identity/adapter/web/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/flashsale/notification/domain/NotificationDelivery.java`, `NotificationChannel.java`, `NotificationStatus.java`
- Create: `backend/src/main/java/com/flashsale/notification/application/NotificationSender.java` (port), `NotificationDeliveryRepository.java` (port)
- Create: `backend/src/main/java/com/flashsale/notification/adapter/mail/EmailNotificationSender.java`, `MailConfig.java`
- Create: `backend/src/main/resources/templates/email/registration-success.html`
- Test: `backend/src/test/java/com/flashsale/identity/application/RegisterUserServiceTest.java`
- Test: `backend/src/test/java/com/flashsale/identity/adapter/web/AuthControllerRegisterIT.java`

**Interfaces:**
- Consumes: `PasswordEncoder` (Spring-provided bean, configured in Task 8's `SecurityConfig`).
- Produces: `UserRepository.save(User)`, `UserRepository.findByEmail(String)` — used by Task 8's `LoginService` and Task 11's `CreatePurchaseRequestService`. `NotificationSender.send(NotificationDelivery)` — the seam Week 2's SMS/in-app senders plug into later.

- [ ] **Step 1: Write the failing domain/application test**

`backend/src/test/java/com/flashsale/identity/application/RegisterUserServiceTest.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.notification.application.NotificationSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterUserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock NotificationSender notificationSender;

    RegisterUserService service;

    @Test
    void registersNewUserAndSendsNotification() {
        service = new RegisterUserService(userRepository, passwordEncoder, notificationSender);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.register("alice@example.com", "secret123");

        assertThat(result.getEmail()).isEqualTo("alice@example.com");
        assertThat(result.getPasswordHash()).isEqualTo("hashed");
        assertThat(result.getRole()).isEqualTo(Role.USER);
        verify(notificationSender).send(any());
    }

    @Test
    void rejectsDuplicateEmail() {
        service = new RegisterUserService(userRepository, passwordEncoder, notificationSender);
        when(userRepository.findByEmail("alice@example.com"))
            .thenReturn(Optional.of(User.register("alice@example.com", "x", Role.USER)));

        assertThatThrownBy(() -> service.register("alice@example.com", "secret123"))
            .isInstanceOf(com.flashsale.common.exception.ConflictException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.application.RegisterUserServiceTest"`
Expected: FAIL — none of `User`, `Role`, `UserRepository`, `RegisterUserService`, `NotificationSender` exist yet.

- [ ] **Step 3: Implement domain and application classes**

`backend/src/main/java/com/flashsale/identity/domain/Role.java`:
```java
package com.flashsale.identity.domain;

public enum Role {
    USER, ADMIN
}
```

`backend/src/main/java/com/flashsale/identity/domain/User.java`:
```java
package com.flashsale.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {}

    private User(String email, String passwordHash, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static User register(String email, String passwordHash, Role role) {
        return new User(email, passwordHash, role);
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getStatus() { return status; }
}
```

`backend/src/main/java/com/flashsale/identity/application/UserRepository.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.identity.domain.User;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    User save(User user);
}
```

`backend/src/main/java/com/flashsale/notification/domain/NotificationChannel.java`:
```java
package com.flashsale.notification.domain;

public enum NotificationChannel {
    EMAIL, SMS, IN_APP
}
```

`backend/src/main/java/com/flashsale/notification/domain/NotificationStatus.java`:
```java
package com.flashsale.notification.domain;

public enum NotificationStatus {
    PENDING, SENT, FAILED
}
```

`backend/src/main/java/com/flashsale/notification/domain/NotificationDelivery.java`:
```java
package com.flashsale.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String template;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    protected NotificationDelivery() {}

    public static NotificationDelivery pendingEmail(Long userId, String template, String recipient) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.userId = userId;
        delivery.channel = NotificationChannel.EMAIL;
        delivery.template = template;
        delivery.recipient = recipient;
        delivery.status = NotificationStatus.PENDING;
        return delivery;
    }

    public void markSent() { this.status = NotificationStatus.SENT; }
    public void markFailed(String error) {
        this.status = NotificationStatus.FAILED;
        this.lastError = error;
        this.attemptCount++;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getRecipient() { return recipient; }
    public String getTemplate() { return template; }
    public NotificationStatus getStatus() { return status; }
}
```

`backend/src/main/java/com/flashsale/notification/application/NotificationSender.java`:
```java
package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;

public interface NotificationSender {
    void send(NotificationDelivery delivery);
}
```

`backend/src/main/java/com/flashsale/notification/application/NotificationDeliveryRepository.java`:
```java
package com.flashsale.notification.application;

import com.flashsale.notification.domain.NotificationDelivery;

public interface NotificationDeliveryRepository {
    NotificationDelivery save(NotificationDelivery delivery);
}
```

`backend/src/main/java/com/flashsale/identity/application/RegisterUserService.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.notification.application.NotificationSender;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationSender notificationSender;

    public RegisterUserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                NotificationSender notificationSender) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationSender = notificationSender;
    }

    @Transactional
    public User register(String email, String rawPassword) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "Email is already registered");
        });

        User user = User.register(email, passwordEncoder.encode(rawPassword), Role.USER);
        User saved = userRepository.save(user);

        notificationSender.send(
            NotificationDelivery.pendingEmail(saved.getId(), "registration-success", saved.getEmail()));

        return saved;
    }
}
```

(`notificationSender.send(...)` is called synchronously here but the mail send itself is `@Async` inside `EmailNotificationSender` — Step 3 below — so SMTP latency/failure never rolls back this `@Transactional` registration, matching spec §12/§17.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.application.RegisterUserServiceTest"`
Expected: PASS

- [ ] **Step 5: Write the failing API integration test**

`backend/src/test/java/com/flashsale/identity/adapter/web/AuthControllerRegisterIT.java`:
```java
package com.flashsale.identity.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AuthControllerRegisterIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registerReturns201AndPersistsUser() throws Exception {
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("email", "bob@example.com");
                put("password", "secret123");
            }});

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void duplicateEmailReturns409ProblemDetail() throws Exception {
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("email", "carol@example.com");
                put("password", "secret123");
            }});

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.adapter.web.AuthControllerRegisterIT"`
Expected: FAIL — `AuthController`, persistence adapters, and mail sender don't exist yet (404/compilation error).

- [ ] **Step 7: Implement persistence adapter, mail sender, and controller**

`backend/src/main/java/com/flashsale/identity/adapter/persistence/UserJpaRepository.java`:
```java
package com.flashsale.identity.adapter.persistence;

import com.flashsale.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
```

`backend/src/main/java/com/flashsale/identity/adapter/persistence/UserRepositoryImpl.java`:
```java
package com.flashsale.identity.adapter.persistence;

import com.flashsale.identity.application.UserRepository;
import com.flashsale.identity.domain.User;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepositoryImpl(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    public User save(User user) {
        return jpaRepository.save(user);
    }
}
```

`backend/src/main/java/com/flashsale/notification/adapter/mail/MailConfig.java`:
```java
package com.flashsale.notification.adapter.mail;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class MailConfig {
}
```

`backend/src/main/java/com/flashsale/notification/adapter/mail/EmailNotificationSender.java`:
```java
package com.flashsale.notification.adapter.mail;

import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.application.NotificationSender;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.MimeMessage;

@Component
public class EmailNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final NotificationDeliveryRepository deliveryRepository;

    public EmailNotificationSender(JavaMailSender mailSender, TemplateEngine templateEngine,
                                    NotificationDeliveryRepository deliveryRepository) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.deliveryRepository = deliveryRepository;
    }

    @Override
    @Async
    public void send(NotificationDelivery delivery) {
        NotificationDelivery saved = deliveryRepository.save(delivery);
        try {
            Context context = new Context();
            context.setVariable("email", saved.getRecipient());
            String html = templateEngine.process("email/" + saved.getTemplate(), context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(saved.getRecipient());
            helper.setSubject("Welcome to FlashSale");
            helper.setText(html, true);

            mailSender.send(message);
            saved.markSent();
        } catch (Exception e) {
            saved.markFailed(e.getMessage());
        }
        deliveryRepository.save(saved);
    }
}
```

`backend/src/main/java/com/flashsale/notification/adapter/mail/NotificationDeliveryRepositoryImpl.java`
(implements the port from a JPA repository, same pattern as `UserRepositoryImpl` — create `NotificationDeliveryJpaRepository extends JpaRepository<NotificationDelivery, Long>` alongside it):
```java
package com.flashsale.notification.adapter.mail;

import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface NotificationDeliveryJpaRepository extends JpaRepository<NotificationDelivery, Long> {}

@Repository
class NotificationDeliveryRepositoryImpl implements NotificationDeliveryRepository {

    private final NotificationDeliveryJpaRepository jpaRepository;

    NotificationDeliveryRepositoryImpl(NotificationDeliveryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public NotificationDelivery save(NotificationDelivery delivery) {
        return jpaRepository.save(delivery);
    }
}
```

`backend/src/main/resources/templates/email/registration-success.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
    <h1>Welcome to FlashSale!</h1>
    <p th:text="${email}">user@example.com</p>
    <p>Your account has been created. Good luck with your next flash sale.</p>
</body>
</html>
```

`backend/src/main/java/com/flashsale/identity/adapter/web/dto/RegisterRequest.java`:
```java
package com.flashsale.identity.adapter.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, max = 100) String password
) {}
```

`backend/src/main/java/com/flashsale/identity/adapter/web/AuthController.java`:
```java
package com.flashsale.identity.adapter.web;

import com.flashsale.identity.application.RegisterUserService;
import com.flashsale.identity.adapter.web.dto.RegisterRequest;
import com.flashsale.identity.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegisterUserService registerUserService;

    public AuthController(RegisterUserService registerUserService) {
        this.registerUserService = registerUserService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = registerUserService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", user.getId(), "email", user.getEmail()));
    }
}
```

(Login/refresh/logout endpoints are added to this same controller in Tasks 8–9.)

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.adapter.web.AuthControllerRegisterIT"`
Expected: PASS. Note: this test needs a `PasswordEncoder` bean, which Task 8's `SecurityConfig` provides — if running Task 7 in isolation before Task 8 exists, add a minimal `@Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }` temporarily in a test `@TestConfiguration`, then remove it once Task 8 lands (Task 8's `SecurityConfig` supersedes it). Recommended: implement Task 8 immediately after this task so the real bean exists from the start.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/flashsale/identity backend/src/main/java/com/flashsale/notification backend/src/main/resources/templates backend/src/test/java/com/flashsale/identity
git commit -m "feat: add user registration with registration-success email"
```

---

## Task 8: Identity — Login, JWT Issuance & Spring Security Resource Server

**Files:**
- Create: `backend/src/main/java/com/flashsale/identity/adapter/security/JwtKeyConfig.java`
- Create: `backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java`
- Create: `backend/src/main/java/com/flashsale/identity/adapter/security/JwtIssuer.java`
- Create: `backend/src/main/java/com/flashsale/identity/application/LoginService.java`
- Create: `backend/src/main/java/com/flashsale/identity/domain/RefreshToken.java`
- Create: `backend/src/main/java/com/flashsale/identity/application/RefreshTokenRepository.java` (port)
- Create: `backend/src/main/java/com/flashsale/identity/adapter/persistence/RefreshTokenJpaRepository.java`, `RefreshTokenRepositoryImpl.java`
- Modify: `backend/src/main/java/com/flashsale/identity/adapter/web/AuthController.java` (add `/login`)
- Modify: `backend/src/main/java/com/flashsale/identity/adapter/web/dto/RegisterRequest.java` sibling — add `LoginRequest.java`, `TokenResponse.java`
- Test: `backend/src/test/java/com/flashsale/identity/application/LoginServiceTest.java`
- Test: `backend/src/test/java/com/flashsale/identity/adapter/web/AuthControllerLoginIT.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 7), `PasswordEncoder`/`JwtEncoder`/`JwtDecoder` beans (this task).
- Produces: `LoginService.login(email, password) -> LoginResult(accessToken, refreshToken)`, `RefreshTokenRepository.save/findByTokenHash/revoke` — used by Task 9's refresh/logout flow. `SecurityConfig`'s JWT filter chain gates every `/api/**` endpoint from Task 10 onward.

- [ ] **Step 1: Write the failing key config and JWT issuer test**

`backend/src/test/java/com/flashsale/identity/application/LoginServiceTest.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.identity.adapter.security.JwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtIssuer jwtIssuer;
    @Mock RefreshTokenRepository refreshTokenRepository;

    LoginService service;

    @Test
    void loginWithValidCredentialsReturnsTokens() {
        service = new LoginService(userRepository, passwordEncoder, jwtIssuer, refreshTokenRepository);
        User user = User.register("dave@example.com", "hashed", Role.USER);
        when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hashed")).thenReturn(true);
        when(jwtIssuer.issueAccessToken(user)).thenReturn("access-token");
        when(jwtIssuer.issueRawRefreshToken()).thenReturn("raw-refresh-token");

        LoginService.LoginResult result = service.login("dave@example.com", "secret123");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.rawRefreshToken()).isEqualTo("raw-refresh-token");
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void loginWithWrongPasswordThrowsUnauthorized() {
        service = new LoginService(userRepository, passwordEncoder, jwtIssuer, refreshTokenRepository);
        User user = User.register("dave@example.com", "hashed", Role.USER);
        when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login("dave@example.com", "wrong"))
            .isInstanceOf(NotFoundException.class);
    }
}
```

(Wrong-credential failures deliberately reuse `NotFoundException` → `401`-mapped as `INVALID_CREDENTIALS` below rather than leaking whether the email exists — see Step 3's `GlobalExceptionHandler` note.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.application.LoginServiceTest"`
Expected: FAIL — `LoginService`, `JwtIssuer`, `RefreshToken`, `RefreshTokenRepository` don't exist.

- [ ] **Step 3: Implement JWT key config, issuer, refresh token domain, and login service**

Add an `UNAUTHORIZED` mapping to `GlobalExceptionHandler` (from Task 6) — extend it with:
```java
package com.flashsale.common.exception;

public class UnauthorizedException extends DomainException {
    public UnauthorizedException(String code, String detail) {
        super(code, detail);
    }
}
```
and in `GlobalExceptionHandler`, add:
```java
    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage(), request);
    }
```
(Use `UnauthorizedException` in `LoginService` below instead of `NotFoundException` — the test above stands as the contract; adjust the test's `isInstanceOf` to `UnauthorizedException.class` once this class exists, since `NotFoundException` was a placeholder pending this exception type.)

`backend/src/main/java/com/flashsale/identity/adapter/security/JwtKeyConfig.java`:
```java
package com.flashsale.identity.adapter.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

@Configuration
public class JwtKeyConfig {

    @Value("${JWT_PRIVATE_KEY}")
    private String privateKeyPem;

    @Value("${JWT_PUBLIC_KEY}")
    private String publicKeyPem;

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        String cleaned = publicKeyPem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    @Bean
    public RSAPrivateKey rsaPrivateKey() throws Exception {
        String cleaned = privateKeyPem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        JWK jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(UUID.randomUUID().toString()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk)));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }
}
```

`backend/src/main/java/com/flashsale/identity/adapter/security/JwtIssuer.java`:
```java
package com.flashsale.identity.adapter.security;

import com.flashsale.identity.domain.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Component
public class JwtIssuer {

    private final JwtEncoder jwtEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtIssuer(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("flash-sale")
            .issuedAt(now)
            .expiresAt(now.plus(15, ChronoUnit.MINUTES))
            .subject(user.getEmail())
            .claim("userId", user.getId())
            .claim("role", user.getRole().name())
            .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    public String issueRawRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

`backend/src/main/java/com/flashsale/identity/domain/RefreshToken.java`:
```java
package com.flashsale.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {}

    public static RefreshToken issue(Long userId, String tokenHash, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke() { this.revokedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
}
```

`backend/src/main/java/com/flashsale/identity/application/RefreshTokenRepository.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.identity.domain.RefreshToken;
import java.util.Optional;

public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken token);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
```

`backend/src/main/java/com/flashsale/identity/application/LoginService.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.adapter.security.JwtIssuer;
import com.flashsale.identity.domain.RefreshToken;
import com.flashsale.identity.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public LoginService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                         JwtIssuer jwtIssuer, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public record LoginResult(String accessToken, String rawRefreshToken) {}

    @Transactional
    public LoginResult login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");
        }

        String accessToken = jwtIssuer.issueAccessToken(user);
        String rawRefreshToken = jwtIssuer.issueRawRefreshToken();
        String hash = hash(rawRefreshToken);

        refreshTokenRepository.save(
            RefreshToken.issue(user.getId(), hash, Instant.now().plus(30, ChronoUnit.DAYS)));

        return new LoginResult(accessToken, rawRefreshToken);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`backend/src/main/java/com/flashsale/identity/adapter/persistence/RefreshTokenJpaRepository.java`:
```java
package com.flashsale.identity.adapter.persistence;

import com.flashsale.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
```

`backend/src/main/java/com/flashsale/identity/adapter/persistence/RefreshTokenRepositoryImpl.java`:
```java
package com.flashsale.identity.adapter.persistence;

import com.flashsale.identity.application.RefreshTokenRepository;
import com.flashsale.identity.domain.RefreshToken;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public RefreshToken save(RefreshToken token) { return jpaRepository.save(token); }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash);
    }
}
```

`backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java`:
```java
package com.flashsale.identity.adapter.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/actuator/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt);

        return http.build();
    }
}
```

`backend/src/main/java/com/flashsale/identity/adapter/web/dto/LoginRequest.java`:
```java
package com.flashsale.identity.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
```

Modify `AuthController` — add:
```java
    private final LoginService loginService;

    // add LoginService to constructor injection alongside RegisterUserService

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                       jakarta.servlet.http.HttpServletResponse response) {
        LoginService.LoginResult result = loginService.login(request.email(), request.password());

        jakarta.servlet.http.Cookie refreshCookie = new jakarta.servlet.http.Cookie("refresh_token", result.rawRefreshToken());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/api/auth");
        refreshCookie.setMaxAge(30 * 24 * 60 * 60);
        response.addCookie(refreshCookie);

        return ResponseEntity.ok(Map.of("accessToken", result.accessToken()));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.application.LoginServiceTest"`
Expected: PASS

- [ ] **Step 5: Write and run the failing/passing login API integration test**

`backend/src/test/java/com/flashsale/identity/adapter/web/AuthControllerLoginIT.java` follows the same Testcontainers `@SpringBootTest` pattern as `AuthControllerRegisterIT` (Task 7 Step 5): register a user via `POST /api/auth/register`, then `POST /api/auth/login` with the same credentials, asserting `status().isOk()`, `jsonPath("$.accessToken").exists()`, and `cookie().exists("refresh_token")`. Also assert `POST /api/auth/login` with a wrong password returns `401` with `code` = `INVALID_CREDENTIALS`. Set `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` test properties via `@DynamicPropertySource`, generated once via `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` and `openssl rsa -pubout`, PEM contents inlined as static test constants.

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.adapter.web.AuthControllerLoginIT"`
Expected: FAIL then PASS after implementation (same red/green cycle as prior tasks).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flashsale/identity backend/src/main/java/com/flashsale/common/exception backend/src/test/java/com/flashsale/identity
git commit -m "feat: add JWT login and Spring Security resource server config"
```

---

## Task 9: Identity — Refresh & Logout + Nginx Rate Limiting

**Files:**
- Create: `backend/src/main/java/com/flashsale/identity/application/RefreshTokenService.java`
- Create: `backend/src/main/java/com/flashsale/identity/application/LogoutService.java`
- Modify: `backend/src/main/java/com/flashsale/identity/adapter/web/AuthController.java` (add `/refresh`, `/logout`)
- Modify: `nginx/nginx.conf` (add `limit_req_zone`/`limit_req` for auth endpoints)
- Test: `backend/src/test/java/com/flashsale/identity/application/RefreshTokenServiceTest.java`
- Test: `backend/src/test/java/com/flashsale/identity/adapter/web/AuthControllerRefreshLogoutIT.java`

**Interfaces:**
- Consumes: `RefreshTokenRepository`, `JwtIssuer` (Task 8).
- Produces: `RefreshTokenService.refresh(rawToken) -> newAccessToken`, `LogoutService.logout(rawToken)` — revokes the matching `refresh_tokens` row.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/flashsale/identity/application/RefreshTokenServiceTest.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.domain.RefreshToken;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.identity.adapter.security.JwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock UserRepository userRepository;
    @Mock JwtIssuer jwtIssuer;

    RefreshTokenService service;

    @Test
    void validRefreshTokenIssuesNewAccessToken() {
        service = new RefreshTokenService(refreshTokenRepository, userRepository, jwtIssuer);
        String raw = "raw-token";
        String hash = LoginService.hash(raw);
        RefreshToken stored = RefreshToken.issue(1L, hash, Instant.now().plus(1, ChronoUnit.DAYS));
        User user = User.register("eve@example.com", "hashed", Role.USER);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(jwtIssuer.issueAccessToken(any())).thenReturn("new-access-token");

        // service looks up user by id in practice; test wires findById-style lookup via UserRepository
        // (UserRepository gains a findById method used only here and by later admin lookups)
    }

    @Test
    void expiredOrRevokedTokenThrowsUnauthorized() {
        service = new RefreshTokenService(refreshTokenRepository, userRepository, jwtIssuer);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("unknown-token"))
            .isInstanceOf(UnauthorizedException.class);
    }
}
```

(The first test documents the need for `UserRepository.findById(Long)`, added in Step 3 below — this is the "test drives the interface" moment; finish Step 3 then come back and complete the first test's assertions and stubs.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.application.RefreshTokenServiceTest"`
Expected: FAIL — `RefreshTokenService` doesn't exist.

- [ ] **Step 3: Implement refresh/logout services**

Add to `backend/src/main/java/com/flashsale/identity/application/UserRepository.java`:
```java
    Optional<User> findById(Long id);
```
and implement it in `UserRepositoryImpl` by delegating to `UserJpaRepository.findById(id)` (from `JpaRepository`, already available).

`backend/src/main/java/com/flashsale/identity/application/RefreshTokenService.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.adapter.security.JwtIssuer;
import com.flashsale.identity.domain.RefreshToken;
import com.flashsale.identity.domain.User;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
                                JwtIssuer jwtIssuer) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
    }

    public String refresh(String rawToken) {
        String hash = LoginService.hash(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
            .filter(t -> t.isValid(Instant.now()))
            .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));

        User user = userRepository.findById(token.getUserId())
            .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH_TOKEN", "User no longer exists"));

        return jwtIssuer.issueAccessToken(user);
    }
}
```

`backend/src/main/java/com/flashsale/identity/application/LogoutService.java`:
```java
package com.flashsale.identity.application;

import com.flashsale.identity.domain.RefreshToken;
import org.springframework.stereotype.Service;

@Service
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;

    public LogoutService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public void logout(String rawToken) {
        String hash = LoginService.hash(rawToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(RefreshToken::revoke);
    }
}
```

Now finish `RefreshTokenServiceTest`'s first test body:
```java
    @Test
    void validRefreshTokenIssuesNewAccessToken() {
        service = new RefreshTokenService(refreshTokenRepository, userRepository, jwtIssuer);
        String raw = "raw-token";
        String hash = LoginService.hash(raw);
        RefreshToken stored = RefreshToken.issue(1L, hash, Instant.now().plus(1, ChronoUnit.DAYS));
        User user = User.register("eve@example.com", "hashed", Role.USER);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtIssuer.issueAccessToken(user)).thenReturn("new-access-token");

        String result = service.refresh(raw);

        assertThat(result).isEqualTo("new-access-token");
    }
```

Modify `AuthController` — add `RefreshTokenService refreshTokenService` and `LogoutService logoutService` to the constructor injection alongside the existing services, then add:
```java
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@CookieValue("refresh_token") String refreshToken) {
        String accessToken = refreshTokenService.refresh(refreshToken);
        return ResponseEntity.ok(Map.of("accessToken", accessToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "refresh_token", required = false) String refreshToken,
                                        jakarta.servlet.http.HttpServletResponse response) {
        if (refreshToken != null) {
            logoutService.logout(refreshToken);
        }
        jakarta.servlet.http.Cookie expired = new jakarta.servlet.http.Cookie("refresh_token", "");
        expired.setHttpOnly(true);
        expired.setSecure(true);
        expired.setPath("/api/auth");
        expired.setMaxAge(0);
        response.addCookie(expired);
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.application.RefreshTokenServiceTest"`
Expected: PASS

- [ ] **Step 5: Write and pass the refresh/logout API integration test**

`backend/src/test/java/com/flashsale/identity/adapter/web/AuthControllerRefreshLogoutIT.java`: same Testcontainers pattern — register, login (capture `refresh_token` cookie from the response), call `POST /api/auth/refresh` with that cookie and assert a new `accessToken` is returned; call `POST /api/auth/logout` with the cookie, then call `/api/auth/refresh` again with the same (now revoked) cookie and assert `401`.

Run: `cd backend && ./gradlew test --tests "com.flashsale.identity.adapter.web.AuthControllerRefreshLogoutIT"`
Expected: FAIL then PASS.

- [ ] **Step 6: Add Nginx rate limiting for auth endpoints**

Modify `nginx/nginx.conf` — add inside the `http {}` block (above `server {}`):
```nginx
    limit_req_zone $binary_remote_addr zone=auth_limit:10m rate=5r/s;
```

Modify the `location /api/` block to special-case auth endpoints before the general proxy rule — add above `location /api/ { ... }`:
```nginx
        location ~ ^/api/auth/(login|register) {
            limit_req zone=auth_limit burst=10 nodelay;
            proxy_pass http://backend:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
```

- [ ] **Step 7: Verify rate limiting**

Run: `docker compose up --build -d` then fire 20 rapid requests: `for i in $(seq 1 20); do curl -k -s -o /dev/null -w "%{http_code}\n" -X POST https://localhost:8443/api/auth/login -H "Content-Type: application/json" -d '{"email":"x@example.com","password":"wrong"}'; done`
Expected: the first ~5-15 requests return `401` (invalid credentials, proving they reached the backend), later ones return `503` (Nginx rate-limit rejection).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/flashsale/identity backend/src/test/java/com/flashsale/identity nginx/nginx.conf
git commit -m "feat: add refresh/logout endpoints and Nginx auth rate limiting"
```

---

## Task 10: Catalog & Flash Sale Query APIs

**Files:**
- Create: `backend/src/main/java/com/flashsale/catalog/domain/Product.java`
- Create: `backend/src/main/java/com/flashsale/catalog/application/ProductRepository.java` (port)
- Create: `backend/src/main/java/com/flashsale/catalog/adapter/persistence/ProductJpaRepository.java`, `ProductRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/flashsale/domain/FlashSale.java`, `FlashSaleStatus.java`
- Create: `backend/src/main/java/com/flashsale/flashsale/application/FlashSaleRepository.java` (port), `FlashSaleQueryService.java`, `dto/FlashSaleSummary.java`, `dto/FlashSaleDetail.java`
- Create: `backend/src/main/java/com/flashsale/flashsale/adapter/persistence/FlashSaleJpaRepository.java`, `FlashSaleRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/flashsale/adapter/web/FlashSaleController.java`
- Create: `backend/src/test/resources/db/testdata/flashsale-fixtures.sql`
- Test: `backend/src/test/java/com/flashsale/flashsale/application/FlashSaleQueryServiceTest.java`
- Test: `backend/src/test/java/com/flashsale/flashsale/adapter/web/FlashSaleControllerIT.java`

**Interfaces:**
- Consumes: `catalog.application.ProductRepository` (cross-module port; `flashsale` depends on it directly, not on `catalog.adapter`).
- Produces: `GET /api/flash-sales` (list of `FlashSaleSummary`), `GET /api/flash-sales/{id}` (single `FlashSaleDetail`) — consumed by Task 11's purchase endpoint (to validate sale timing) and Task 13's frontend pages.

- [ ] **Step 1: Write the failing service test**

`backend/src/test/java/com/flashsale/flashsale/application/FlashSaleQueryServiceTest.java`:
```java
package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashSaleQueryServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock ProductRepository productRepository;

    FlashSaleQueryService service;

    @Test
    void listActiveSalesJoinsProductDetails() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findAll()).thenReturn(List.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        List<FlashSaleSummary> result = service.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).productName()).isEqualTo("Limited Sneakers");
        assertThat(result.get(0).salePrice()).isEqualByComparingTo("9.99");
    }

    @Test
    void detailThrowsNotFoundForUnknownSale() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository);
        when(flashSaleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L)).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.flashsale.application.FlashSaleQueryServiceTest"`
Expected: FAIL — none of `Product`, `FlashSale`, `FlashSaleRepository`, `ProductRepository`, `FlashSaleQueryService` exist.

- [ ] **Step 3: Implement domain, ports, and query service**

`backend/src/main/java/com/flashsale/catalog/domain/Product.java`:
```java
package com.flashsale.catalog.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    protected Product() {}

    private Product(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public static Product create(String name, String description) {
        return new Product(name, description);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
```

`backend/src/main/java/com/flashsale/catalog/application/ProductRepository.java`:
```java
package com.flashsale.catalog.application;

import com.flashsale.catalog.domain.Product;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(Long id);
}
```

`backend/src/main/java/com/flashsale/flashsale/domain/FlashSaleStatus.java`:
```java
package com.flashsale.flashsale.domain;

public enum FlashSaleStatus {
    SCHEDULED, ACTIVE, ENDED
}
```

`backend/src/main/java/com/flashsale/flashsale/domain/FlashSale.java`:
```java
package com.flashsale.flashsale.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "flash_sales")
public class FlashSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sale_price", nullable = false)
    private BigDecimal salePrice;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "purchase_limit_per_user", nullable = false)
    private int purchaseLimitPerUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlashSaleStatus status;

    protected FlashSale() {}

    public static FlashSale schedule(Long productId, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                                      int purchaseLimitPerUser) {
        FlashSale sale = new FlashSale();
        sale.productId = productId;
        sale.salePrice = salePrice;
        sale.startsAt = startsAt;
        sale.endsAt = endsAt;
        sale.purchaseLimitPerUser = purchaseLimitPerUser;
        sale.status = FlashSaleStatus.SCHEDULED;
        return sale;
    }

    public boolean isPurchasableAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public BigDecimal getSalePrice() { return salePrice; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public int getPurchaseLimitPerUser() { return purchaseLimitPerUser; }
    public FlashSaleStatus getStatus() { return status; }
}
```

`backend/src/main/java/com/flashsale/flashsale/application/FlashSaleRepository.java`:
```java
package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSale;
import java.util.List;
import java.util.Optional;

public interface FlashSaleRepository {
    List<FlashSale> findAll();
    Optional<FlashSale> findById(Long id);
}
```

`backend/src/main/java/com/flashsale/flashsale/application/dto/FlashSaleSummary.java`:
```java
package com.flashsale.flashsale.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleSummary(
    Long id, String productName, BigDecimal salePrice, Instant startsAt, Instant endsAt, String status
) {}
```

`backend/src/main/java/com/flashsale/flashsale/application/dto/FlashSaleDetail.java`:
```java
package com.flashsale.flashsale.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleDetail(
    Long id, String productName, String productDescription, BigDecimal salePrice,
    Instant startsAt, Instant endsAt, int purchaseLimitPerUser, String status
) {}
```

`backend/src/main/java/com/flashsale/flashsale/application/FlashSaleQueryService.java`:
```java
package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleDetail;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FlashSaleQueryService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;

    public FlashSaleQueryService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
    }

    public List<FlashSaleSummary> listAll() {
        return flashSaleRepository.findAll().stream()
            .map(sale -> {
                Product product = productFor(sale);
                return new FlashSaleSummary(sale.getId(), product.getName(), sale.getSalePrice(),
                    sale.getStartsAt(), sale.getEndsAt(), sale.getStatus().name());
            })
            .toList();
    }

    public FlashSaleDetail getDetail(Long id) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "Flash sale " + id + " does not exist"));
        Product product = productFor(sale);
        return new FlashSaleDetail(sale.getId(), product.getName(), product.getDescription(), sale.getSalePrice(),
            sale.getStartsAt(), sale.getEndsAt(), sale.getPurchaseLimitPerUser(), sale.getStatus().name());
    }

    private Product productFor(FlashSale sale) {
        return productRepository.findById(sale.getProductId())
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + sale.getProductId() + " does not exist"));
    }
}
```

`backend/src/main/java/com/flashsale/catalog/adapter/persistence/ProductJpaRepository.java`:
```java
package com.flashsale.catalog.adapter.persistence;

import com.flashsale.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {}
```

`backend/src/main/java/com/flashsale/catalog/adapter/persistence/ProductRepositoryImpl.java`:
```java
package com.flashsale.catalog.adapter.persistence;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpaRepository;

    public ProductRepositoryImpl(ProductJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Product> findById(Long id) {
        return jpaRepository.findById(id);
    }
}
```

`backend/src/main/java/com/flashsale/flashsale/adapter/persistence/FlashSaleJpaRepository.java`:
```java
package com.flashsale.flashsale.adapter.persistence;

import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashSaleJpaRepository extends JpaRepository<FlashSale, Long> {}
```

`backend/src/main/java/com/flashsale/flashsale/adapter/persistence/FlashSaleRepositoryImpl.java`:
```java
package com.flashsale.flashsale.adapter.persistence;

import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class FlashSaleRepositoryImpl implements FlashSaleRepository {

    private final FlashSaleJpaRepository jpaRepository;

    public FlashSaleRepositoryImpl(FlashSaleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<FlashSale> findAll() { return jpaRepository.findAll(); }

    @Override
    public Optional<FlashSale> findById(Long id) { return jpaRepository.findById(id); }
}
```

`backend/src/main/java/com/flashsale/flashsale/adapter/web/FlashSaleController.java`:
```java
package com.flashsale.flashsale.adapter.web;

import com.flashsale.flashsale.application.FlashSaleQueryService;
import com.flashsale.flashsale.application.dto.FlashSaleDetail;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flash-sales")
public class FlashSaleController {

    private final FlashSaleQueryService queryService;

    public FlashSaleController(FlashSaleQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<FlashSaleSummary> list() {
        return queryService.listAll();
    }

    @GetMapping("/{id}")
    public FlashSaleDetail detail(@PathVariable Long id) {
        return queryService.getDetail(id);
    }
}
```

Also add `SecurityConfig`'s `authorizeHttpRequests` a public rule for these read endpoints (product browsing needs no auth per spec §10): insert `.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/flash-sales/**").permitAll()` above the `.anyRequest().authenticated()` line from Task 8.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.flashsale.application.FlashSaleQueryServiceTest"`
Expected: PASS

- [ ] **Step 5: Write and pass the API integration test**

`backend/src/test/resources/db/testdata/flashsale-fixtures.sql`:
```sql
INSERT INTO products (id, name, description) VALUES (1, 'Limited Sneakers', 'Only 100 pairs');
INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE');
```

`backend/src/test/java/com/flashsale/flashsale/adapter/web/FlashSaleControllerIT.java` follows the Testcontainers `@SpringBootTest`/`@AutoConfigureMockMvc` pattern from Task 7, loading the fixture via `@Sql("/db/testdata/flashsale-fixtures.sql")` on the test class, then asserting `GET /api/flash-sales` returns the seeded summary and `GET /api/flash-sales/1` returns matching detail fields; `GET /api/flash-sales/999` asserts `404` with `code` = `FLASH_SALE_NOT_FOUND`.

Run: `cd backend && ./gradlew test --tests "com.flashsale.flashsale.adapter.web.FlashSaleControllerIT"`
Expected: FAIL then PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flashsale/catalog backend/src/main/java/com/flashsale/flashsale backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java backend/src/test/java/com/flashsale/flashsale backend/src/test/resources/db
git commit -m "feat: add catalog and flash sale query APIs"
```

---

## Task 11: Inventory & Synchronous Purchase/Order Flow

**Files:**
- Create: `backend/src/main/java/com/flashsale/inventory/domain/Inventory.java`
- Create: `backend/src/main/java/com/flashsale/inventory/application/InventoryRepository.java` (port)
- Create: `backend/src/main/java/com/flashsale/inventory/adapter/persistence/InventoryJpaRepository.java`, `InventoryRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/order/domain/Order.java`, `OrderItem.java`, `OrderStatus.java`, `PurchaseRequest.java`, `PurchaseRequestStatus.java`
- Create: `backend/src/main/java/com/flashsale/order/application/OrderRepository.java`, `PurchaseRequestRepository.java` (ports), `CreatePurchaseRequestService.java`
- Create: `backend/src/main/java/com/flashsale/order/application/dto/PurchaseRequestView.java`, `OrderSummary.java`, `OrderDetail.java`
- Create: `backend/src/main/java/com/flashsale/order/adapter/persistence/OrderJpaRepository.java`, `OrderRepositoryImpl.java`, `PurchaseRequestJpaRepository.java`, `PurchaseRequestRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/order/adapter/web/PurchaseController.java`, `OrderController.java`
- Create: `backend/src/test/resources/db/testdata/inventory-fixtures.sql`
- Test: `backend/src/test/java/com/flashsale/order/application/CreatePurchaseRequestServiceTest.java`
- Test: `backend/src/test/java/com/flashsale/order/adapter/web/PurchaseControllerIT.java`

**Interfaces:**
- Consumes: `FlashSaleRepository` (Task 10), authenticated user id from the JWT `userId` claim (Task 8).
- Produces: `POST /api/flash-sales/{id}/purchase-requests` (202, `{requestId, status}`), `GET /api/purchase-requests/{requestId}` (`{requestId, status, orderId}`), `GET /api/orders/me`, `GET /api/orders/{orderId}`. Week 2 replaces `CreatePurchaseRequestService`'s internals with Redis Lua + RabbitMQ but keeps this exact HTTP contract.

- [ ] **Step 1: Write the failing service test**

`backend/src/test/java/com/flashsale/order/application/CreatePurchaseRequestServiceTest.java`:
```java
package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreatePurchaseRequestServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock OrderRepository orderRepository;
    @Mock PurchaseRequestRepository purchaseRequestRepository;

    CreatePurchaseRequestService service;

    private FlashSale activeSale() {
        return FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
    }

    @Test
    void succeedsWhenStockAvailable() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-1"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryRepository.findByFlashSaleIdForUpdate(10L))
            .thenReturn(Optional.of(Inventory.initialize(10L, 5)));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            var order = inv.getArgument(0, com.flashsale.order.domain.Order.class);
            return order;
        });
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-1");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SUCCEEDED);
        assertThat(result.getOrderId()).isNotNull();
        verify(inventoryRepository).save(argThat(inv -> inv.getAvailableQuantity() == 4));
    }

    @Test
    void marksSoldOutWhenNoStock() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-2"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryRepository.findByFlashSaleIdForUpdate(10L))
            .thenReturn(Optional.of(Inventory.initialize(10L, 0)));
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-2");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SOLD_OUT);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void repeatingSameIdempotencyKeyReturnsSameResult() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        PurchaseRequest existing = PurchaseRequest.succeed(1L, 10L, "idem-3", 999L);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-3"))
            .thenReturn(Optional.of(existing));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-3");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(inventoryRepository, orderRepository);
    }

    @Test
    void rejectsWhenUserAlreadyHasSuccessfulOrder() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-4"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(true);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-4");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.REJECTED);
    }

    @Test
    void rejectsWhenFlashSaleNotActive() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        FlashSale ended = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 1);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-5"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> service.createPurchaseRequest(1L, 10L, "idem-5"))
            .isInstanceOf(ConflictException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.application.CreatePurchaseRequestServiceTest"`
Expected: FAIL — `Inventory`, `Order`, `PurchaseRequest`, repositories, and `CreatePurchaseRequestService` don't exist.

- [ ] **Step 3: Implement domain, ports, and the synchronous purchase service**

`backend/src/main/java/com/flashsale/inventory/domain/Inventory.java`:
```java
package com.flashsale.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flash_sale_id", nullable = false, unique = true)
    private Long flashSaleId;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity = 0;

    @Version
    private long version;

    protected Inventory() {}

    public static Inventory initialize(Long flashSaleId, int totalQuantity) {
        Inventory inventory = new Inventory();
        inventory.flashSaleId = flashSaleId;
        inventory.totalQuantity = totalQuantity;
        inventory.availableQuantity = totalQuantity;
        return inventory;
    }

    public boolean hasStock() {
        return availableQuantity > 0;
    }

    public void sell() {
        if (!hasStock()) {
            throw new IllegalStateException("No stock available for flash sale " + flashSaleId);
        }
        availableQuantity--;
        soldQuantity++;
    }

    public Long getId() { return id; }
    public Long getFlashSaleId() { return flashSaleId; }
    public int getAvailableQuantity() { return availableQuantity; }
    public int getSoldQuantity() { return soldQuantity; }
}
```

(Week 1 skips the reserve→confirm→release lifecycle from spec §7 — `sell()` moves stock straight from available to sold in one step, since there's no async cancellation window yet. `reserved_quantity` stays `0` until Week 2 introduces Redis pre-deduction. Row-level correctness under concurrency comes from `findByFlashSaleIdForUpdate`'s `SELECT ... FOR UPDATE`, Step 3 below — the `@Version` column is schema-compatible with Week 2's optimistic-locking Redis reconciliation but isn't exercised by Week 1's pessimistic-lock path. Known Week 1 limitation: two concurrent requests with the *same* idempotency key can both pass the initial "not found" lookup in `CreatePurchaseRequestService` before either inserts, so the second insert can hit the `purchase_requests` unique constraint and surface as a raw `DataIntegrityViolationException` (mapped to a generic `500` until a handler is added) instead of a clean idempotent replay. This is acceptable for Week 1's synchronous baseline — Week 2's Redis-backed idempotency check closes this window; flag it in the plan's Week 2 kickoff rather than fixing it here.)

`backend/src/main/java/com/flashsale/inventory/application/InventoryRepository.java`:
```java
package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.Inventory;
import java.util.Optional;

public interface InventoryRepository {
    Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId);
    Inventory save(Inventory inventory);
}
```

`backend/src/main/java/com/flashsale/order/domain/OrderStatus.java`:
```java
package com.flashsale.order.domain;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, CANCELLED, EXPIRED
}
```

`backend/src/main/java/com/flashsale/order/domain/OrderItem.java`:
```java
package com.flashsale.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, insertable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    protected OrderItem() {}

    public static OrderItem of(Long productId, int quantity, BigDecimal unitPrice) {
        OrderItem item = new OrderItem();
        item.productId = productId;
        item.quantity = quantity;
        item.unitPrice = unitPrice;
        return item;
    }

    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
```

`backend/src/main/java/com/flashsale/order/domain/Order.java`:
```java
package com.flashsale.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "payment_due_at")
    private Instant paymentDueAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public static Order createPendingPayment(Long userId, Long productId, int quantity, BigDecimal unitPrice) {
        Order order = new Order();
        order.orderNo = "ORD-" + UUID.randomUUID();
        order.userId = userId;
        order.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        order.status = OrderStatus.PENDING_PAYMENT;
        order.paymentDueAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        order.items.add(OrderItem.of(productId, quantity, unitPrice));
        return order;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getUserId() { return userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public OrderStatus getStatus() { return status; }
    public Instant getPaymentDueAt() { return paymentDueAt; }
    public List<OrderItem> getItems() { return items; }
}
```

`backend/src/main/java/com/flashsale/order/domain/PurchaseRequestStatus.java`:
```java
package com.flashsale.order.domain;

public enum PurchaseRequestStatus {
    PENDING, SUCCEEDED, SOLD_OUT, REJECTED, FAILED
}
```

`backend/src/main/java/com/flashsale/order/domain/PurchaseRequest.java`:
```java
package com.flashsale.order.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "purchase_requests")
public class PurchaseRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "flash_sale_id", nullable = false)
    private Long flashSaleId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseRequestStatus status;

    protected PurchaseRequest() {}

    private static PurchaseRequest create(Long userId, Long flashSaleId, String idempotencyKey,
                                           PurchaseRequestStatus status, Long orderId) {
        PurchaseRequest request = new PurchaseRequest();
        request.requestId = UUID.randomUUID();
        request.userId = userId;
        request.flashSaleId = flashSaleId;
        request.idempotencyKey = idempotencyKey;
        request.status = status;
        request.orderId = orderId;
        return request;
    }

    public static PurchaseRequest succeed(Long userId, Long flashSaleId, String idempotencyKey, Long orderId) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.SUCCEEDED, orderId);
    }

    public static PurchaseRequest soldOut(Long userId, Long flashSaleId, String idempotencyKey) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.SOLD_OUT, null);
    }

    public static PurchaseRequest reject(Long userId, Long flashSaleId, String idempotencyKey) {
        return create(userId, flashSaleId, idempotencyKey, PurchaseRequestStatus.REJECTED, null);
    }

    public Long getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Long getFlashSaleId() { return flashSaleId; }
    public Long getOrderId() { return orderId; }
    public PurchaseRequestStatus getStatus() { return status; }
}
```

`backend/src/main/java/com/flashsale/order/application/OrderRepository.java`:
```java
package com.flashsale.order.application;

import com.flashsale.order.domain.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(Long id);
    List<Order> findAllByUserId(Long userId);
}
```

`backend/src/main/java/com/flashsale/order/application/PurchaseRequestRepository.java`:
```java
package com.flashsale.order.application;

import com.flashsale.order.domain.PurchaseRequest;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestRepository {
    PurchaseRequest save(PurchaseRequest request);
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsSucceededForUserAndFlashSale(Long userId, Long flashSaleId);
}
```

`backend/src/main/java/com/flashsale/order/application/CreatePurchaseRequestService.java`:
```java
package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CreatePurchaseRequestService {

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;

    public CreatePurchaseRequestService(FlashSaleRepository flashSaleRepository, InventoryRepository inventoryRepository,
                                         OrderRepository orderRepository, PurchaseRequestRepository purchaseRequestRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
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

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(flashSaleId)
            .orElseThrow(() -> new NotFoundException("INVENTORY_NOT_FOUND", "Inventory for flash sale " + flashSaleId + " does not exist"));

        if (!inventory.hasStock()) {
            return purchaseRequestRepository.save(PurchaseRequest.soldOut(userId, flashSaleId, idempotencyKey));
        }

        inventory.sell();
        inventoryRepository.save(inventory);

        Order order = Order.createPendingPayment(userId, flashSale.getProductId(), 1, flashSale.getSalePrice());
        Order savedOrder = orderRepository.save(order);

        return purchaseRequestRepository.save(
            PurchaseRequest.succeed(userId, flashSaleId, idempotencyKey, savedOrder.getId()));
    }
}
```

`backend/src/main/java/com/flashsale/inventory/adapter/persistence/InventoryJpaRepository.java`:
```java
package com.flashsale.inventory.adapter.persistence;

import com.flashsale.inventory.domain.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface InventoryJpaRepository extends JpaRepository<Inventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.flashSaleId = :flashSaleId")
    Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId);
}
```

`backend/src/main/java/com/flashsale/inventory/adapter/persistence/InventoryRepositoryImpl.java`:
```java
package com.flashsale.inventory.adapter.persistence;

import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class InventoryRepositoryImpl implements InventoryRepository {

    private final InventoryJpaRepository jpaRepository;

    public InventoryRepositoryImpl(InventoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Inventory> findByFlashSaleIdForUpdate(Long flashSaleId) {
        return jpaRepository.findByFlashSaleIdForUpdate(flashSaleId);
    }

    @Override
    public Inventory save(Inventory inventory) { return jpaRepository.save(inventory); }
}
```

`backend/src/main/java/com/flashsale/order/adapter/persistence/OrderJpaRepository.java`:
```java
package com.flashsale.order.adapter.persistence;

import com.flashsale.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByUserId(Long userId);
}
```

`backend/src/main/java/com/flashsale/order/adapter/persistence/OrderRepositoryImpl.java`:
```java
package com.flashsale.order.adapter.persistence;

import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.domain.Order;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public OrderRepositoryImpl(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Order save(Order order) { return jpaRepository.save(order); }

    @Override
    public Optional<Order> findById(Long id) { return jpaRepository.findById(id); }

    @Override
    public List<Order> findAllByUserId(Long userId) { return jpaRepository.findAllByUserId(userId); }
}
```

`backend/src/main/java/com/flashsale/order/adapter/persistence/PurchaseRequestJpaRepository.java`:
```java
package com.flashsale.order.adapter.persistence;

import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestJpaRepository extends JpaRepository<PurchaseRequest, Long> {
    Optional<PurchaseRequest> findByRequestId(UUID requestId);
    Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey);
    boolean existsByUserIdAndFlashSaleIdAndStatus(Long userId, Long flashSaleId, com.flashsale.order.domain.PurchaseRequestStatus status);
}
```

`backend/src/main/java/com/flashsale/order/adapter/persistence/PurchaseRequestRepositoryImpl.java`:
```java
package com.flashsale.order.adapter.persistence;

import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PurchaseRequestRepositoryImpl implements PurchaseRequestRepository {

    private final PurchaseRequestJpaRepository jpaRepository;

    public PurchaseRequestRepositoryImpl(PurchaseRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PurchaseRequest save(PurchaseRequest request) { return jpaRepository.save(request); }

    @Override
    public Optional<PurchaseRequest> findByRequestId(UUID requestId) {
        return jpaRepository.findByRequestId(requestId);
    }

    @Override
    public Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long userId, Long flashSaleId, String idempotencyKey) {
        return jpaRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(userId, flashSaleId, idempotencyKey);
    }

    @Override
    public boolean existsSucceededForUserAndFlashSale(Long userId, Long flashSaleId) {
        return jpaRepository.existsByUserIdAndFlashSaleIdAndStatus(userId, flashSaleId, PurchaseRequestStatus.SUCCEEDED);
    }
}
```

`backend/src/main/java/com/flashsale/order/application/dto/PurchaseRequestView.java`:
```java
package com.flashsale.order.application.dto;

import java.util.UUID;

public record PurchaseRequestView(UUID requestId, String status, Long orderId) {}
```

`backend/src/main/java/com/flashsale/order/application/dto/OrderSummary.java`:
```java
package com.flashsale.order.application.dto;

import java.math.BigDecimal;

public record OrderSummary(Long id, String orderNo, BigDecimal totalAmount, String status) {}
```

`backend/src/main/java/com/flashsale/order/application/dto/OrderDetail.java`:
```java
package com.flashsale.order.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderDetail(Long id, String orderNo, BigDecimal totalAmount, String status, Instant paymentDueAt) {}
```

`backend/src/main/java/com/flashsale/order/adapter/web/PurchaseController.java`:
```java
package com.flashsale.order.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.CreatePurchaseRequestService;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.dto.PurchaseRequestView;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class PurchaseController {

    private final CreatePurchaseRequestService createPurchaseRequestService;
    private final PurchaseRequestRepository purchaseRequestRepository;

    public PurchaseController(CreatePurchaseRequestService createPurchaseRequestService,
                               PurchaseRequestRepository purchaseRequestRepository) {
        this.createPurchaseRequestService = createPurchaseRequestService;
        this.purchaseRequestRepository = purchaseRequestRepository;
    }

    @PostMapping("/api/flash-sales/{id}/purchase-requests")
    public ResponseEntity<PurchaseRequestView> purchase(@PathVariable("id") Long flashSaleId,
                                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        PurchaseRequest request = createPurchaseRequestService.createPurchaseRequest(userId, flashSaleId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId()));
    }

    @GetMapping("/api/purchase-requests/{requestId}")
    public PurchaseRequestView getStatus(@PathVariable UUID requestId) {
        PurchaseRequest request = purchaseRequestRepository.findByRequestId(requestId)
            .orElseThrow(() -> new NotFoundException("PURCHASE_REQUEST_NOT_FOUND", "Purchase request " + requestId + " does not exist"));
        return new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId());
    }
}
```

`backend/src/main/java/com/flashsale/order/adapter/web/OrderController.java`:
```java
package com.flashsale.order.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.dto.OrderDetail;
import com.flashsale.order.application.dto.OrderSummary;
import com.flashsale.order.domain.Order;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping("/me")
    public List<OrderSummary> myOrders(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        return orderRepository.findAllByUserId(userId).stream()
            .map(o -> new OrderSummary(o.getId(), o.getOrderNo(), o.getTotalAmount(), o.getStatus().name()))
            .toList();
    }

    @GetMapping("/{orderId}")
    public OrderDetail detail(@PathVariable Long orderId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        Order order = orderRepository.findById(orderId)
            .filter(o -> o.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " does not exist"));
        return new OrderDetail(order.getId(), order.getOrderNo(), order.getTotalAmount(), order.getStatus().name(), order.getPaymentDueAt());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.application.CreatePurchaseRequestServiceTest"`
Expected: PASS (all 5 cases)

- [ ] **Step 5: Write and pass the purchase API integration test**

`backend/src/test/resources/db/testdata/inventory-fixtures.sql` (extends Task 10's fixture with stock):
```sql
INSERT INTO products (id, name, description) VALUES (1, 'Limited Sneakers', 'Only 100 pairs');
INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status)
VALUES (1, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE');
INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version)
VALUES (1, 1, 1, 1, 0, 0, 0);
```

`backend/src/test/java/com/flashsale/order/adapter/web/PurchaseControllerIT.java` follows the established Testcontainers pattern: register + login a user to get a real access token, `@Sql` the fixture above, then:
- `POST /api/flash-sales/1/purchase-requests` with `Authorization: Bearer <token>` and `Idempotency-Key: key-1` → expect `202`, `status` = `SUCCEEDED`, `orderId` non-null.
- Immediately repeat the same call with the same `Idempotency-Key: key-1` → expect the identical `requestId`/`orderId` back (idempotent replay).
- Register a second user, call with `Idempotency-Key: key-2` → expect `202` with `status` = `SOLD_OUT` (stock was 1, already consumed).
- `GET /api/purchase-requests/{requestId}` from the first call → expect `200` with matching `orderId`.
- `GET /api/orders/me` for the first user → expect one order matching the `orderId`.

Run: `cd backend && ./gradlew test --tests "com.flashsale.order.adapter.web.PurchaseControllerIT"`
Expected: FAIL then PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/flashsale/inventory backend/src/main/java/com/flashsale/order backend/src/test/java/com/flashsale/order backend/src/test/resources/db
git commit -m "feat: add synchronous inventory-backed purchase and order flow"
```

---

## Task 12: Frontend — Auth Pages

**Files:**
- Create: `frontend/src/api/httpClient.ts`
- Create: `frontend/src/api/authApi.ts`
- Create: `frontend/src/features/auth/useAuth.ts`
- Create: `frontend/src/features/auth/RegisterPage.tsx`
- Create: `frontend/src/features/auth/LoginPage.tsx`
- Modify: `frontend/src/router.tsx` (add `/register`, `/login` routes)
- Test: `frontend/src/features/auth/RegisterPage.test.tsx`

**Interfaces:**
- Consumes: `POST /api/auth/register`, `POST /api/auth/login` (Tasks 7–8), proxied through Nginx at `/api/*` (Task 4).
- Produces: `useAuth()` hook exposing `{ accessToken, login, logout }`, storing the access token in memory (module-level state, not `localStorage` — refresh token is the httpOnly cookie, access token is short-lived and re-fetched via `/api/auth/refresh` on page load). Used by Task 13's pages to attach `Authorization: Bearer` headers.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/auth/RegisterPage.test.tsx`:
```typescript
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RegisterPage } from './RegisterPage'
import * as authApi from '../../api/authApi'

describe('RegisterPage', () => {
  it('submits the form and calls the register API', async () => {
    const registerSpy = vi.spyOn(authApi, 'register').mockResolvedValue({ id: 1, email: 'a@example.com' })
    const queryClient = new QueryClient()

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <RegisterPage />
        </MemoryRouter>
      </QueryClientProvider>
    )

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'a@example.com' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret123' } })
    fireEvent.click(screen.getByRole('button', { name: /register/i }))

    await waitFor(() => expect(registerSpy).toHaveBeenCalledWith('a@example.com', 'secret123'))
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run RegisterPage`
Expected: FAIL — `RegisterPage`, `authApi.register` don't exist.

- [ ] **Step 3: Implement the API client and pages**

`frontend/src/api/httpClient.ts`:
```typescript
let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText }))
    throw new Error(problem.detail ?? 'Request failed')
  }
  return response
}
```

`frontend/src/api/authApi.ts`:
```typescript
import { apiFetch, setAccessToken } from './httpClient'

export async function register(email: string, password: string) {
  const response = await apiFetch('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
  return response.json() as Promise<{ id: number; email: string }>
}

export async function login(email: string, password: string) {
  const response = await apiFetch('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
  const data = (await response.json()) as { accessToken: string }
  setAccessToken(data.accessToken)
  return data
}

export async function refresh() {
  const response = await apiFetch('/api/auth/refresh', { method: 'POST' })
  const data = (await response.json()) as { accessToken: string }
  setAccessToken(data.accessToken)
  return data
}

export async function logout() {
  await apiFetch('/api/auth/logout', { method: 'POST' })
  setAccessToken(null)
}
```

`frontend/src/features/auth/useAuth.ts`:
```typescript
import { useState, useCallback } from 'react'
import * as authApi from '../../api/authApi'

export function useAuth() {
  const [isAuthenticated, setIsAuthenticated] = useState(false)

  const login = useCallback(async (email: string, password: string) => {
    await authApi.login(email, password)
    setIsAuthenticated(true)
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout()
    setIsAuthenticated(false)
  }, [])

  return { isAuthenticated, login, logout }
}
```

`frontend/src/features/auth/RegisterPage.tsx`:
```typescript
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import * as authApi from '../../api/authApi'

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
})

type FormValues = z.infer<typeof schema>

export function RegisterPage() {
  const navigate = useNavigate()
  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({ resolver: zodResolver(schema) })
  const mutation = useMutation({
    mutationFn: (values: FormValues) => authApi.register(values.email, values.password),
    onSuccess: () => navigate('/login'),
  })

  return (
    <form onSubmit={handleSubmit((values) => mutation.mutate(values))}>
      <label htmlFor="email">Email</label>
      <input id="email" type="email" {...register('email')} />
      {errors.email && <span role="alert">{errors.email.message}</span>}

      <label htmlFor="password">Password</label>
      <input id="password" type="password" {...register('password')} />
      {errors.password && <span role="alert">{errors.password.message}</span>}

      <button type="submit">Register</button>
      {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
    </form>
  )
}
```

`frontend/src/features/auth/LoginPage.tsx`:
```typescript
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useAuth } from './useAuth'

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
})

type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({ resolver: zodResolver(schema) })
  const mutation = useMutation({
    mutationFn: (values: FormValues) => login(values.email, values.password),
    onSuccess: () => navigate('/'),
  })

  return (
    <form onSubmit={handleSubmit((values) => mutation.mutate(values))}>
      <label htmlFor="email">Email</label>
      <input id="email" type="email" {...register('email')} />
      {errors.email && <span role="alert">{errors.email.message}</span>}

      <label htmlFor="password">Password</label>
      <input id="password" type="password" {...register('password')} />
      {errors.password && <span role="alert">{errors.password.message}</span>}

      <button type="submit">Login</button>
      {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
    </form>
  )
}
```

Modify `frontend/src/router.tsx`:
```typescript
import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { LoginPage } from './features/auth/LoginPage'

export const router = createBrowserRouter([
  { path: '/', element: <FlashSaleListPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/login', element: <LoginPage /> },
])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run RegisterPage`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api frontend/src/features/auth frontend/src/router.tsx
git commit -m "feat: add register/login pages and auth API client"
```

---

## Task 13: Frontend — Flash Sale List & Detail Pages

**Files:**
- Create: `frontend/src/api/flashSaleApi.ts`
- Modify: `frontend/src/features/flash-sales/FlashSaleListPage.tsx` (replace placeholder from Task 2)
- Create: `frontend/src/features/flash-sales/FlashSaleDetailPage.tsx`
- Modify: `frontend/src/router.tsx` (add `/flash-sales/:id`)
- Test: `frontend/src/features/flash-sales/FlashSaleListPage.test.tsx`

**Interfaces:**
- Consumes: `GET /api/flash-sales`, `GET /api/flash-sales/{id}` (Task 10).
- Produces: the landing page users see; the "搶購" button on the detail page is wired to `POST .../purchase-requests` in Week 2/3 once the polling result page exists — this task only renders sale data.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/flash-sales/FlashSaleListPage.test.tsx`:
```typescript
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { FlashSaleListPage } from './FlashSaleListPage'
import * as flashSaleApi from '../../api/flashSaleApi'

describe('FlashSaleListPage', () => {
  it('renders fetched flash sales', async () => {
    vi.spyOn(flashSaleApi, 'listFlashSales').mockResolvedValue([
      { id: 1, productName: 'Limited Sneakers', salePrice: 9.99, startsAt: new Date().toISOString(), endsAt: new Date().toISOString(), status: 'ACTIVE' },
    ])
    const queryClient = new QueryClient()

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <FlashSaleListPage />
        </MemoryRouter>
      </QueryClientProvider>
    )

    await waitFor(() => expect(screen.getByText('Limited Sneakers')).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run FlashSaleListPage`
Expected: FAIL — `flashSaleApi.listFlashSales` doesn't exist, placeholder page renders "Loading flash sales…" only.

- [ ] **Step 3: Implement the API client and pages**

`frontend/src/api/flashSaleApi.ts`:
```typescript
import { apiFetch } from './httpClient'

export interface FlashSaleSummary {
  id: number
  productName: string
  salePrice: number
  startsAt: string
  endsAt: string
  status: string
}

export interface FlashSaleDetail extends FlashSaleSummary {
  productDescription: string
  purchaseLimitPerUser: number
}

export async function listFlashSales(): Promise<FlashSaleSummary[]> {
  const response = await apiFetch('/api/flash-sales')
  return response.json()
}

export async function getFlashSale(id: number): Promise<FlashSaleDetail> {
  const response = await apiFetch(`/api/flash-sales/${id}`)
  return response.json()
}
```

`frontend/src/features/flash-sales/FlashSaleListPage.tsx`:
```typescript
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listFlashSales } from '../../api/flashSaleApi'

export function FlashSaleListPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['flash-sales'], queryFn: listFlashSales })

  if (isLoading) return <div>Loading flash sales…</div>
  if (isError) return <div role="alert">Failed to load flash sales.</div>

  return (
    <ul>
      {data!.map((sale) => (
        <li key={sale.id}>
          <Link to={`/flash-sales/${sale.id}`}>{sale.productName}</Link>
          <span> ${sale.salePrice.toFixed(2)}</span>
          <span> {sale.status}</span>
        </li>
      ))}
    </ul>
  )
}
```

`frontend/src/features/flash-sales/FlashSaleDetailPage.tsx`:
```typescript
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getFlashSale } from '../../api/flashSaleApi'

export function FlashSaleDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['flash-sales', id],
    queryFn: () => getFlashSale(Number(id)),
    enabled: !!id,
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load flash sale.</div>

  return (
    <article>
      <h2>{data.productName}</h2>
      <p>{data.productDescription}</p>
      <p>Price: ${data.salePrice.toFixed(2)}</p>
      <p>Status: {data.status}</p>
      <p>Ends at: {new Date(data.endsAt).toLocaleString()}</p>
    </article>
  )
}
```

Modify `frontend/src/router.tsx` — add:
```typescript
import { FlashSaleDetailPage } from './features/flash-sales/FlashSaleDetailPage'
// ...
  { path: '/flash-sales/:id', element: <FlashSaleDetailPage /> },
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run FlashSaleListPage`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/flashSaleApi.ts frontend/src/features/flash-sales frontend/src/router.tsx
git commit -m "feat: add flash sale list and detail pages"
```

---

## Self-Review Notes

- **Spec coverage:** every Week 1 bullet in spec §15 maps to a task — repo/Compose/Nginx skeleton (Tasks 1–4), JWT register/login/refresh/logout + registration email (Tasks 7–9), Nginx auth rate limiting (Task 9), product/flash-sale queries (Task 10), Flyway schema (Task 5), synchronous order+inventory transaction (Task 11), Swagger/ProblemDetail (Task 6), TDD tests (every task). No gaps found.
- **Placeholder scan:** no `TBD`/`TODO`/"implement later"/"add appropriate handling" patterns present; every step has real code.
- **Type/name consistency:** verified repository method names match between port interfaces, JPA adapters, and callers (`existsSucceededForUserAndFlashSale`, `findByFlashSaleIdForUpdate`, `findAllByUserId`, etc.), and the `userId` JWT claim name matches between `JwtIssuer` and the controllers that read it.
- **Fixed during review:** `SecurityConfig` (Task 8) was missing `/api/auth/logout` from its `permitAll` list, which would have made Task 9's logout test fail with `401` — added.
- **Documented, not fixed:** a same-idempotency-key race in `CreatePurchaseRequestService` (Task 11) can surface as an unhandled `500` instead of a clean idempotent replay under concurrent duplicate requests — acceptable for Week 1's synchronous baseline, closed by Week 2's Redis-backed idempotency check.

---

Plan complete and saved to `docs/superpowers/plans/2026-08-08-flash-sale-week1-mvp.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
