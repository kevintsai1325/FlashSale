import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'
import * as authApi from './api/authApi'
import { AuthProvider, useAuth } from './features/auth/useAuth'

const queryClient = new QueryClient()

function AppContent() {
  const { markAuthenticated } = useAuth()

  useEffect(() => {
    authApi.refresh().then(markAuthenticated).catch(() => {
      // No valid refresh cookie (never logged in, or it expired) — stay logged out.
    })
  }, [markAuthenticated])

  return (
    <>
      <h1>FlashSale</h1>
      <RouterProvider router={router} />
    </>
  )
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
