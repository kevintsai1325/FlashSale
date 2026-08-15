import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { RequireAuth } from './RequireAuth'
import { AuthContext } from './useAuth'

function renderWithAuth(isAuthenticated: boolean) {
  return render(
    <AuthContext.Provider value={{ isAuthenticated, role: null, isRestoring: false, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn(), finishRestoring: vi.fn() }}>
      <MemoryRouter initialEntries={['/protected']}>
        <Routes>
          <Route path="/login" element={<div>login page</div>} />
          <Route element={<RequireAuth />}>
            <Route path="/protected" element={<div>secret content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

describe('RequireAuth', () => {
  it('redirects to /login when not authenticated', () => {
    renderWithAuth(false)
    expect(screen.getByText('login page')).toBeInTheDocument()
  })

  it('renders the protected route when authenticated', () => {
    renderWithAuth(true)
    expect(screen.getByText('secret content')).toBeInTheDocument()
  })

  it('does not redirect while auth restore is in progress, even though isAuthenticated is still false', () => {
    render(
      <AuthContext.Provider value={{ isAuthenticated: false, role: null, isRestoring: true, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn(), finishRestoring: vi.fn() }}>
        <MemoryRouter initialEntries={['/protected']}>
          <Routes>
            <Route path="/login" element={<div>login page</div>} />
            <Route element={<RequireAuth />}>
              <Route path="/protected" element={<div>secret content</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    )

    expect(screen.queryByText('login page')).not.toBeInTheDocument()
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
  })
})
