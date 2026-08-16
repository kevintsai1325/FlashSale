import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './useAuth'

export function RequireAuth() {
  const { isAuthenticated, isRestoring } = useAuth()
  const location = useLocation()

  if (isRestoring) {
    // Same reasoning as RequireAdmin: App.tsx's authApi.refresh() call hasn't settled yet, so
    // isAuthenticated still holds its initial "logged out" value even for a genuinely logged-in
    // user. Render nothing rather than redirecting, otherwise every page refresh bounces a real
    // logged-in user back to /login before refresh() has a chance to resolve.
    return null
  }
  if (!isAuthenticated) {
    return <Navigate
      to="/login"
      state={{ from: location.pathname + location.search + location.hash }}
      replace
    />
  }
  return <Outlet />
}
