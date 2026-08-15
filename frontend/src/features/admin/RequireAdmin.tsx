import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function RequireAdmin() {
  const { role, isRestoring } = useAuth()
  if (isRestoring) {
    // Auth restore (App.tsx's authApi.refresh() call) hasn't settled yet — role/isAuthenticated
    // still hold their initial "logged out" values at this point even for a genuinely logged-in
    // admin. Render nothing rather than redirecting, otherwise every page refresh/deep link into
    // /admin/* bounces a real admin back to / before refresh() has a chance to resolve.
    return null
  }
  if (role !== 'ADMIN') {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
