import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { MyOrdersPage } from './MyOrdersPage'
import * as orderApi from '../../api/orderApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MyOrdersPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('MyOrdersPage', () => {
  it('renders fetched orders', async () => {
    vi.spyOn(orderApi, 'listMyOrders').mockResolvedValue([
      { id: 1, orderNo: 'ORD-1', totalAmount: 9.99, status: 'PAID' },
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText(/ORD-1/)).toBeInTheDocument())
  })

  it('shows an empty state when there are no orders', async () => {
    vi.spyOn(orderApi, 'listMyOrders').mockResolvedValue([])
    renderPage()
    await waitFor(() => expect(screen.getByText('尚無訂單')).toBeInTheDocument())
  })
})
