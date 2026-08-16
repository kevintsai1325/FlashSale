# Frontend Auth Errors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解析 typed Problem Details，401 時 single-flight refresh 並重送一次，失敗才清除狀態與返回登入頁；403 保持登入。

**Architecture:** httpClient 分成 requestOnce 與 recovery wrapper；AuthProvider 註冊 refresh/session-expired callbacks；Router bridge 專責導頁。React Query 不承擔 token refresh。

**Tech Stack:** React 19、TypeScript、React Router 7、TanStack Query、Vitest、Testing Library

## Global Constraints

- token 只在記憶體；每 request 最多 retry 一次；refresh endpoints 跳過 recovery。
- 並行 401 共用一次 refresh；403 不登出。
- 返回位置包含 pathname、search、hash，且只接受站內 URL。
- commit、push、merge 前需明確同意。

---

### Task 1: Typed ApiError

**Files:**
- Create: frontend/src/api/ApiError.ts
- Create: frontend/src/api/httpClient.test.ts
- Modify: frontend/src/api/httpClient.ts

**Interfaces:**
- Produces: ApiError(status, code, detail, instance)
- Produces: requestOnce(path, options)

- [ ] **Step 1: 測試 Problem Details 與非 JSON error**

    await expect(apiFetch("/api/admin")).rejects.toMatchObject({
      name: "ApiError",
      status: 403,
      code: "ACCESS_DENIED",
      message: "您沒有權限存取此資源",
      instance: "/api/admin"
    })

非 JSON 500 應為 請求失敗（500），不得包含 response body。

- [ ] **Step 2: 跑測試確認紅燈**

    docker run --rm -v "C:\SideProject\FlashSale\frontend:/app" -w /app node:20-alpine node node_modules/vitest/vitest.mjs run src/api/httpClient.test.ts

- [ ] **Step 3: 實作 ApiError**

    export class ApiError extends Error {
      constructor(
        public readonly status: number,
        public readonly code: string | null,
        detail: string,
        public readonly instance: string | null,
      ) {
        super(detail)
        this.name = "ApiError"
      }
    }

requestOnce 保留 credentials=include、Content-Type 與當前 Authorization header。

- [ ] **Step 4: 跑測試確認綠燈**

### Task 2: Single-flight recovery

**Files:**
- Modify: frontend/src/api/httpClient.ts
- Modify: frontend/src/api/httpClient.test.ts
- Modify: frontend/src/api/authApi.ts

**Interfaces:**
- Produces: configureAuthRecovery({ refresh, onSessionExpired })
- Produces: apiFetch(path, options?, { skipAuthRecovery?: boolean })

- [ ] **Step 1: 寫兩個並行 401 測試**

以 deferred refresh promise 啟動 /a、/b，斷言 refresh 一次、fetch 共四次、兩個結果成功。另測 retry 仍 401 時 session-expired 一次。

- [ ] **Step 2: 跑測試確認紅燈**

- [ ] **Step 3: 實作 single-flight**

    let refreshInFlight: Promise<void> | null = null

    async function recoverAuthentication() {
      if (!refreshInFlight) {
        refreshInFlight = authRecovery.refresh()
          .finally(() => { refreshInFlight = null })
      }
      return refreshInFlight
    }

apiFetch 只在第一次 401 且 skipAuthRecovery=false 時呼叫 recovery，再 requestOnce 一次；不做第二輪。

- [ ] **Step 4: authApi 全部明確 skip recovery**

register、login、refresh、logout 呼叫 apiFetch 時第三參數使用 skipAuthRecovery: true，避免遞迴。

- [ ] **Step 5: 跑 httpClient tests 確認綠燈**

### Task 3: Auth state 與導頁

**Files:**
- Modify: frontend/src/features/auth/useAuth.tsx
- Create: frontend/src/features/auth/AuthNavigationBridge.tsx
- Modify: frontend/src/App.tsx
- Modify: frontend/src/features/auth/LoginPage.tsx
- Test: frontend/src/App.test.tsx
- Test: frontend/src/features/auth/LoginPage.test.tsx

**Interfaces:**
- Produces: clearAuthentication(): void
- Consumes: configureAuthRecovery

- [ ] **Step 1: 寫 session expiry failing test**

從 /orders?tab=open#latest 觸發 refresh failure，斷言導到 /login 且 state.from 完整保存。測登入成功後回原 URL。

- [ ] **Step 2: 寫 403 不清狀態測試**

ApiError 403 後 AuthContext 仍 authenticated，畫面顯示中文 detail。

- [ ] **Step 3: 跑 scoped tests 確認紅燈**

    docker run --rm -v "C:\SideProject\FlashSale\frontend:/app" -w /app node:20-alpine node node_modules/vitest/vitest.mjs run src/App.test.tsx src/features/auth/LoginPage.test.tsx

- [ ] **Step 4: 實作 idempotent clearAuthentication**

呼叫 setAccessToken(null)、setIsAuthenticated(false)、setRole(null)。logout 使用 finally 執行相同清理。

- [ ] **Step 5: 實作 AuthNavigationBridge**

bridge 在 Router context 中註冊 recovery callbacks；session expiry 清除 protected queries 後：

    const from = location.pathname + location.search + location.hash
    navigate("/login", { replace: true, state: { from } })

- [ ] **Step 6: LoginPage 驗證站內 from**

只有 typeof from === "string" 且 from.startsWith("/") 才採用，否則使用 "/"。

- [ ] **Step 7: 跑 scoped tests 確認綠燈**

### Task 4: 驗證與交付

- [ ] **Step 1: 完整前端測試**

    docker run --rm -v "C:\SideProject\FlashSale\frontend:/app" -w /app node:20-alpine npm test

- [ ] **Step 2: build 與 lint**

    docker run --rm -v "C:\SideProject\FlashSale\frontend:/app" -w /app node:20-alpine npm run build
    docker run --rm -v "C:\SideProject\FlashSale\frontend:/app" -w /app node:20-alpine npm run lint

- [ ] **Step 3: git diff --check 與 status**

- [ ] **Step 4: 使用者同意後 commit**

    git add frontend/src/api frontend/src/features/auth frontend/src/App.tsx frontend/src/App.test.tsx docs/superpowers/specs/2026-08-16-frontend-auth-errors-design.md docs/superpowers/plans/2026-08-16-frontend-auth-errors.md
    git commit -m "fix: recover frontend sessions after unauthorized responses"

