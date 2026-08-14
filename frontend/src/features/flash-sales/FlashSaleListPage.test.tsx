import { render, screen, waitFor } from '@testing-library/react'
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
  })
})
