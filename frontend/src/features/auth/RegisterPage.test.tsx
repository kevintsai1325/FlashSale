import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RegisterPage } from './RegisterPage'
import * as authApi from '../../api/authApi'

function LoginStub() {
  const location = useLocation()
  const email = (location.state as { email?: string } | null)?.email ?? ''
  return <div>login page, prefill: {email}</div>
}

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/login" element={<LoginStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('RegisterPage', () => {
  it('submits the form and calls the register API', async () => {
    const registerSpy = vi.spyOn(authApi, 'register').mockResolvedValue({ id: 1, email: 'a@example.com' })

    renderPage()

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'a@example.com' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret123' } })
    fireEvent.click(screen.getByRole('button', { name: /register/i }))

    await waitFor(() => expect(registerSpy).toHaveBeenCalledWith('a@example.com', 'secret123'))
  })

  it('navigates to /login with the registered email in location state', async () => {
    vi.spyOn(authApi, 'register').mockResolvedValue({ id: 1, email: 'a@example.com' })

    renderPage()

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'a@example.com' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret123' } })
    fireEvent.click(screen.getByRole('button', { name: /register/i }))

    await waitFor(() => expect(screen.getByText('login page, prefill: a@example.com')).toBeInTheDocument())
  })

  it('shows the validation message instead of letting native browser validation swallow the submit', async () => {
    const registerSpy = vi.spyOn(authApi, 'register')

    renderPage()

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'not-an-email' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: /register/i }))

    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(0))
    expect(registerSpy).not.toHaveBeenCalled()
  })
})
