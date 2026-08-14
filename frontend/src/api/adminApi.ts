// Base file for admin API calls — shared types/helpers only.
// Tasks 9-11 each add their own endpoint functions here (dashboard, api-logs,
// orders, notifications) on top of this shared paging envelope.

import { apiFetch } from './httpClient'

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
