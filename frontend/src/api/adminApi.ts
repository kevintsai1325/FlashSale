// Base file for admin API calls — shared types/helpers only.
// Tasks 9-11 each add their own endpoint functions here (dashboard, api-logs,
// orders, notifications) on top of this shared paging envelope.

import { apiFetch } from './httpClient'

export type ServiceHealthStatus = 'UP' | 'DOWN' | 'UNKNOWN'

export interface ServiceHealthView {
  name: string
  status: ServiceHealthStatus
  checkedAt: string
  reason: string
}

export interface SystemHealthView {
  overallStatus: ServiceHealthStatus
  checkedAt: string
  services: ServiceHealthView[]
}

export async function getSystemHealth(): Promise<SystemHealthView> {
  const response = await apiFetch('/api/admin/system-health')
  return response.json()
}

/** Mirrors the backend's `PagedResult<T>` response envelope for admin list endpoints. */
export interface PagedResult<T> {
  content: T[]
  totalElements: number
  page: number
  size: number
}

/** Mirrors the backend's `InventorySummary` record. */
export interface InventorySummary {
  totalQuantity: number
  availableQuantity: number
  reservedQuantity: number
  soldQuantity: number
}

/** Mirrors the backend's `DashboardSummary` record. */
export interface DashboardSummary {
  totalPurchaseRequests: number
  succeededPurchaseRequests: number
  /** Keys are `OrderStatus` enum names, e.g. `PENDING_PAYMENT`, `PAID`. */
  ordersByStatus: Record<string, number>
  totalPaidAmount: number
  /** Keys are flash-sale IDs serialized as JSON string keys by Jackson (conceptually `Long`). */
  inventoryByFlashSaleId: Record<string, InventorySummary>
}

/** Mirrors the backend's `TrendPoint` record. `bucketStart` is an ISO-8601 string, not parsed here. */
export interface TrendPoint {
  bucketStart: string
  purchaseRequestCount: number
  orderCount: number
}

/** Mirrors the backend's `DashboardTrends` record. */
export interface DashboardTrends {
  lastHour: TrendPoint[]
  last24Hours: TrendPoint[]
}

export async function getDashboardSummary(): Promise<DashboardSummary> {
  const response = await apiFetch('/api/admin/dashboard/summary')
  return response.json()
}

export async function getDashboardTrends(): Promise<DashboardTrends> {
  const response = await apiFetch('/api/admin/dashboard/trends')
  return response.json()
}

/** Mirrors the backend's `ApiAuditLogView` record. `occurredAt` is an ISO-8601 string, not parsed here. */
export interface ApiAuditLogView {
  id: number
  occurredAt: string
  method: string
  pathTemplate: string
  status: number
  userId: number | null
  requestId: string | null
  traceId: string | null
  durationMs: number
  clientIp: string | null
  userAgent: string | null
  errorCode: string | null
}

/** Query params accepted by `GET /api/admin/api-logs` (`AdminApiLogController`). */
export interface ApiLogFilters {
  method?: string
  pathTemplate?: string
  status?: number
  userId?: number
  traceId?: string
  /** ISO-8601 instant string, inclusive lower bound on `occurredAt`. */
  from?: string
  /** ISO-8601 instant string, inclusive upper bound on `occurredAt`. */
  to?: string
  page?: number
  size?: number
}

export async function listApiLogs(filters: ApiLogFilters = {}): Promise<PagedResult<ApiAuditLogView>> {
  const params = new URLSearchParams()
  if (filters.method) params.set('method', filters.method)
  if (filters.pathTemplate) params.set('pathTemplate', filters.pathTemplate)
  if (filters.status !== undefined) params.set('status', String(filters.status))
  if (filters.userId !== undefined) params.set('userId', String(filters.userId))
  if (filters.traceId) params.set('traceId', filters.traceId)
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  params.set('page', String(filters.page ?? 0))
  params.set('size', String(filters.size ?? 20))

  const response = await apiFetch(`/api/admin/api-logs?${params.toString()}`)
  return response.json()
}

/** Mirrors the backend's `AdminOrderSummary` record. `createdAt` is an ISO-8601 string. */
export interface AdminOrderSummary {
  id: number
  orderNo: string
  userId: number
  totalAmount: number
  status: string
  createdAt: string
}

/** Query params accepted by `GET /api/admin/orders` (`AdminOrderController`). */
export interface AdminOrderListFilters {
  /** `OrderStatus` enum name, e.g. `PENDING_PAYMENT`, `PAID`, `CANCELLED`, `EXPIRED`. Omit for all statuses. */
  status?: string
  page?: number
  size?: number
}

export async function listAdminOrders(
  filters: AdminOrderListFilters = {}
): Promise<PagedResult<AdminOrderSummary>> {
  const params = new URLSearchParams()
  if (filters.status) params.set('status', filters.status)
  params.set('page', String(filters.page ?? 0))
  params.set('size', String(filters.size ?? 20))

  const response = await apiFetch(`/api/admin/orders?${params.toString()}`)
  return response.json()
}

/** Mirrors the backend's `AdminOrderItemView` record. */
export interface AdminOrderItemView {
  productId: number
  quantity: number
  unitPrice: number
}

/** Mirrors the backend's `PurchaseRequestView` record. `requestId` is a UUID serialized as a string. */
export interface PurchaseRequestView {
  requestId: string
  status: string
  orderId: number | null
}

/** Mirrors the backend's `OrderStatusHistoryView` record. `fromStatus` is null for the initial transition. */
export interface OrderStatusHistoryView {
  fromStatus: string | null
  toStatus: string
  changedAt: string
}

/**
 * Mirrors the backend's `AdminOrderDetail` record.
 *
 * `relatedApiLogs` is a deliberately approximate, non-exact cross-reference: the backend has no
 * `trace_id` column on `orders`, so it looks up `api_audit_logs` rows for this order's `userId`
 * that fall within a +/-5-minute window around the order's creation time. Render it as a "logs
 * around this time" hint, never as "the request that created this order."
 */
export interface AdminOrderDetail {
  id: number
  orderNo: string
  userId: number
  totalAmount: number
  status: string
  paymentDueAt: string | null
  createdAt: string
  items: AdminOrderItemView[]
  purchaseRequest: PurchaseRequestView | null
  statusHistory: OrderStatusHistoryView[]
  relatedApiLogs: ApiAuditLogView[]
}

export async function getAdminOrderDetail(orderId: number): Promise<AdminOrderDetail> {
  const response = await apiFetch(`/api/admin/orders/${orderId}`)
  return response.json()
}

/** Mirrors the backend's `NotificationView` record. `createdAt`/`updatedAt` are ISO-8601 strings. */
export interface NotificationView {
  id: number
  userId: number
  channel: string
  template: string
  recipient: string
  status: string
  attemptCount: number
  lastError: string | null
  read: boolean
  createdAt: string
  updatedAt: string
}

/** Query params accepted by `GET /api/admin/notifications` (`AdminNotificationController`). */
export interface NotificationFilters {
  userId?: number
  channel?: string
  status?: string
  read?: boolean
  page?: number
  size?: number
}

export async function listNotifications(
  filters: NotificationFilters = {}
): Promise<PagedResult<NotificationView>> {
  const params = new URLSearchParams()
  if (filters.userId !== undefined) params.set('userId', String(filters.userId))
  if (filters.channel) params.set('channel', filters.channel)
  if (filters.status) params.set('status', filters.status)
  if (filters.read !== undefined) params.set('read', String(filters.read))
  params.set('page', String(filters.page ?? 0))
  params.set('size', String(filters.size ?? 20))

  const response = await apiFetch(`/api/admin/notifications?${params.toString()}`)
  return response.json()
}

export async function getNotificationDetail(id: number): Promise<NotificationView> {
  const response = await apiFetch(`/api/admin/notifications/${id}`)
  return response.json()
}

/** Body mirrors the backend's `ReadStatusRequest`: `{ ids: number[], read: boolean }`. */
export async function updateNotificationReadStatus(ids: number[], read: boolean): Promise<void> {
  await apiFetch('/api/admin/notifications/read-status', {
    method: 'PATCH',
    body: JSON.stringify({ ids, read }),
  })
}

export async function retryNotification(id: number): Promise<void> {
  await apiFetch(`/api/admin/notifications/${id}/retry`, { method: 'POST' })
}

/** Backend returns `Map<String, Long>` shaped as `{ count: <unread count> }`. */
export async function getUnreadCount(): Promise<number> {
  const response = await apiFetch('/api/admin/notifications/unread-count')
  const data: { count: number } = await response.json()
  return data.count
}

/** Mirrors the backend's `admin.application.dto.ProductView` record. */
export interface ProductView {
  id: number
  name: string
  description: string | null
}

export async function listProducts(): Promise<ProductView[]> {
  const response = await apiFetch('/api/admin/products')
  return response.json()
}

export async function createProduct(name: string, description: string): Promise<ProductView> {
  const response = await apiFetch('/api/admin/products', {
    method: 'POST',
    body: JSON.stringify({ name, description }),
  })
  return response.json()
}

export async function updateProduct(id: number, name: string, description: string): Promise<ProductView> {
  const response = await apiFetch(`/api/admin/products/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ name, description }),
  })
  return response.json()
}

/** Mirrors the backend's `flashsale.application.dto.FlashSaleSummary` record. */
export interface AdminFlashSaleSummary {
  id: number
  productId: number
  productName: string
  salePrice: number
  startsAt: string
  endsAt: string
  purchaseLimitPerUser: number
  totalQuantity: number
  status: string
}

export interface UpdateFlashSaleInput {
  salePrice: number
  startsAt: string
  endsAt: string
  purchaseLimitPerUser: number
  totalQuantity: number
}

export async function updateFlashSale(
  id: number,
  input: UpdateFlashSaleInput,
): Promise<{ id: number; status: string }> {
  const response = await apiFetch(`/api/admin/flash-sales/${id}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
  return response.json()
}

export async function listAdminFlashSales(): Promise<AdminFlashSaleSummary[]> {
  const response = await apiFetch('/api/admin/flash-sales')
  return response.json()
}

export interface CreateFlashSaleInput {
  productId: number
  salePrice: number
  startsAt: string
  endsAt: string
  purchaseLimitPerUser: number
  totalQuantity: number
}

export async function createFlashSale(input: CreateFlashSaleInput): Promise<{ id: number }> {
  const response = await apiFetch('/api/admin/flash-sales', {
    method: 'POST',
    body: JSON.stringify(input),
  })
  return response.json()
}
