import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import * as authApi from '../../api/authApi'

interface AuthContextValue {
  isAuthenticated: boolean
  role: 'USER' | 'ADMIN' | null
  /**
   * `true` from initial mount until the app's one-time auth-restore attempt (the
   * `authApi.refresh()` call kicked off in `App.tsx`) settles, whether it succeeds or fails.
   * Consumers that redirect based on `role`/`isAuthenticated` (e.g. `RequireAdmin`) must wait for
   * this to become `false` before treating an unauthenticated/non-admin state as final — otherwise
   * a genuinely logged-in user gets redirected away on every page refresh or deep link, since
   * `role`/`isAuthenticated` start out in their "logged out" state until the refresh call resolves.
   */
  isRestoring: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  markAuthenticated: (data: { accessToken: string }) => void
  finishRestoring: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

function decodeRole(accessToken: string): 'USER' | 'ADMIN' | null {
  try {
    const payload = JSON.parse(atob(accessToken.split('.')[1]))
    return payload.role === 'ADMIN' ? 'ADMIN' : payload.role === 'USER' ? 'USER' : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [role, setRole] = useState<'USER' | 'ADMIN' | null>(null)
  const [isRestoring, setIsRestoring] = useState(true)

  const markAuthenticated = useCallback((data: { accessToken: string }) => {
    setIsAuthenticated(true)
    setRole(decodeRole(data.accessToken))
  }, [])

  const finishRestoring = useCallback(() => {
    setIsRestoring(false)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const data = await authApi.login(email, password)
    markAuthenticated(data)
  }, [markAuthenticated])

  const logout = useCallback(async () => {
    await authApi.logout()
    setIsAuthenticated(false)
    setRole(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{ isAuthenticated, role, isRestoring, login, logout, markAuthenticated, finishRestoring }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
