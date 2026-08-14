# FlashSale Week 3 (使用者操作頁面) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the user-facing pages from `docs/superpowers/specs/2026-08-14-flash-sale-week3-user-pages-design.md`: a shared cross-page login state with a route guard, a purchase button + polling status page, and a my-orders list/detail with simulated payment and cancel — pure frontend, the backend API contract was already finalized and implemented in Week 2.

**Architecture:** Same `frontend/src/{api,features}` layout as Week 1/2. `features/auth/useAuth.tsx` becomes the single source of truth for login state via React Context (was per-component local state before); `RequireAuth` is a layout route that gates the three new authenticated pages. Each new page follows the existing pattern exactly: a `useQuery`/`useMutation` calling a plain async function in `api/*.ts`, rendered as semantic HTML with no CSS framework.

**Tech Stack:** Same as Week 1/2 (React 19, TypeScript, Vite, React Router 7, TanStack Query 5, React Hook Form, Zod, Vitest, Testing Library) — no new dependencies.

## Global Constraints

- No new npm dependencies — everything needed is already in `frontend/package.json`.
- No CSS/UI framework, no optimistic updates, no WebSocket/SSE — see design spec §10 for why. Plain semantic HTML matching the existing pages' style.
- Testing convention (matches `FlashSaleListPage.test.tsx`/`RegisterPage.test.tsx` exactly): `vi.spyOn` the relevant `api/*.ts` module, render with `QueryClientProvider` + `MemoryRouter`(+`Routes` when asserting navigation), assert via `screen`/`waitFor`. No MSW, no real backend, no fake timers for polling cadence — trust TanStack Query's `refetchInterval`, only assert on rendered states.
- Test runner: no `test` script exists in `package.json` yet — run via `npx vitest run <path>` from `frontend/`. Don't add a `test` script unless asked; out of scope.
- `apiFetch` (existing `api/httpClient.ts`) already throws on non-2xx and already attaches the bearer token — new API functions just call it, no new error-handling wrapper.
- JWT `userId` claim is resolved server-side from the bearer token for every endpoint used here — the frontend never needs to know or send the current user's ID.
- Component/context files that contain JSX must have a `.tsx` extension, including `useAuth.tsx` (renamed from `.ts` in Task 1 because it starts returning a `<Context.Provider>` element).

---

## File Structure

```text
frontend/src/
├─ api/
│  ├─ purchaseApi.ts             # Task 2 — createPurchaseRequest, getPurchaseRequest
│  └─ orderApi.ts                # Task 4 — listMyOrders, getOrder, cancelOrder, submitPayment
├─ features/
│  ├─ auth/
│  │  ├─ useAuth.tsx             # Task 1 — rewritten: AuthContext + AuthProvider + useAuth (renamed from useAuth.ts)
│  │  ├─ RequireAuth.tsx         # Task 1
│  │  ├─ RequireAuth.test.tsx    # Task 1
│  │  ├─ LoginPage.tsx           # Task 1 — modify: redirect to location.state.from after login
│  │  └─ RegisterPage.tsx        # unchanged
│  ├─ flash-sales/
│  │  ├─ FlashSaleListPage.tsx   # Task 4 — modify: nav link to /orders
│  │  └─ FlashSaleDetailPage.tsx # Task 3 — modify: add 搶購 button + mutation
│  ├─ purchase/
│  │  ├─ PurchaseStatusPage.tsx  # Task 2
│  │  └─ PurchaseStatusPage.test.tsx  # Task 2
│  └─ orders/
│     ├─ MyOrdersPage.tsx        # Task 4
│     ├─ MyOrdersPage.test.tsx   # Task 4
│     ├─ OrderDetailPage.tsx     # Task 5
│     └─ OrderDetailPage.test.tsx  # Task 5
├─ App.tsx                       # Task 1 — modify: wrap with AuthProvider, refresh() updates context
└─ router.tsx                    # Task 1–5 — modify: add RequireAuth layout route + new page routes
```

---

## Task 1: Shared Auth State + Route Guard

**Files:**
- Modify: `frontend/src/features/auth/useAuth.ts` → rewritten and renamed to `frontend/src/features/auth/useAuth.tsx`
- Create: `frontend/src/features/auth/RequireAuth.tsx`
- Test: `frontend/src/features/auth/RequireAuth.test.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/router.tsx`, `frontend/src/features/auth/LoginPage.tsx`

**Interfaces:**
- Consumes: `api/authApi.ts` (unchanged — `login`/`logout`/`refresh`).
- Produces: `useAuth() -> { isAuthenticated, login, logout, markAuthenticated }` (same public shape `LoginPage`/`RegisterPage` already use, plus `markAuthenticated` for `App.tsx`'s silent-refresh case). `AuthContext` is exported too, so tests can inject a value directly without going through `AuthProvider`'s real `login`/`refresh` calls. `<RequireAuth />` — a layout route element; Task 2/4/5's pages are nested under it in `router.tsx`.

- [ ] **Step 1: Write the failing RequireAuth test**

`frontend/src/features/auth/RequireAuth.test.tsx`:
```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RequireAuth } from './RequireAuth'
import { AuthContext } from './useAuth'

function renderWithAuth(isAuthenticated: boolean) {
  return render(
    <AuthContext.Provider value={{ isAuthenticated, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn() }}>
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
}

describe('RequireAuth', () => {
  it('redirects to /login when not authenticated', () => {
    renderWithAuth(false)
    expect(screen.getByText('login page')).toBeInTheDocument()
  })

  it('renders the protected route when authenticated', () => {
    renderWithAuth(true)
    expect(screen.getByText('secret content')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/auth/RequireAuth.test.tsx`
Expected: FAIL to compile — `RequireAuth` and `AuthContext` don't exist yet.

- [ ] **Step 3: Rewrite useAuth as a Context, add RequireAuth**

Delete `frontend/src/features/auth/useAuth.ts`, create `frontend/src/features/auth/useAuth.tsx`:
```tsx
import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import * as authApi from '../../api/authApi'

interface AuthContextValue {
  isAuthenticated: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  markAuthenticated: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)

  const login = useCallback(async (email: string, password: string) => {
    await authApi.login(email, password)
    setIsAuthenticated(true)
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout()
    setIsAuthenticated(false)
  }, [])

  const markAuthenticated = useCallback(() => setIsAuthenticated(true), [])

  return (
    <AuthContext.Provider value={{ isAuthenticated, login, logout, markAuthenticated }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
```

`frontend/src/features/auth/RequireAuth.tsx`:
```tsx
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './useAuth'

export function RequireAuth() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }
  return <Outlet />
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/auth/RequireAuth.test.tsx`
Expected: PASS

- [ ] **Step 5: Wire AuthProvider into App.tsx, update refresh() to use it**

Replace `frontend/src/App.tsx`:
```tsx
import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'
import * as authApi from './api/authApi'
import { AuthProvider, useAuth } from './features/auth/useAuth'

const queryClient = new QueryClient()

function AppContent() {
  const { markAuthenticated } = useAuth()

  useEffect(() => {
    authApi.refresh().then(markAuthenticated).catch(() => {
      // No valid refresh cookie (never logged in, or it expired) — stay logged out.
    })
  }, [markAuthenticated])

  return (
    <>
      <h1>FlashSale</h1>
      <RouterProvider router={router} />
    </>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AppContent />
      </AuthProvider>
    </QueryClientProvider>
  )
}
```

- [ ] **Step 6: LoginPage redirects to the originally-requested page after login**

In `frontend/src/features/auth/LoginPage.tsx`, add `useLocation` and change the mutation's `onSuccess`:
```tsx
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './useAuth'

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
})

type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login } = useAuth()
  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({ resolver: zodResolver(schema) })
  const from = (location.state as { from?: string } | null)?.from ?? '/'
  const mutation = useMutation({
    mutationFn: (values: FormValues) => login(values.email, values.password),
    onSuccess: () => navigate(from),
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

- [ ] **Step 7: Add the RequireAuth layout route to router.tsx (empty children for now — Tasks 2/4/5 add pages)**

`frontend/src/router.tsx`:
```tsx
import { createBrowserRouter } from 'react-router-dom'
import { FlashSaleListPage } from './features/flash-sales/FlashSaleListPage'
import { FlashSaleDetailPage } from './features/flash-sales/FlashSaleDetailPage'
import { RegisterPage } from './features/auth/RegisterPage'
import { LoginPage } from './features/auth/LoginPage'
import { RequireAuth } from './features/auth/RequireAuth'

export const router = createBrowserRouter([
  { path: '/', element: <FlashSaleListPage /> },
  { path: '/flash-sales/:id', element: <FlashSaleDetailPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [],
  },
])
```

- [ ] **Step 8: Run existing frontend suite to confirm nothing broke**

Run: `cd frontend && npx vitest run`
Expected: PASS — `App.test.tsx`, `RegisterPage.test.tsx`, `FlashSaleListPage.test.tsx`, and the new `RequireAuth.test.tsx` all green. (`LoginPage.tsx`'s behavior change has no existing test to break — none existed before this task.)

- [ ] **Step 9: Commit**

```bash
cd frontend
git add src/features/auth/useAuth.tsx src/features/auth/RequireAuth.tsx src/features/auth/RequireAuth.test.tsx src/features/auth/LoginPage.tsx src/App.tsx src/router.tsx
git rm src/features/auth/useAuth.ts
git commit -m "feat: make login state shared across pages via context, add route guard"
```

---

## Task 2: Purchase Status Polling Page

**Files:**
- Create: `frontend/src/api/purchaseApi.ts`
- Create: `frontend/src/features/purchase/PurchaseStatusPage.tsx`
- Test: `frontend/src/features/purchase/PurchaseStatusPage.test.tsx`
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: `GET /api/purchase-requests/{requestId}` (Week 2, unchanged) → `{ requestId, status, orderId }`.
- Produces: `createPurchaseRequest(flashSaleId, idempotencyKey)`, `getPurchaseRequest(requestId)` — Task 3's purchase button calls `createPurchaseRequest` and navigates here.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/purchase/PurchaseStatusPage.test.tsx`:
```tsx
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { PurchaseStatusPage } from './PurchaseStatusPage'
import * as purchaseApi from '../../api/purchaseApi'

function renderPage(requestId = 'req-1') {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/purchase-requests/${requestId}`]}>
        <Routes>
          <Route path="/purchase-requests/:requestId" element={<PurchaseStatusPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('PurchaseStatusPage', () => {
  it('shows a pending message while processing', async () => {
    vi.spyOn(purchaseApi, 'getPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'PENDING', orderId: null })
    renderPage()
    await waitFor(() => expect(screen.getByText(/處理中/)).toBeInTheDocument())
  })

  it('shows a success message with a link to the order when succeeded', async () => {
    vi.spyOn(purchaseApi, 'getPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'SUCCEEDED', orderId: 42 })
    renderPage()
    await waitFor(() => expect(screen.getByText(/搶購成功/)).toBeInTheDocument())
    expect(screen.getByRole('link', { name: /查看訂單/ })).toHaveAttribute('href', '/orders/42')
  })

  it('shows a sold-out message', async () => {
    vi.spyOn(purchaseApi, 'getPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'SOLD_OUT', orderId: null })
    renderPage()
    await waitFor(() => expect(screen.getByText(/已售完/)).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/purchase/PurchaseStatusPage.test.tsx`
Expected: FAIL to compile — neither file exists.

- [ ] **Step 3: Implement purchaseApi.ts and PurchaseStatusPage**

`frontend/src/api/purchaseApi.ts`:
```ts
import { apiFetch } from './httpClient'

export interface PurchaseRequestView {
  requestId: string
  status: string
  orderId: number | null
}

export async function createPurchaseRequest(flashSaleId: number, idempotencyKey: string): Promise<PurchaseRequestView> {
  const response = await apiFetch(`/api/flash-sales/${flashSaleId}/purchase-requests`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.json()
}

export async function getPurchaseRequest(requestId: string): Promise<PurchaseRequestView> {
  const response = await apiFetch(`/api/purchase-requests/${requestId}`)
  return response.json()
}
```

`frontend/src/features/purchase/PurchaseStatusPage.tsx`:
```tsx
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getPurchaseRequest } from '../../api/purchaseApi'

const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'SOLD_OUT', 'REJECTED', 'FAILED'])

const STATUS_MESSAGES: Record<string, string> = {
  PENDING: '搶購處理中，請稍候…',
  SUCCEEDED: '搶購成功！',
  SOLD_OUT: '很抱歉，商品已售完',
  REJECTED: '您已經購買過這個活動的商品',
  FAILED: '訂單建立失敗，系統已自動釋放您的庫存扣減，請重新嘗試搶購',
}

export function PurchaseStatusPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['purchase-requests', requestId],
    queryFn: () => getPurchaseRequest(requestId!),
    enabled: !!requestId,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && TERMINAL_STATUSES.has(status) ? false : 1000
    },
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load purchase request status.</div>

  return (
    <article>
      <p>{STATUS_MESSAGES[data.status] ?? data.status}</p>
      {data.status === 'SUCCEEDED' && data.orderId != null && (
        <Link to={`/orders/${data.orderId}`}>查看訂單</Link>
      )}
    </article>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/purchase/PurchaseStatusPage.test.tsx`
Expected: PASS

- [ ] **Step 5: Nest the page under RequireAuth in router.tsx**

In `frontend/src/router.tsx`, add the import and fill in the previously-empty `children` array:
```tsx
import { PurchaseStatusPage } from './features/purchase/PurchaseStatusPage'
// ...
  {
    element: <RequireAuth />,
    children: [
      { path: '/purchase-requests/:requestId', element: <PurchaseStatusPage /> },
    ],
  },
```

- [ ] **Step 6: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS

```bash
cd frontend
git add src/api/purchaseApi.ts src/features/purchase src/router.tsx
git commit -m "feat: add purchase status polling page"
```

---

## Task 3: Purchase Button on the Flash Sale Detail Page

**Files:**
- Modify: `frontend/src/features/flash-sales/FlashSaleDetailPage.tsx`
- Test: `frontend/src/features/flash-sales/FlashSaleDetailPage.test.tsx` (new — none existed before)

**Interfaces:**
- Consumes: `purchaseApi.createPurchaseRequest` (Task 2), `useAuth` (Task 1).
- Produces: nothing new consumed elsewhere — this is the entry point into the flow Task 2 built.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/flash-sales/FlashSaleDetailPage.test.tsx`:
```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { FlashSaleDetailPage } from './FlashSaleDetailPage'
import * as flashSaleApi from '../../api/flashSaleApi'
import * as purchaseApi from '../../api/purchaseApi'
import { AuthContext } from '../auth/useAuth'

const activeSale = {
  id: 1,
  productName: 'Limited Sneakers',
  productDescription: 'desc',
  salePrice: 9.99,
  startsAt: new Date().toISOString(),
  endsAt: new Date().toISOString(),
  purchaseLimitPerUser: 1,
  status: 'ACTIVE',
}

function renderPage(isAuthenticated: boolean) {
  const queryClient = new QueryClient()
  return render(
    <AuthContext.Provider value={{ isAuthenticated, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn() }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/flash-sales/1']}>
          <Routes>
            <Route path="/flash-sales/:id" element={<FlashSaleDetailPage />} />
            <Route path="/purchase-requests/:requestId" element={<div>status page</div>} />
            <Route path="/login" element={<div>login page</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </AuthContext.Provider>
  )
}

describe('FlashSaleDetailPage purchase button', () => {
  it('navigates to the status page after a successful purchase request', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    vi.spyOn(purchaseApi, 'createPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'PENDING', orderId: null })
    renderPage(true)

    await waitFor(() => screen.getByRole('button', { name: /搶購/ }))
    fireEvent.click(screen.getByRole('button', { name: /搶購/ }))

    await waitFor(() => expect(screen.getByText('status page')).toBeInTheDocument())
    expect(purchaseApi.createPurchaseRequest).toHaveBeenCalledWith(1, expect.any(String))
  })

  it('redirects to /login when not authenticated', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    const createSpy = vi.spyOn(purchaseApi, 'createPurchaseRequest')
    renderPage(false)

    await waitFor(() => screen.getByRole('button', { name: /搶購/ }))
    fireEvent.click(screen.getByRole('button', { name: /搶購/ }))

    await waitFor(() => expect(screen.getByText('login page')).toBeInTheDocument())
    expect(createSpy).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/flash-sales/FlashSaleDetailPage.test.tsx`
Expected: FAIL — no button named `搶購` exists yet.

- [ ] **Step 3: Add the purchase button**

Replace `frontend/src/features/flash-sales/FlashSaleDetailPage.tsx`:
```tsx
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getFlashSale } from '../../api/flashSaleApi'
import { createPurchaseRequest } from '../../api/purchaseApi'
import { useAuth } from '../auth/useAuth'

export function FlashSaleDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { isAuthenticated } = useAuth()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['flash-sales', id],
    queryFn: () => getFlashSale(Number(id)),
    enabled: !!id,
  })

  const mutation = useMutation({
    mutationFn: () => createPurchaseRequest(Number(id), crypto.randomUUID()),
    onSuccess: (result) => navigate(`/purchase-requests/${result.requestId}`),
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load flash sale.</div>

  const handlePurchase = () => {
    if (!isAuthenticated) {
      navigate('/login', { state: { from: `/flash-sales/${id}` } })
      return
    }
    mutation.mutate()
  }

  return (
    <article>
      <h2>{data.productName}</h2>
      <p>{data.productDescription}</p>
      <p>Price: ${data.salePrice.toFixed(2)}</p>
      <p>Status: {data.status}</p>
      <p>Ends at: {new Date(data.endsAt).toLocaleString()}</p>
      <button onClick={handlePurchase} disabled={data.status !== 'ACTIVE' || mutation.isPending}>
        搶購
      </button>
      {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
    </article>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/flash-sales/FlashSaleDetailPage.test.tsx`
Expected: PASS

- [ ] **Step 5: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS

```bash
cd frontend
git add src/features/flash-sales/FlashSaleDetailPage.tsx src/features/flash-sales/FlashSaleDetailPage.test.tsx
git commit -m "feat: add purchase button to flash sale detail page"
```

---

## Task 4: My Orders List Page

**Files:**
- Create: `frontend/src/api/orderApi.ts`
- Create: `frontend/src/features/orders/MyOrdersPage.tsx`
- Test: `frontend/src/features/orders/MyOrdersPage.test.tsx`
- Modify: `frontend/src/features/flash-sales/FlashSaleListPage.tsx` (nav link)
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: `GET /api/orders/me` (Week 1/2, unchanged) → `OrderSummary[]`.
- Produces: `listMyOrders`, `getOrder`, `cancelOrder`, `submitPayment` in `orderApi.ts` — Task 5's `OrderDetailPage` uses the latter three.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/orders/MyOrdersPage.test.tsx`:
```tsx
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { MyOrdersPage } from './MyOrdersPage'
import * as orderApi from '../../api/orderApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MyOrdersPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('MyOrdersPage', () => {
  it('renders fetched orders', async () => {
    vi.spyOn(orderApi, 'listMyOrders').mockResolvedValue([
      { id: 1, orderNo: 'ORD-1', totalAmount: 9.99, status: 'PAID' },
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
  })

  it('shows an empty state when there are no orders', async () => {
    vi.spyOn(orderApi, 'listMyOrders').mockResolvedValue([])
    renderPage()
    await waitFor(() => expect(screen.getByText('尚無訂單')).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/orders/MyOrdersPage.test.tsx`
Expected: FAIL to compile — neither file exists.

- [ ] **Step 3: Implement orderApi.ts and MyOrdersPage**

`frontend/src/api/orderApi.ts`:
```ts
import { apiFetch } from './httpClient'

export interface OrderSummary {
  id: number
  orderNo: string
  totalAmount: number
  status: string
}

export interface OrderDetail extends OrderSummary {
  paymentDueAt: string | null
}

export async function listMyOrders(): Promise<OrderSummary[]> {
  const response = await apiFetch('/api/orders/me')
  return response.json()
}

export async function getOrder(orderId: number): Promise<OrderDetail> {
  const response = await apiFetch(`/api/orders/${orderId}`)
  return response.json()
}

export async function cancelOrder(orderId: number): Promise<OrderDetail> {
  const response = await apiFetch(`/api/orders/${orderId}/cancel`, { method: 'POST' })
  return response.json()
}

export async function submitPayment(orderId: number, result: 'SUCCESS' | 'FAILURE'): Promise<OrderDetail> {
  const response = await apiFetch(`/api/orders/${orderId}/payments`, {
    method: 'POST',
    body: JSON.stringify({ result }),
  })
  return response.json()
}
```

`frontend/src/features/orders/MyOrdersPage.tsx`:
```tsx
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listMyOrders } from '../../api/orderApi'

export function MyOrdersPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['orders', 'me'], queryFn: listMyOrders })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load orders.</div>
  if (data.length === 0) return <p>尚無訂單</p>

  return (
    <ul>
      {data.map((order) => (
        <li key={order.id}>
          <Link to={`/orders/${order.id}`}>
            {order.orderNo} — {order.status} — ${order.totalAmount.toFixed(2)}
          </Link>
        </li>
      ))}
    </ul>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/orders/MyOrdersPage.test.tsx`
Expected: PASS

- [ ] **Step 5: Nest under RequireAuth, add nav link from the flash sale list**

In `frontend/src/router.tsx`, add the import and extend `children`:
```tsx
import { MyOrdersPage } from './features/orders/MyOrdersPage'
// ...
      { path: '/purchase-requests/:requestId', element: <PurchaseStatusPage /> },
      { path: '/orders', element: <MyOrdersPage /> },
```

In `frontend/src/features/flash-sales/FlashSaleListPage.tsx`, add a nav link (read the file first — just add `<Link to="/orders">我的訂單</Link>` near the top of the existing markup, don't restructure the rest of the page).

- [ ] **Step 6: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS

```bash
cd frontend
git add src/api/orderApi.ts src/features/orders/MyOrdersPage.tsx src/features/orders/MyOrdersPage.test.tsx src/features/flash-sales/FlashSaleListPage.tsx src/router.tsx
git commit -m "feat: add my-orders list page"
```

---

## Task 5: Order Detail Page — Simulated Payment and Cancel

**Files:**
- Create: `frontend/src/features/orders/OrderDetailPage.tsx`
- Test: `frontend/src/features/orders/OrderDetailPage.test.tsx`
- Modify: `frontend/src/router.tsx`

**Interfaces:**
- Consumes: `orderApi.getOrder`/`cancelOrder`/`submitPayment` (Task 4), `GET/POST /api/orders/{orderId}`, `POST /api/orders/{orderId}/payments`, `POST /api/orders/{orderId}/cancel` (Week 2, unchanged).
- Produces: nothing consumed elsewhere — this is the last page in the plan.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/orders/OrderDetailPage.test.tsx`:
```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { OrderDetailPage } from './OrderDetailPage'
import * as orderApi from '../../api/orderApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/orders/1']}>
        <Routes>
          <Route path="/orders/:orderId" element={<OrderDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const pendingOrder = { id: 1, orderNo: 'ORD-1', totalAmount: 9.99, status: 'PENDING_PAYMENT', paymentDueAt: new Date().toISOString() }
const paidOrder = { ...pendingOrder, status: 'PAID' }

describe('OrderDetailPage', () => {
  it('shows payment and cancel actions while PENDING_PAYMENT', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /模擬付款成功/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /模擬付款失敗/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /取消訂單/ })).toBeInTheDocument()
  })

  it('does not show actions once PAID', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(paidOrder)
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /模擬付款成功/ })).not.toBeInTheDocument()
  })

  it('submits SUCCESS payment and reflects the updated status', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    const submitSpy = vi.spyOn(orderApi, 'submitPayment').mockResolvedValue(paidOrder)
    renderPage()

    await waitFor(() => screen.getByRole('button', { name: /模擬付款成功/ }))
    fireEvent.click(screen.getByRole('button', { name: /模擬付款成功/ }))

    expect(submitSpy).toHaveBeenCalledWith(1, 'SUCCESS')
    await waitFor(() => expect(screen.getByText(/PAID/)).toBeInTheDocument())
  })

  it('cancels the order and reflects the updated status', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    const cancelSpy = vi.spyOn(orderApi, 'cancelOrder').mockResolvedValue({ ...pendingOrder, status: 'CANCELLED' })
    renderPage()

    await waitFor(() => screen.getByRole('button', { name: /取消訂單/ }))
    fireEvent.click(screen.getByRole('button', { name: /取消訂單/ }))

    expect(cancelSpy).toHaveBeenCalledWith(1)
    await waitFor(() => expect(screen.getByText(/CANCELLED/)).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/orders/OrderDetailPage.test.tsx`
Expected: FAIL to compile — the file doesn't exist.

- [ ] **Step 3: Implement OrderDetailPage**

`frontend/src/features/orders/OrderDetailPage.tsx`:
```tsx
import { useParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getOrder, cancelOrder, submitPayment } from '../../api/orderApi'
import type { OrderDetail } from '../../api/orderApi'

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const id = Number(orderId)
  const queryClient = useQueryClient()
  const queryKey = ['orders', orderId]

  const { data, isLoading, isError } = useQuery({
    queryKey,
    queryFn: () => getOrder(id),
    enabled: !!orderId,
  })

  const updateCache = (updated: OrderDetail) => queryClient.setQueryData(queryKey, updated)

  const payMutation = useMutation({
    mutationFn: (result: 'SUCCESS' | 'FAILURE') => submitPayment(id, result),
    onSuccess: updateCache,
  })
  const cancelMutation = useMutation({
    mutationFn: () => cancelOrder(id),
    onSuccess: updateCache,
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load order.</div>

  return (
    <article>
      <h2>{data.orderNo}</h2>
      <p>Status: {data.status}</p>
      <p>Total: ${data.totalAmount.toFixed(2)}</p>
      {data.status === 'PENDING_PAYMENT' && (
        <>
          {data.paymentDueAt && <p>付款期限：{new Date(data.paymentDueAt).toLocaleString()}</p>}
          <button onClick={() => payMutation.mutate('SUCCESS')} disabled={payMutation.isPending}>
            模擬付款成功
          </button>
          <button onClick={() => payMutation.mutate('FAILURE')} disabled={payMutation.isPending}>
            模擬付款失敗
          </button>
          <button onClick={() => cancelMutation.mutate()} disabled={cancelMutation.isPending}>
            取消訂單
          </button>
        </>
      )}
    </article>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/orders/OrderDetailPage.test.tsx`
Expected: PASS

- [ ] **Step 5: Nest under RequireAuth**

In `frontend/src/router.tsx`, add the import and extend `children`:
```tsx
import { OrderDetailPage } from './features/orders/OrderDetailPage'
// ...
      { path: '/orders', element: <MyOrdersPage /> },
      { path: '/orders/:orderId', element: <OrderDetailPage /> },
```

- [ ] **Step 6: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: PASS — every test from Tasks 1–5 green together with Week 1's untouched `App.test.tsx`/`RegisterPage.test.tsx`/`FlashSaleListPage.test.tsx`.

- [ ] **Step 7: Manual walkthrough against the real backend**

`docker compose up --build -d` (needs `.env` with `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`, see Week 2's compose setup), then in a browser at `https://localhost:8443`: register → login → open a flash sale → 搶購 → watch the status page resolve to SUCCEEDED → click through to the order → simulate a successful payment → separately, on a second order, simulate a failed payment and confirm it cancels and the flash sale's available stock goes back up (via `GET /api/flash-sales/{id}`) → cancel a third still-pending order directly. This is the plan's only manual step, everything else is covered by the automated tests above.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/features/orders/OrderDetailPage.tsx src/features/orders/OrderDetailPage.test.tsx src/router.tsx
git commit -m "feat: add order detail page with simulated payment and cancel"
```
