export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly instance: string | null

  constructor(
    status: number,
    code: string | null,
    detail: string,
    instance: string | null,
  ) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.instance = instance
  }
}
