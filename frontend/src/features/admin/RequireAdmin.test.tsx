import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RequireAdmin } from './RequireAdmin'
import { AuthContext } from '../auth/useAuth'

function renderWithRole(role: 'USER' | 'ADMIN' | null, isRestoring = false) {
  return render(
    <AuthContext.Provider
      value={{
        isAuthenticated: role !== null,
        role,
        isRestoring,
        login: vi.fn(),
        logout: vi.fn(),
        markAuthenticated: vi.fn(),
        finishRestoring: vi.fn(),
      }}
    >
      <MemoryRouter initialEntries={['/admin']}>
        <Routes>
          <Route path="/" element={<div>home page</div>} />
          <Route element={<RequireAdmin />}>
            <Route path="/admin" element={<div>admin content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

describe('RequireAdmin', () => {
  it('redirects to / when not logged in', () => {
    renderWithRole(null)
    expect(screen.getByText('home page')).toBeInTheDocument()
  })

  it('redirects to / when logged in as USER', () => {
    renderWithRole('USER')
    expect(screen.getByText('home page')).toBeInTheDocument()
  })

  it('renders the nested route when logged in as ADMIN', () => {
    renderWithRole('ADMIN')
    expect(screen.getByText('admin content')).toBeInTheDocument()
  })

  it('does not redirect while auth restore is in progress, even for a not-yet-known role', () => {
    renderWithRole(null, true)
    expect(screen.queryByText('home page')).not.toBeInTheDocument()
    expect(screen.queryByText('admin content')).not.toBeInTheDocument()
  })

  it('renders the nested route once restoring finishes and role resolves to ADMIN', () => {
    const { rerender } = render(
      <AuthContext.Provider
        value={{
          isAuthenticated: false,
          role: null,
          isRestoring: true,
          login: vi.fn(),
          logout: vi.fn(),
          markAuthenticated: vi.fn(),
          finishRestoring: vi.fn(),
        }}
      >
        <MemoryRouter initialEntries={['/admin']}>
          <Routes>
            <Route path="/" element={<div>home page</div>} />
            <Route element={<RequireAdmin />}>
              <Route path="/admin" element={<div>admin content</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    )
    expect(screen.queryByText('admin content')).not.toBeInTheDocument()

    rerender(
      <AuthContext.Provider
        value={{
          isAuthenticated: true,
          role: 'ADMIN',
          isRestoring: false,
          login: vi.fn(),
          logout: vi.fn(),
          markAuthenticated: vi.fn(),
          finishRestoring: vi.fn(),
        }}
      >
        <MemoryRouter initialEntries={['/admin']}>
          <Routes>
            <Route path="/" element={<div>home page</div>} />
            <Route element={<RequireAdmin />}>
              <Route path="/admin" element={<div>admin content</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    )
    expect(screen.getByText('admin content')).toBeInTheDocument()
  })
})
