import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminDashboardPage } from './AdminDashboardPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminDashboardPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const summary: adminApi.DashboardSummary = {
  totalPurchaseRequests: 120,
  succeededPurchaseRequests: 100,
  ordersByStatus: { PENDING_PAYMENT: 3, PAID: 5, CANCELLED: 2 },
  totalPaidAmount: 4999.5,
  inventoryByFlashSaleId: {
    '501': { totalQuantity: 100, availableQuantity: 40, reservedQuantity: 10, soldQuantity: 50 },
  },
}

const trends: adminApi.DashboardTrends = {
  lastHour: [
    { bucketStart: '2026-08-14T13:00:00Z', purchaseRequestCount: 4, orderCount: 3 },
    { bucketStart: '2026-08-14T13:05:00Z', purchaseRequestCount: 6, orderCount: 5 },
  ],
  last24Hours: [
    { bucketStart: '2026-08-13T14:00:00Z', purchaseRequestCount: 40, orderCount: 30 },
    { bucketStart: '2026-08-14T13:00:00Z', purchaseRequestCount: 50, orderCount: 45 },
  ],
}

describe('AdminDashboardPage', () => {
  it('renders summary stats and trend charts', async () => {
    vi.spyOn(adminApi, 'getDashboardSummary').mockResolvedValue(summary)
    vi.spyOn(adminApi, 'getDashboardTrends').mockResolvedValue(trends)

    renderPage()

    await waitFor(() => expect(screen.getByText('120')).toBeInTheDocument())
    // "100" is rendered twice: succeededPurchaseRequests (stat card) and the inventory summary's
    // totalQuantity (below) — assert on both occurrences rather than a single unique element.
    expect(screen.getAllByText('100')).toHaveLength(2)
    expect(screen.getByText('$4999.50')).toBeInTheDocument()

    const charts = await screen.findAllByTestId('dashboard-trend-chart')
    expect(charts).toHaveLength(2)

    expect(screen.getByText('搶購活動 #501')).toBeInTheDocument()
    expect(screen.getByText('40')).toBeInTheDocument()
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('50')).toBeInTheDocument()
  })
})
