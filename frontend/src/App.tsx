import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'
import * as authApi from './api/authApi'
import { AuthProvider, useAuth } from './features/auth/useAuth'

const queryClient = new QueryClient()

function AppContent() {
  const { markAuthenticated, finishRestoring } = useAuth()

  useEffect(() => {
    authApi.refresh().then(markAuthenticated).catch(() => {
      // No valid refresh cookie (never logged in, or it expired) — stay logged out.
    }).finally(finishRestoring)
  }, [markAuthenticated, finishRestoring])

  return <RouterProvider router={router} />
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AppContent />
      </AuthProvider>
    </QueryClientProvider>
  )
}
