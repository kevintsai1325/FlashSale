import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { configureAuthRecovery } from '../../api/httpClient'
import * as authApi from '../../api/authApi'
import { AuthProvider, useAuth } from './useAuth'

vi.mock('../../api/httpClient', () => ({
  configureAuthRecovery: vi.fn(),
  setAccessToken: vi.fn(),
}))

function AuthProbe() {
  const auth = useAuth()
  return <div>{auth.isAuthenticated ? 'authenticated' : 'anonymous'}</div>
}

describe('AuthProvider', () => {
  it('clears authentication when recovery reports an expired session', async () => {
    vi.spyOn(authApi, 'refresh').mockResolvedValue({ accessToken: 'x.eyJyb2xlIjoiVVNFUiJ9.x' })
    render(<AuthProvider><AuthProbe /></AuthProvider>)

    const recovery = vi.mocked(configureAuthRecovery).mock.calls[0][0]
    await act(() => recovery!.refresh())
    expect(screen.getByText('authenticated')).toBeInTheDocument()

    act(() => recovery!.onSessionExpired())
    expect(screen.getByText('anonymous')).toBeInTheDocument()
  })
})
