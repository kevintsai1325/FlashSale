let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText }))
    throw new Error(problem.detail ?? '請求失敗')
  }
  return response
}
