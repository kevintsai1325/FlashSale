import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { OrderDetailPage } from './OrderDetailPage'
import * as orderApi from '../../api/orderApi'
import { AuthProvider } from '../auth/useAuth'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/orders/1']}>
          <Routes>
            <Route path="/orders/:orderId" element={<OrderDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </AuthProvider>
  )
}

const pendingOrder = {
  id: 1,
  orderNo: 'ORD-1',
  totalAmount: 9.99,
  status: 'PENDING_PAYMENT',
  paymentDueAt: new Date().toISOString(),
  items: [{ productId: 2, productName: '限量鍵盤', quantity: 3, unitPrice: 499 }],
}
const paidOrder = { ...pendingOrder, status: 'PAID' }

describe('OrderDetailPage', () => {
  it('shows each purchased product with its quantity and order-time unit price', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    renderPage()

    expect(await screen.findByText('限量鍵盤')).toBeInTheDocument()
    expect(screen.getByText(/3\s*×\s*\$499\.00/)).toBeInTheDocument()
  })

  it('shows payment and cancel actions while PENDING_PAYMENT', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /模擬付款成功/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /模擬付款失敗/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /取消訂單/ })).toBeInTheDocument()
  })

  it('does not show actions once PAID', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(paidOrder)
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /模擬付款成功/ })).not.toBeInTheDocument()
  })

  it('submits SUCCESS payment and reflects the updated status', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    const submitSpy = vi.spyOn(orderApi, 'submitPayment').mockResolvedValue(paidOrder)
    renderPage()

    await waitFor(() => screen.getByRole('button', { name: /模擬付款成功/ }))
    fireEvent.click(screen.getByRole('button', { name: /模擬付款成功/ }))

    await waitFor(() => expect(submitSpy).toHaveBeenCalledWith(1, 'SUCCESS'))
    await waitFor(() => expect(screen.getByText(/已付款/)).toBeInTheDocument())
  })

  it('cancels the order and reflects the updated status', async () => {
    vi.spyOn(orderApi, 'getOrder').mockResolvedValue(pendingOrder)
    const cancelSpy = vi.spyOn(orderApi, 'cancelOrder').mockResolvedValue({ ...pendingOrder, status: 'CANCELLED' })
    renderPage()

    await waitFor(() => screen.getByRole('button', { name: /取消訂單/ }))
    fireEvent.click(screen.getByRole('button', { name: /取消訂單/ }))

    await waitFor(() => expect(cancelSpy).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.getByText(/已取消/)).toBeInTheDocument())
  })
})
