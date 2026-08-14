import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminOrderDetailPage } from './AdminOrderDetailPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/admin/orders/7']}>
        <Routes>
          <Route path="/admin/orders/:orderId" element={<AdminOrderDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const detail: adminApi.AdminOrderDetail = {
  id: 7,
  orderNo: 'ORD-7',
  userId: 42,
  totalAmount: 199.5,
  status: 'PAID',
  paymentDueAt: null,
  createdAt: '2026-08-14T13:00:00Z',
  items: [{ productId: 501, quantity: 2, unitPrice: 99.75 }],
  purchaseRequest: { requestId: '11111111-1111-1111-1111-111111111111', status: 'SUCCEEDED', orderId: 7 },
  statusHistory: [
    { fromStatus: null, toStatus: 'PENDING_PAYMENT', changedAt: '2026-08-14T13:00:01Z' },
    { fromStatus: 'PENDING_PAYMENT', toStatus: 'PAID', changedAt: '2026-08-14T13:02:00Z' },
  ],
  relatedApiLogs: [
    {
      id: 1,
      occurredAt: '2026-08-14T13:00:05Z',
      method: 'POST',
      pathTemplate: '/api/purchase-requests',
      status: 201,
      userId: 42,
      requestId: 'req-1',
      traceId: 'trace-abc',
      durationMs: 8,
      clientIp: '127.0.0.1',
      userAgent: 'vitest',
      errorCode: null,
    },
  ],
}

describe('AdminOrderDetailPage', () => {
  it('renders order info, status history timeline, and related-log hints with non-exact-match copy', async () => {
    vi.spyOn(adminApi, 'getAdminOrderDetail').mockResolvedValue(detail)
    renderPage()

    await waitFor(() => expect(screen.getByText('ORD-7')).toBeInTheDocument())
    expect(screen.getByText('已付款')).toBeInTheDocument()

    // status history timeline
    expect(screen.getByText(/PENDING_PAYMENT → PAID/)).toBeInTheDocument()

    // related-logs section uses non-exact-match copy, not "the request that created this order"
    expect(screen.getByText('此時段附近的 API 紀錄')).toBeInTheDocument()
    expect(screen.queryByText(/建立此訂單的請求/)).not.toBeInTheDocument()

    // related log row links to the api-logs page filtered by its traceId
    expect(screen.getByRole('link', { name: /trace-abc/ })).toHaveAttribute(
      'href',
      '/admin/api-logs?traceId=trace-abc'
    )
  })

  it('shows a fallback when there is no linked purchase request', async () => {
    vi.spyOn(adminApi, 'getAdminOrderDetail').mockResolvedValue({ ...detail, purchaseRequest: null })
    renderPage()

    await waitFor(() => expect(screen.getByText('ORD-7')).toBeInTheDocument())
    expect(screen.getByText('無關聯搶購請求')).toBeInTheDocument()
  })
})
