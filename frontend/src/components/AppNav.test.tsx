import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AppNav } from './AppNav'
import { AuthContext } from '../features/auth/useAuth'
import * as adminApi from '../api/adminApi'

function renderNav(role: 'USER' | 'ADMIN' | null) {
  const queryClient = new QueryClient()
  return render(
    <AuthContext.Provider
      value={{
        isAuthenticated: role !== null,
        role,
        login: vi.fn(),
        logout: vi.fn(),
        markAuthenticated: vi.fn(),
      }}
    >
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <AppNav />
        </MemoryRouter>
      </QueryClientProvider>
    </AuthContext.Provider>
  )
}

describe('AppNav', () => {
  it('does not render the notification bell for a non-admin user', () => {
    const spy = vi.spyOn(adminApi, 'getUnreadCount').mockResolvedValue(0)
    renderNav('USER')

    expect(screen.queryByLabelText('通知中心')).not.toBeInTheDocument()
    expect(spy).not.toHaveBeenCalled()
  })

  it('renders a bell linking to /admin/notifications for an admin user, with no badge when unread count is 0', async () => {
    vi.spyOn(adminApi, 'getUnreadCount').mockResolvedValue(0)
    renderNav('ADMIN')

    const bell = await screen.findByLabelText('通知中心')
    expect(bell).toHaveAttribute('href', '/admin/notifications')
    expect(screen.queryByTestId('nav-bell-badge')).not.toBeInTheDocument()
  })

  it('shows an unread-count badge on the bell for an admin user when count > 0', async () => {
    vi.spyOn(adminApi, 'getUnreadCount').mockResolvedValue(3)
    renderNav('ADMIN')

    await waitFor(() => expect(screen.getByTestId('nav-bell-badge')).toHaveTextContent('3'))
  })
})
