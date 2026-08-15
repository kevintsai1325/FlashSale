import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'
import { AuthContext } from './useAuth'

function renderPage(initialEntries: Array<{ pathname: string; state?: unknown }>) {
  const queryClient = new QueryClient()
  return render(
    <AuthContext.Provider value={{ isAuthenticated: false, role: null, isRestoring: false, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn(), finishRestoring: vi.fn() }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={initialEntries}>
          <LoginPage />
        </MemoryRouter>
      </QueryClientProvider>
    </AuthContext.Provider>
  )
}

describe('LoginPage', () => {
  it('pre-fills the email field when location.state carries an email', () => {
    renderPage([{ pathname: '/login', state: { email: 'prefill@example.com' } }])

    expect((screen.getByLabelText(/email/i) as HTMLInputElement).value).toBe('prefill@example.com')
  })

  it('leaves the email field empty when there is no location state', () => {
    renderPage([{ pathname: '/login' }])

    expect((screen.getByLabelText(/email/i) as HTMLInputElement).value).toBe('')
  })
})
