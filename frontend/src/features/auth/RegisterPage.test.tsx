import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RegisterPage } from './RegisterPage'
import * as authApi from '../../api/authApi'

describe('RegisterPage', () => {
  it('submits the form and calls the register API', async () => {
    const registerSpy = vi.spyOn(authApi, 'register').mockResolvedValue({ id: 1, email: 'a@example.com' })
    const queryClient = new QueryClient()

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <RegisterPage />
        </MemoryRouter>
      </QueryClientProvider>
    )

    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'a@example.com' } })
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret123' } })
    fireEvent.click(screen.getByRole('button', { name: /register/i }))

    await waitFor(() => expect(registerSpy).toHaveBeenCalledWith('a@example.com', 'secret123'))
  })
})
