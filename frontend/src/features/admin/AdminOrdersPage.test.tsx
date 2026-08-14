import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminOrdersPage } from './AdminOrdersPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminOrdersPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const page1: adminApi.PagedResult<adminApi.AdminOrderSummary> = {
  content: [
    { id: 1, orderNo: 'ORD-1', userId: 42, totalAmount: 9.99, status: 'PAID', createdAt: '2026-08-14T13:00:00Z' },
  ],
  totalElements: 1,
  page: 0,
  size: 20,
}

describe('AdminOrdersPage', () => {
  it('renders fetched orders with a status pill and links to the detail page', async () => {
    vi.spyOn(adminApi, 'listAdminOrders').mockResolvedValue(page1)
    renderPage()

    await waitFor(() => expect(screen.getByText('ORD-1')).toBeInTheDocument())
    expect(screen.getByText('已付款')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ORD-1/ })).toHaveAttribute('href', '/admin/orders/1')
  })
})
