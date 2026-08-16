import { afterEach, describe, expect, it, vi } from 'vitest'
import * as httpClientModule from './httpClient'
import { apiFetch, setAccessToken } from './httpClient'

interface AuthRecoveryTestApi {
  configureAuthRecovery(config: {
    refresh: () => Promise<void>
    onSessionExpired: () => void
  } | null): void
}

const authRecoveryApi = httpClientModule as typeof httpClientModule & AuthRecoveryTestApi

describe('apiFetch', () => {
  afterEach(() => {
    setAccessToken(null)
    authRecoveryApi.configureAuthRecovery?.(null)
    vi.unstubAllGlobals()
  })

  it('preserves the Problem Details status, code, detail, and instance', async () => {
    const refresh = vi.fn()
    const onSessionExpired = vi.fn()
    authRecoveryApi.configureAuthRecovery({ refresh, onSessionExpired })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      status: 403,
      code: 'ACCESS_DENIED',
      detail: '您沒有權限存取此資源',
      instance: '/api/admin',
    }), {
      status: 403,
      headers: { 'Content-Type': 'application/problem+json' },
    })))

    await expect(apiFetch('/api/admin')).rejects.toMatchObject({
      name: 'ApiError',
      status: 403,
      code: 'ACCESS_DENIED',
      message: '您沒有權限存取此資源',
      instance: '/api/admin',
    })
    expect(refresh).not.toHaveBeenCalled()
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('uses a safe status-based message for a non-JSON error response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('internal stack trace', {
      status: 500,
      statusText: 'Internal Server Error',
    })))

    await expect(apiFetch('/api/failure')).rejects.toMatchObject({
      name: 'ApiError',
      status: 500,
      code: null,
      message: '請求失敗（500）',
      instance: null,
    })
  })

  it('shares one refresh across concurrent 401 responses and retries each request once', async () => {
    const fetchMock = vi.fn().mockImplementation((_path: string, options: RequestInit) => {
      const authorization = new Headers(options.headers).get('Authorization')
      return Promise.resolve(authorization === 'Bearer fresh-token'
        ? new Response(null, { status: 200 })
        : new Response(JSON.stringify({ code: 'UNAUTHENTICATED', detail: '需要登入' }), {
            status: 401,
            headers: { 'Content-Type': 'application/problem+json' },
          }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const refresh = vi.fn().mockImplementation(async () => {
      setAccessToken('fresh-token')
    })
    const onSessionExpired = vi.fn()
    authRecoveryApi.configureAuthRecovery({ refresh, onSessionExpired })

    const responses = await Promise.all([apiFetch('/api/a'), apiFetch('/api/b')])

    expect(responses.map((response) => response.status)).toEqual([200, 200])
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('expires the session once when a shared refresh fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'UNAUTHENTICATED',
      detail: '需要登入',
    }), {
      status: 401,
      headers: { 'Content-Type': 'application/problem+json' },
    })))
    const refresh = vi.fn().mockRejectedValue(new Error('refresh rejected'))
    const onSessionExpired = vi.fn()
    authRecoveryApi.configureAuthRecovery({ refresh, onSessionExpired })

    const results = await Promise.allSettled([apiFetch('/api/a'), apiFetch('/api/b')])

    expect(results.map((result) => result.status)).toEqual(['rejected', 'rejected'])
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(onSessionExpired).toHaveBeenCalledTimes(1)
  })
})
