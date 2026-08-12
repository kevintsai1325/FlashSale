import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'
import * as authApi from './api/authApi'

const queryClient = new QueryClient()

export default function App() {
  useEffect(() => {
    authApi.refresh().catch(() => {
      // No valid refresh cookie (never logged in, or it expired) — stay logged out.
    })
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      <h1>FlashSale</h1>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
