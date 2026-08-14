# FlashSale Week 3 (使用者操作頁面) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the user-facing pages from `docs/superpowers/specs/2026-08-14-flash-sale-week3-user-pages-design.md`: a shared cross-page login state with a route guard, a purchase button + polling status page, and a my-orders list/detail with simulated payment and cancel — pure frontend, the backend API contract was already finalized and implemented in Week 2.

**Architecture:** Same `frontend/src/{api,features}` layout as Week 1/2. `features/auth/useAuth.tsx` becomes the single source of truth for login state via React Context (was per-component local state before); `RequireAuth` is a layout route that gates the three new authenticated pages. Each new page follows the existing pattern exactly: a `useQuery`/`useMutation` calling a plain async function in `api/*.ts`, rendered as semantic HTML with no CSS framework.

**Tech Stack:** Same as Week 1/2 (React 19, TypeScript, Vite, React Router 7, TanStack Query 5, React Hook Form, Zod, Vitest, Testing Library) — no new dependencies.

## Global Constraints

- No new npm dependencies — everything needed is already in `frontend/package.json`, including for Tasks 6-8's visual design system (hand-rolled CSS custom properties, no Tailwind/component library — see design spec §10/§11 for why).
- No optimistic updates, no WebSocket/SSE — see design spec §10 for why.
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

---

## Addendum: Ticket-Stub Design System + RWD + Register→Login Prefill

Tasks 1-5 above shipped the *functional* Week 3 pages with zero styling — `frontend/src/index.css` is still Vite's unmodified scaffold CSS (purple `#aa3bff` accent, fixed 1126px `#root`), never actually designed for FlashSale. Design spec §11 (added after a visual-direction review) specifies a "ticket stub" design system: warm paper tones, a ticket-notch logo mark, red-orange accent, a 3-color semantic status system (go/wait/stop), and mobile-first RWD. Read design spec §11 in full before starting — it has the complete rationale and the design-token table. Tasks 6-8 below implement it, plus one small unrelated UX fix (register→login email prefill) folded into Task 7 since it touches the same two files.

**Global note for Tasks 6-8:** No new npm dependency — everything is hand-rolled CSS custom properties + plain CSS classes (design spec §11.0 explains why Tailwind/a component library was rejected: the token system is already fully specified, adding a build-tool dependency for it would be pure overhead). Every color, in every component, must come from a `var(--token)` in `frontend/src/styles/tokens.css` — never a literal hex value in a component file. Both light and dark mode must resolve correctly (`@media (prefers-color-scheme: dark)` guarded as `:root:not([data-theme="light"])`, `:root[data-theme="dark"]` too, even though this app has no theme-toggle UI yet — the token structure should be ready for one).

**One deviation from the approved visual mockup, decided up front — don't rediscover this mid-task:** the mockup's flash-sale detail screen showed a "剩餘庫存 1/3" stock meter. `GET /api/flash-sales/{id}` (`FlashSaleDetail` DTO) does not expose remaining/total quantity — only `productDescription` and `purchaseLimitPerUser`. Adding that field would be a backend DTO change, out of scope for this frontend-only round (design spec §0). **Drop the stock meter entirely** from the real `FlashSaleDetailPage` — keep the countdown-to-`endsAt` strip (real data, already returned) and the "每人限購 N 件" note (from `purchaseLimitPerUser`, already returned).

> **Update (post Task 6-8):** the user asked for this back. `FlashSaleDetail` gained `totalQuantity`/`availableQuantity` (backed by `InventoryRepository.findByFlashSaleId`, defaulting to `0`/`0` if no inventory row exists yet — `Inventory` also gained a `getTotalQuantity()` getter it didn't have before), and `FlashSaleDetailPage` shows the stock meter again, guarded by `data.totalQuantity > 0` so the zero-inventory default doesn't render a nonsensical "0 / 0" bar. This was a small, deliberate exception to "no backend changes this round" — approved explicitly, not a scope-creep reversal to repeat elsewhere in this plan without asking first.

- [ ] **Step 0: Read the design spec**

Read `docs/superpowers/specs/2026-08-14-flash-sale-week3-user-pages-design.md` §10-11 in full (token list, status-color mapping table, RWD breakpoints, shared-component list, font strategy) before writing any code in Tasks 6-8.

---

## Task 6: Design Tokens + Shared Components + Flash Sale Pages

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/index.css` (strip the Vite boilerplate, keep only a body reset), `frontend/src/main.tsx` (import order: tokens.css before index.css)
- Create: `frontend/src/components/AppNav.tsx`, `frontend/src/components/StatusPill.tsx`
- Modify: `frontend/src/features/flash-sales/FlashSaleListPage.tsx`, `frontend/src/features/flash-sales/FlashSaleDetailPage.tsx`

**Interfaces:**
- Consumes: nothing new from the backend.
- Produces: `<AppNav />` (reads `useAuth()` itself for the conditional 登出 button, no props needed) — used by Task 6 (List) and Task 8 (MyOrders); `FlashSaleDetailPage` also gets it per spec §11.5. `<StatusPill status="ACTIVE" />` (or `SCHEDULED`/`ENDED`/`PENDING`/`SUCCEEDED`/`SOLD_OUT`/`REJECTED`/`FAILED`/`PENDING_PAYMENT`/`PAID`/`CANCELLED`/`EXPIRED`) — maps 1:1 via the table in spec §11.2, used by every task from here on. Unknown status strings should render as-is in a neutral pill rather than throwing — defensive, not a scope excuse to over-engineer: one `default` branch, nothing more.

- [ ] **Step 1: Design tokens**

`frontend/src/styles/tokens.css` — the complete light palette on bare `:root`, redefined for dark:

```css
:root {
  --ink: #1b1710;
  --paper: #f4f0e6;
  --paper-raised: #fffdf8;
  --line: #d8d0bd;
  --line-strong: #c2b89e;
  --muted: #8a8066;
  --stub: #d8391e;
  --stub-hover: #b62f18;
  --stub-ink: #fff8ed;
  --go: #2e7d4f;
  --go-tint: rgba(46, 125, 79, .12);
  --wait: #96650f;
  --wait-tint: rgba(184, 134, 46, .16);
  --stop: #a32f1f;
  --stop-tint: rgba(163, 47, 31, .12);
  --shadow: 0 1px 2px rgba(27, 23, 16, .06), 0 8px 20px -12px rgba(27, 23, 16, .18);
  --font-display: "Archivo Black Sub", "Arial Black", sans-serif;
  --font-ui: -apple-system, "Segoe UI", "PingFang TC", "Microsoft JhengHei", system-ui, sans-serif;
  --font-mono: ui-monospace, "SFMono-Regular", "Cascadia Mono", Consolas, monospace;
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --ink: #f3ede0; --paper: #171310; --paper-raised: #211c16;
    --line: #3a3226; --line-strong: #4a4030; --muted: #a79c82;
    --stub: #ff5b37; --stub-hover: #ff7455; --stub-ink: #171310;
    --go: #57c98a; --go-tint: rgba(87, 201, 138, .16);
    --wait: #e0ac4e; --wait-tint: rgba(224, 172, 78, .18);
    --stop: #ff6b52; --stop-tint: rgba(255, 107, 82, .16);
    --shadow: 0 1px 2px rgba(0,0,0,.3), 0 12px 28px -14px rgba(0,0,0,.6);
  }
}
:root[data-theme="dark"] {
  /* identical property list to the media-query block above — copy it verbatim so an explicit
     future theme toggle wins over the OS setting in both directions */
}

body { margin: 0; background: var(--paper); color: var(--ink); font-family: var(--font-ui); }
```

Do **not** embed the Archivo Black webfont this round — it was a nice-to-have in the mockup for headline flavor, not load-bearing for the design (every screen still reads correctly in the fallback `"Arial Black", sans-serif`), and embedding a font file correctly (subsetting, base64, `@font-face`) is meaningfully more work for a purely decorative upgrade. `ponytail: skipped webfont embedding, --font-display falls back to system Arial Black — add the real subsetted woff2 (already produced once during the mockup, ask the controller session if it still has it) if the fallback reads too plain once it's live.`

- [ ] **Step 2: Strip the Vite boilerplate**

`frontend/src/index.css` currently has the unused Vite scaffold (`--accent: #aa3bff`, fixed-width `#root`, etc. — read it first). Delete all of it; the only thing `index.css` should still own after this step is whatever generic host-level reset isn't already in `tokens.css`'s `body` rule (there may be nothing left to keep — an empty or near-empty file is the expected outcome, don't invent rules to fill it). Import `./styles/tokens.css` before `./index.css` in `main.tsx`.

- [ ] **Step 3: AppNav**

`frontend/src/components/AppNav.tsx` — the ticket-notch wordmark badge + `我的訂單` link + conditional `登出` button (reuses the exact markup/behavior already proven in `FlashSaleListPage.tsx` from the earlier UX-gap fix — move it here, don't rewrite the logic):

```tsx
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/useAuth'

export function AppNav() {
  const { isAuthenticated, logout } = useAuth()
  return (
    <nav className="app-nav">
      <span className="wordmark">FLASH SALE</span>
      <div className="nav-links">
        <Link className="nav-link" to="/orders">我的訂單</Link>
        {isAuthenticated && <button className="nav-logout" onClick={() => logout()}>登出</button>}
      </div>
    </nav>
  )
}
```
Wordmark notch CSS (the two "punched circle" pseudo-elements — this exact technique, don't reinvent):
```css
.wordmark {
  position: relative; display: inline-flex; align-items: center;
  padding: 5px 12px 5px 14px; background: var(--stub); color: var(--stub-ink);
  font-family: var(--font-display); font-weight: 900; font-size: .78rem;
  letter-spacing: .04em; border-radius: 3px;
}
.wordmark::before, .wordmark::after {
  content: ""; position: absolute; top: 50%; width: 9px; height: 9px;
  background: var(--paper-raised); border-radius: 50%; transform: translateY(-50%);
}
.wordmark::before { left: -4.5px; } .wordmark::after { right: -4.5px; }
```
This notch trick assumes `.wordmark` always sits directly on a `var(--paper-raised)`-colored surface (true for every current usage — the nav bar background is `var(--paper-raised)` everywhere `AppNav` appears). If a future page ever puts `AppNav` on a different background, the notch fill color needs to change with it — don't build that flexibility now, nothing needs it.

- [ ] **Step 4: StatusPill**

`frontend/src/components/StatusPill.tsx`:
```tsx
const STATUS_MAP: Record<string, { tone: 'go' | 'wait' | 'stop'; label: string }> = {
  ACTIVE: { tone: 'go', label: '搶購中' },
  SCHEDULED: { tone: 'wait', label: '即將開賣' },
  ENDED: { tone: 'stop', label: '已結束' },
  PENDING: { tone: 'wait', label: '搶購處理中' },
  SUCCEEDED: { tone: 'go', label: '搶購成功' },
  SOLD_OUT: { tone: 'stop', label: '已售完' },
  REJECTED: { tone: 'stop', label: '已購買過' },
  FAILED: { tone: 'stop', label: '建單失敗' },
  PENDING_PAYMENT: { tone: 'wait', label: '待付款' },
  PAID: { tone: 'go', label: '已付款' },
  CANCELLED: { tone: 'stop', label: '已取消' },
  EXPIRED: { tone: 'stop', label: '已逾期' },
}

export function StatusPill({ status }: { status: string }) {
  const entry = STATUS_MAP[status] ?? { tone: 'stop' as const, label: status }
  return <span className={`pill ${entry.tone}`}>{entry.label}</span>
}
```
Pill CSS:
```css
.pill { display: inline-flex; align-items: center; gap: 5px; font-size: .68rem; font-weight: 700;
  letter-spacing: .03em; padding: 3px 9px 3px 7px; border-radius: 20px; text-transform: uppercase; white-space: nowrap; }
.pill::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.pill.go { background: var(--go-tint); color: var(--go); }
.pill.wait { background: var(--wait-tint); color: var(--wait); }
.pill.stop { background: var(--stop-tint); color: var(--stop); }
```

- [ ] **Step 5: Restyle FlashSaleListPage — RWD grid**

Read the current file first (it already has the nav/logout markup from the earlier UX fix — replace that inline markup with `<AppNav />`). Card list becomes a responsive grid: single column under 900px, `repeat(auto-fill, minmax(280px, 1fr))` at ≥900px (spec §11.4). Each list item uses `<StatusPill status={sale.status} />` instead of printing `{sale.status}` raw. Ticket-card visual: main content + a dashed-border right edge (`border-left: 1px dashed var(--line-strong)`) — see the approved mockup's `.sale-card`/`.stub-edge` classes for the exact look, port them into a stylesheet colocated with the component (e.g. `FlashSaleListPage.css`, plain CSS import — no CSS-in-JS library, none is installed).

- [ ] **Step 6: Restyle FlashSaleDetailPage — no stock meter (see Addendum note above)**

Hero product name (`.detail-hero h2`, `var(--font-display)`), price tag in tabular mono, a live countdown-to-`endsAt` strip (plain `useEffect` + `setInterval(1000)` computing `endsAt - now`, cleared on unmount — no library), `每人限購 N 件` note from `purchaseLimitPerUser`, the 搶購 button restyled as `.btn.btn-primary.btn-block`. Wrap with `<AppNav />` at the top. Detail content gets a `max-width` (spec §11.4) and stays centered on wide viewports rather than stretching full-width.

- [ ] **Step 7: Run the existing test suites, fix any DOM-query breakage**

Run: `cd frontend && npx vitest run`
`FlashSaleListPage.test.tsx` and `FlashSaleDetailPage.test.tsx` assert by text/role, not by class, so they should keep passing — but `AppNav`/`StatusPill` change the DOM structure around that text (e.g. status text is no longer a bare text node, it's inside a `<span class="pill">`), so re-check each assertion actually still matches (`getByText` on a status string still works whether or not it's wrapped in a span, but double-check literals like raw `'ACTIVE'` in test mocks/assertions against the new Chinese pill labels — the test data can keep sending `'ACTIVE'` as the API value, just confirm the assertion checks for the *rendered* label now, not the raw enum, if it was asserting on status text at all).
Expected: PASS, full suite still 100% green.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/styles src/index.css src/main.tsx src/components/AppNav.tsx src/components/StatusPill.tsx src/features/flash-sales
git commit -m "feat: add ticket-stub design system, apply to flash sale list/detail"
```

---

## Task 7: Auth Pages Redesign + Register→Login Email Prefill

**Files:**
- Modify: `frontend/src/features/auth/LoginPage.tsx`, `frontend/src/features/auth/RegisterPage.tsx`
- Modify (tests): `frontend/src/features/auth/RegisterPage.test.tsx` (extend), new `frontend/src/features/auth/LoginPage.test.tsx` (none existed before)

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Register passes email forward, Login pre-fills it**

In `RegisterPage.tsx`, change the mutation's `onSuccess`:
```tsx
onSuccess: (_, values) => navigate('/login', { state: { email: values.email } }),
```
(`useMutation`'s `onSuccess` receives `(data, variables)` — `variables` is the `values` passed to `mutate`, no need to thread the email through some other way.)

In `LoginPage.tsx`, read the prefill and pass it as the form's default value — don't just set the input's `value` imperatively, use react-hook-form's own `defaultValues` so it stays an uncontrolled field consistent with how the rest of the form works:
```tsx
const location = useLocation()
const prefillEmail = (location.state as { email?: string } | null)?.email ?? ''
const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({
  resolver: zodResolver(schema),
  defaultValues: { email: prefillEmail },
})
```
`location.state` already carries `from` (Task 1) — this adds `email` alongside it, both optional, independent of each other (arriving via a register-redirect always has `email` and never `from`; arriving via a RequireAuth-redirect always has `from` and never `email`; both are handled with independent `?.` fallbacks, neither assumes the other is present).

- [ ] **Step 2: Restyle both pages — centered ticket card**

Both forms become a centered `.auth-card` (see mockup: `.auth-body` flex-centers it, `max-width` keeps it a readable card width on desktop, full-width with page padding on mobile — this is the one RWD rule these two pages need, no grid/multi-column concern since it's a single form). `<AppNav />` is **not** used here (spec §11.5 — auth screens stay standalone, no nav chrome before you're logged in). Reuse the `.field`/`.btn` classes from Task 6's stylesheet rather than inventing new ones — forms need the same input/label/button look everywhere.

- [ ] **Step 3: Tests**

Extend `RegisterPage.test.tsx`'s existing test (or add a case) asserting `navigate` was called with `('/login', { state: { email: 'a@example.com' } })` — the existing test already mocks `authApi.register`; you need to also check the navigation target, which means mocking `react-router-dom`'s `useNavigate` (the codebase hasn't needed this yet — check how Task 3's `FlashSaleDetailPage.test.tsx` verified navigation: it rendered real destination routes and asserted on rendered content rather than mocking `useNavigate` directly. Prefer that established pattern here too — render `RegisterPage` inside a `MemoryRouter`/`Routes` with a stub `/login` route that reads and displays `location.state?.email`, then assert the stub shows the right email, instead of introducing a `vi.mock('react-router-dom')` pattern this codebase doesn't otherwise use).

New `LoginPage.test.tsx`: render with `initialEntries={[{ pathname: '/login', state: { email: 'prefill@example.com' } }]}`, assert the email input's value is pre-filled (`screen.getByLabelText(/email/i)` should have `value === 'prefill@example.com'`). Second case: no `state` → email input starts empty.

Run: `cd frontend && npx vitest run src/features/auth`
Expected: PASS.

- [ ] **Step 4: Run full suite, commit**

Run: `cd frontend && npx vitest run`
Expected: PASS, 100% green.

```bash
cd frontend
git add src/features/auth
git commit -m "feat: restyle auth pages, prefill login email after registration"
```

---

## Task 8: Purchase Status + Orders Pages Redesign

**Files:**
- Modify: `frontend/src/features/purchase/PurchaseStatusPage.tsx`, `frontend/src/features/orders/MyOrdersPage.tsx`, `frontend/src/features/orders/OrderDetailPage.tsx`

**Interfaces:**
- Consumes: `StatusPill`, `AppNav` (Task 6).
- Produces: nothing new consumed elsewhere — last task in the plan.

- [ ] **Step 1: PurchaseStatusPage — stamp treatment**

Terminal states get the rotated stamp look (`.stamp.go` for `SUCCEEDED`, `.stamp.stop` for `SOLD_OUT`/`REJECTED`/`FAILED`); `PENDING` gets the spinning ring (`.ring.spin`, `@keyframes spin`, guarded by `@media (prefers-reduced-motion: no-preference)` — the ring must render as a plain static ring with no motion at all when the visitor has reduced-motion set, not a slower spin). No `<AppNav />` here (spec §11.5, same reasoning as the auth pages — this is a focused single-purpose screen). Centered, `max-width` capped same as the detail pages.

- [ ] **Step 2: MyOrdersPage — ticket cards + AppNav + empty state**

Add `<AppNav />`. Order list becomes `.order-card`s (mono order number, `<StatusPill>`, tabular amount) in the same responsive grid rule as Task 6's flash-sale list (spec §11.4 — reuse the same breakpoint, don't invent a second grid rule). Empty state (`尚無訂單`) gets the dashed-ticket-icon treatment from the mockup, not just bare text.

- [ ] **Step 3: OrderDetailPage — countdown strip + button row**

`<AppNav />` **not** included (matches the approved mockup's order-detail frames, which show only a `‹ 我的訂單` back-link, no nav bar — consistent with Task 5's already-shipped back-link fix). Total amount in a bordered strip (dashed top/bottom, tabular mono, larger size). `PENDING_PAYMENT`'s three actions become `.btn-outline-go`/`.btn-outline-stop`/`.btn-ghost` per the mockup instead of three identical plain `<button>`s. `PAID`/other terminal states show the `.paid-note` treatment (small dot + confirmation line) instead of just silently showing no buttons.

- [ ] **Step 4: Run full suite**

Run: `cd frontend && npx vitest run`
Expected: PASS — every test from Tasks 1-8 green together (re-check `OrderDetailPage.test.tsx`'s status-text assertions against the new `StatusPill` Chinese labels, same caveat as Task 6 Step 7).

- [ ] **Step 5: Manual RWD check**

`npm run dev` (no need for the full `docker compose` backend stack just to eyeball layout — component structure and CSS don't need real API data; use the browser devtools responsive mode, or if a quick visual sanity check against real data is wanted, the Task 5 manual-walkthrough stack from earlier still works). Check at minimum: 375px (mobile), 768px (tablet), 1280px (desktop) — list/grid pages reflow, auth/detail cards don't stretch full-width absurdly on desktop, nothing overflows horizontally.

- [ ] **Step 6: Commit**

```bash
cd frontend
git add src/features/purchase src/features/orders
git commit -m "feat: restyle purchase status and order pages, complete design system rollout"
```
