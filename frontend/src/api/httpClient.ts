import { ApiError } from './ApiError'

let accessToken: string | null = null

interface AuthRecovery {
  refresh: () => Promise<void>
  onSessionExpired: () => void
}

interface ApiFetchConfig {
  skipAuthRecovery?: boolean
}

interface ProblemDetailsPayload {
  code?: unknown
  detail?: unknown
  instance?: unknown
}

let authRecovery: AuthRecovery | null = null
let refreshInFlight: Promise<void> | null = null
let sessionExpirationNotified = false

export function configureAuthRecovery(config: AuthRecovery | null) {
  authRecovery = config
  refreshInFlight = null
  sessionExpirationNotified = false
}

export function setAccessToken(token: string | null) {
  accessToken = token
}

function notifySessionExpired() {
  if (!sessionExpirationNotified) {
    sessionExpirationNotified = true
    authRecovery?.onSessionExpired()
  }
}

async function recoverAuthentication(): Promise<void> {
  if (!authRecovery) {
    throw new Error('Authentication recovery is not configured')
  }
  if (!refreshInFlight) {
    refreshInFlight = authRecovery.refresh()
      .then(() => {
        sessionExpirationNotified = false
      })
      .catch((error: unknown) => {
        notifySessionExpired()
        throw error
      })
      .finally(() => {
        setTimeout(() => {
          refreshInFlight = null
        }, 0)
      })
  }
  return refreshInFlight
}

async function requestOnce(path: string, options: RequestInit): Promise<Response> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (!response.ok) {
    const problem: ProblemDetailsPayload = await response.json().catch(() => ({}))
    const detail = typeof problem.detail === 'string'
      ? problem.detail
      : `請求失敗（${response.status}）`
    throw new ApiError(
      response.status,
      typeof problem.code === 'string' ? problem.code : null,
      detail,
      typeof problem.instance === 'string' ? problem.instance : null,
    )
  }
  return response
}

export async function apiFetch(
  path: string,
  options: RequestInit = {},
  config: ApiFetchConfig = {},
): Promise<Response> {
  try {
    return await requestOnce(path, options)
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401 ||
        config.skipAuthRecovery || !authRecovery) {
      throw error
    }

    await recoverAuthentication()
    try {
      return await requestOnce(path, options)
    } catch (retryError) {
      if (retryError instanceof ApiError && retryError.status === 401) {
        notifySessionExpired()
      }
      throw retryError
    }
  }
}
