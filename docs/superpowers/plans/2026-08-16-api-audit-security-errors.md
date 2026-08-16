# API Audit Security Errors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓 401／403 與既有成功／業務錯誤請求一樣，各自恰好寫入一筆完整 API 稽核紀錄。

**Architecture:** 把 ApiAuditFilter 從獨立 Servlet filter 移入 Spring Security chain，位置在 SecurityContextHolderFilter 後，使它包住 Bearer authentication、authorization 與 MVC。以停用的 FilterRegistrationBean 阻止 Boot 重複註冊；security handlers 用既有 request attribute 傳遞 error code。

**Tech Stack:** Java 21、Spring Boot 3.3、Spring Security 6、JUnit 5、MockMvc、Testcontainers、Awaitility

## Global Constraints

- 401：UNAUTHENTICATED、user_id = null。
- 403：ACCESS_DENIED，有效 JWT 保留 userId。
- 每個 request 恰好一筆 audit log；不得記錄 Authorization、JWT、密碼或 body。
- 不改既有 Problem Details、授權規則或稽核欄位。
- commit、push、merge 前必須取得使用者明確同意。

---

### Task 1: 以整合測試重現漏記

**Files:**
- Modify: backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java

**Interfaces:**
- Consumes: GET /api/orders/me、GET /api/admin/dashboard/summary
- Produces: 401／403 各一筆 audit row 的回歸測試

- [ ] **Step 1: 加入只查本次 request 的 helpers**

    private long latestAuditId() {
        Long id = jdbcTemplate.queryForObject(
            "select coalesce(max(id), 0) from api_audit_logs", Long.class);
        return id == null ? 0L : id;
    }

    private List<Map<String, Object>> auditRowsAfter(long id, String path, int status) {
        return jdbcTemplate.queryForList(
            "select * from api_audit_logs where id > ? and path_template = ? and status = ? order by id",
            id, path, status);
    }

- [ ] **Step 2: 加入 401 failing test**

    @Test
    void unauthenticatedRequestIsAuditedExactlyOnce() throws Exception {
        long beforeId = latestAuditId();
        mockMvc.perform(get("/api/orders/me")).andExpect(status().isUnauthorized());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditRowsAfter(beforeId, "/api/orders/me", 401);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("user_id")).isNull();
            assertThat(rows.getFirst().get("error_code")).isEqualTo("UNAUTHENTICATED");
        });
    }

- [ ] **Step 3: 加入 403 failing test**

沿用既有 register/login 流程取得 USER token，另查 users.id；呼叫 admin summary 後斷言：

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("user_id")).isEqualTo(userId);
    assertThat(rows.getFirst().get("error_code")).isEqualTo("ACCESS_DENIED");

- [ ] **Step 4: 執行確認紅燈**

Run:

    Set-Location backend
    .\gradlew.bat test --tests com.flashsale.common.web.ApiAuditFilterIT

Expected: 新測試 Awaitility timeout，證明 401／403 未寫入。

### Task 2: 移入 Security chain 並防止重複註冊

**Files:**
- Modify: backend/src/main/java/com/flashsale/common/web/ApiAuditFilter.java
- Modify: backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java
- Create: backend/src/main/java/com/flashsale/common/web/ApiAuditFilterRegistrationConfig.java
- Test: backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java

**Interfaces:**
- Consumes: Spring bean ApiAuditFilter
- Produces: Security-chain filter、disabled Servlet registration

- [ ] **Step 1: 移除 ApiAuditFilter 的 @Order 與 SecurityProperties import**

更新 Javadoc，filter 本體的 trace、path、duration、client IP、userId 邏輯不變。

- [ ] **Step 2: 建立 disabled registration**

    @Configuration
    public class ApiAuditFilterRegistrationConfig {
        @Bean
        FilterRegistrationBean<ApiAuditFilter> apiAuditFilterRegistration(ApiAuditFilter filter) {
            FilterRegistrationBean<ApiAuditFilter> registration =
                new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }

- [ ] **Step 3: SecurityConfig constructor 注入 ApiAuditFilter**

在 HttpSecurity chain 加入：

    .addFilterAfter(apiAuditFilter, SecurityContextHolderFilter.class)

不得移動或改寫既有 request matchers。

- [ ] **Step 4: 執行 ApiAuditFilterIT**

Expected: 200 行為、403 userId 與 exactly-once 通過；error_code 尚未設定的 assertions 仍紅燈。

### Task 3: Security handlers 設定 error code

**Files:**
- Modify: backend/src/main/java/com/flashsale/identity/adapter/security/ProblemDetailAuthenticationEntryPoint.java
- Modify: backend/src/main/java/com/flashsale/identity/adapter/security/ProblemDetailAccessDeniedHandler.java
- Test: backend/src/test/java/com/flashsale/identity/adapter/security/SecurityErrorResponseIT.java

**Interfaces:**
- Produces: request attribute apiAuditErrorCode

- [ ] **Step 1: 補強既有 response contract assertions**

401／403 都斷言 status、application/problem+json、code 與中文 detail，確保稽核改動不變更 API。

- [ ] **Step 2: handlers 寫入 attribute**

AuthenticationEntryPoint 在 response 前：

    request.setAttribute("apiAuditErrorCode", "UNAUTHENTICATED");

AccessDeniedHandler 在 response 前：

    request.setAttribute("apiAuditErrorCode", "ACCESS_DENIED");

- [ ] **Step 3: 跑 scoped tests**

    Set-Location backend
    .\gradlew.bat test --tests com.flashsale.common.web.ApiAuditFilterIT --tests com.flashsale.identity.adapter.security.SecurityErrorResponseIT --tests com.flashsale.common.config.SecurityFilterChainSingletonIT

Expected: PASS。

### Task 4: 完整驗證與交付

**Files:**
- Modify: docs/superpowers/plans/2026-08-16-api-audit-security-errors.md（勾選完成步驟）

- [ ] **Step 1: 完整後端測試**

    Set-Location backend
    .\gradlew.bat test

Expected: 全綠，沒有 duplicate filter 或 context startup error。

- [ ] **Step 2: diff 檢查**

    git diff --check
    git status --short

- [ ] **Step 3: 使用者同意後才 commit**

    git add backend/src/main/java/com/flashsale/common/web/ApiAuditFilter.java backend/src/main/java/com/flashsale/common/web/ApiAuditFilterRegistrationConfig.java backend/src/main/java/com/flashsale/identity/adapter/security/SecurityConfig.java backend/src/main/java/com/flashsale/identity/adapter/security/ProblemDetailAuthenticationEntryPoint.java backend/src/main/java/com/flashsale/identity/adapter/security/ProblemDetailAccessDeniedHandler.java backend/src/test/java/com/flashsale/common/web/ApiAuditFilterIT.java backend/src/test/java/com/flashsale/identity/adapter/security/SecurityErrorResponseIT.java docs/superpowers/specs/2026-08-16-api-audit-security-errors-design.md docs/superpowers/plans/2026-08-16-api-audit-security-errors.md
    git commit -m "fix: audit authentication and authorization failures"

