import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function RequireAdmin() {
  const { role } = useAuth()
  if (role !== 'ADMIN') {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
