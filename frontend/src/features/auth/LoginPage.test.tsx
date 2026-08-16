import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'
import { AuthContext } from './useAuth'

function LocationProbe() {
  const location = useLocation()
  return <div>{location.pathname + location.search + location.hash}</div>
}

function renderPage(
  initialEntries: Array<{ pathname: string; state?: unknown }>,
  login: (email: string, password: string) => Promise<void> = vi.fn(),
) {
  const queryClient = new QueryClient()
  return render(
    <AuthContext.Provider value={{ isAuthenticated: false, role: null, isRestoring: false, login, logout: vi.fn(), markAuthenticated: vi.fn(), finishRestoring: vi.fn() }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={initialEntries}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="*" element={<LocationProbe />} />
          </Routes>
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

  it('shows the validation message instead of letting native browser validation swallow the submit', async () => {
    renderPage([{ pathname: '/login' }])

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'not-an-email' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: /login/i }))

    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(0))
  })

  it('returns to the full internal URL after a successful login', async () => {
    const login = vi.fn().mockResolvedValue(undefined)
    renderPage([{
      pathname: '/login',
      state: { from: '/orders?tab=open#latest' },
    }], login)

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'buyer@example.com' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret123' } })
    fireEvent.click(screen.getByRole('button', { name: /login/i }))

    expect(await screen.findByText('/orders?tab=open#latest')).toBeInTheDocument()
  })

  it('falls back to the home route when the return URL is external', async () => {
    const login = vi.fn().mockResolvedValue(undefined)
    renderPage([{
      pathname: '/login',
      state: { from: 'https://evil.example/steal' },
    }], login)

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'buyer@example.com' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret123' } })
    fireEvent.click(screen.getByRole('button', { name: /login/i }))

    expect(await screen.findByText('/')).toBeInTheDocument()
  })
})
