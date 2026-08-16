import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { FlashSaleListPage } from './FlashSaleListPage'
import * as flashSaleApi from '../../api/flashSaleApi'
import { AuthProvider } from '../auth/useAuth'

describe('FlashSaleListPage', () => {
  it('renders fetched flash sales', async () => {
    vi.spyOn(flashSaleApi, 'listFlashSales').mockResolvedValue([
      { id: 1, productName: 'Limited Sneakers', salePrice: 9.99, startsAt: new Date().toISOString(), endsAt: new Date().toISOString(), status: 'ACTIVE' },
      { id: 2, productName: 'Future Watch', salePrice: 19.99, startsAt: new Date().toISOString(), endsAt: new Date().toISOString(), status: 'SCHEDULED' },
      { id: 3, productName: 'Past Bag', salePrice: 29.99, startsAt: new Date().toISOString(), endsAt: new Date().toISOString(), status: 'ENDED' },
    ])
    const queryClient = new QueryClient()

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <FlashSaleListPage />
          </MemoryRouter>
        </QueryClientProvider>
      </AuthProvider>
    )

    await waitFor(() => expect(screen.getByText('Limited Sneakers')).toBeInTheDocument())
    expect(within(screen.getByRole('heading', { name: '現正開賣' }).closest('section')!).getByText('Limited Sneakers')).toBeInTheDocument()
    expect(within(screen.getByRole('heading', { name: '即將開賣' }).closest('section')!).getByText('Future Watch')).toBeInTheDocument()
    expect(within(screen.getByRole('heading', { name: '已結束' }).closest('section')!).getByText('Past Bag')).toBeInTheDocument()
    expect(document.querySelectorAll('.stub-edge[aria-hidden="true"]')).toHaveLength(3)
  })
})
