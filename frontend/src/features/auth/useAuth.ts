import { useState, useCallback } from 'react'
import * as authApi from '../../api/authApi'

export function useAuth() {
  const [isAuthenticated, setIsAuthenticated] = useState(false)

  const login = useCallback(async (email: string, password: string) => {
    await authApi.login(email, password)
    setIsAuthenticated(true)
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout()
    setIsAuthenticated(false)
  }, [])

  return { isAuthenticated, login, logout }
}
