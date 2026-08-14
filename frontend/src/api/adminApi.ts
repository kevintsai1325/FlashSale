// Base file for admin API calls — shared types/helpers only.
// Tasks 9-11 each add their own endpoint functions here (dashboard, api-logs,
// orders, notifications) on top of this shared paging envelope.

/** Mirrors the backend's `PagedResult<T>` response envelope for admin list endpoints. */
export interface PagedResult<T> {
  content: T[]
  totalElements: number
  page: number
  size: number
}
