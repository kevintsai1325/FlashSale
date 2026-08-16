import { apiFetch, setAccessToken } from './httpClient'

export async function register(email: string, password: string) {
  const response = await apiFetch('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  }, { skipAuthRecovery: true })
  return response.json() as Promise<{ id: number; email: string }>
}

export async function login(email: string, password: string) {
  const response = await apiFetch('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  }, { skipAuthRecovery: true })
  const data = (await response.json()) as { accessToken: string }
  setAccessToken(data.accessToken)
  return data
}

export async function refresh() {
  const response = await apiFetch(
    '/api/auth/refresh',
    { method: 'POST' },
    { skipAuthRecovery: true },
  )
  const data = (await response.json()) as { accessToken: string }
  setAccessToken(data.accessToken)
  return data
}

export async function logout() {
  await apiFetch('/api/auth/logout', { method: 'POST' }, { skipAuthRecovery: true })
  setAccessToken(null)
}
