import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { PurchaseStatusPage } from './PurchaseStatusPage'
import * as purchaseApi from '../../api/purchaseApi'
import { AuthProvider } from '../auth/useAuth'

function renderPage(requestId = 'req-1') {
  const queryClient = new QueryClient()
  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/purchase-requests/${requestId}`]}>
          <Routes>
            <Route path="/purchase-requests/:requestId" element={<PurchaseStatusPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </AuthProvider>
  )
}

describe('PurchaseStatusPage', () => {
  it('shows a pending message while processing', async () => {
    vi.spyOn(purchaseApi, 'getPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'PENDING', orderId: null })
    renderPage()
    await waitFor(() => expect(screen.getByText(/處理中/)).toBeInTheDocument())
  })

  it('shows a success message with a link to the order when succeeded', async () => {
    vi.spyOn(purchaseApi, 'getPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'SUCCEEDED', orderId: 42 })
    renderPage()
    await waitFor(() => expect(screen.getByText(/搶購成功/)).toBeInTheDocument())
    expect(screen.getByRole('link', { name: /查看訂單/ })).toHaveAttribute('href', '/orders/42')
  })

  it('shows a sold-out message', async () => {
    vi.spyOn(purchaseApi, 'getPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'SOLD_OUT', orderId: null })
    renderPage()
    await waitFor(() => expect(screen.getByText(/已售完/)).toBeInTheDocument())
  })
})
