import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { MyOrdersPage } from './MyOrdersPage'
import * as orderApi from '../../api/orderApi'
import { AuthProvider } from '../auth/useAuth'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <MyOrdersPage />
        </MemoryRouter>
      </QueryClientProvider>
    </AuthProvider>
  )
}

describe('MyOrdersPage', () => {
  it('renders fetched orders', async () => {
    vi.spyOn(orderApi, 'listMyOrders').mockResolvedValue([
      {
        id: 1,
        orderNo: 'ORD-1',
        totalAmount: 9.99,
        status: 'PAID',
        items: [{ productId: 2, productName: '限量鍵盤', quantity: 3, unitPrice: 499 }],
      },
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
    expect(await screen.findByText('限量鍵盤')).toBeInTheDocument()
    expect(screen.getByText(/3\s*×\s*\$499\.00/)).toBeInTheDocument()
  })

  it('shows an empty state when there are no orders', async () => {
    vi.spyOn(orderApi, 'listMyOrders').mockResolvedValue([])
    renderPage()
    await waitFor(() => expect(screen.getByText('尚無訂單')).toBeInTheDocument())
  })
})
