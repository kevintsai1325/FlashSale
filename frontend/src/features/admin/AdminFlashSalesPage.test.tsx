import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminFlashSalesPage } from './AdminFlashSalesPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminFlashSalesPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AdminFlashSalesPage', () => {
  it('lists existing flash sales and prompts to create a product when none exist', async () => {
    vi.spyOn(adminApi, 'listAdminFlashSales').mockResolvedValue([
      { id: 1, productName: 'Limited Sneakers', salePrice: 9.99, startsAt: '2026-08-15T10:00:00Z', endsAt: '2026-08-15T12:00:00Z', status: 'ENDED' },
    ])
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([])

    renderPage()

    await waitFor(() => expect(screen.getByText('Limited Sneakers')).toBeInTheDocument())
    expect(screen.getByText('請先建立商品')).toBeInTheDocument()
  })

  it('submits the create form when a product is available', async () => {
    vi.spyOn(adminApi, 'listAdminFlashSales').mockResolvedValue([])
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([
      { id: 5, name: 'Limited Sneakers', description: null },
    ])
    const createSpy = vi.spyOn(adminApi, 'createFlashSale').mockResolvedValue({ id: 10 })

    renderPage()

    // Wait for the product option itself (not just the <select> element, which renders
    // immediately with only the placeholder option) — otherwise fireEvent.change fires before
    // React Query resolves listProducts() and the value assignment silently no-ops because no
    // matching <option> exists yet.
    await waitFor(() => expect(screen.getByRole('option', { name: 'Limited Sneakers' })).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText('商品'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('售價'), { target: { value: '9.99' } })
    fireEvent.change(screen.getByLabelText('開始時間'), { target: { value: '2026-08-20T10:00' } })
    fireEvent.change(screen.getByLabelText('結束時間'), { target: { value: '2026-08-20T12:00' } })
    fireEvent.change(screen.getByLabelText('每人限購'), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('庫存數量'), { target: { value: '50' } })
    fireEvent.click(screen.getByRole('button', { name: '新增搶購活動' }))

    await waitFor(() => expect(createSpy).toHaveBeenCalled())
    const input = createSpy.mock.calls[0][0]
    expect(input.productId).toBe(5)
    expect(input.salePrice).toBe(9.99)
    // Asserting the exact converted value would make this test depend on the test runner's
    // local timezone; asserting it round-trips through Date parsing to the same ISO string is
    // enough to confirm the conversion actually happened (a raw "2026-08-20T10:00" would fail
    // this, since new Date() on that exact string doesn't normally equal it after
    // .toISOString()).
    expect(new Date(input.startsAt).toISOString()).toBe(input.startsAt)
    expect(new Date(input.endsAt).toISOString()).toBe(input.endsAt)
    expect(input.purchaseLimitPerUser).toBe(1)
    expect(input.totalQuantity).toBe(50)
  })
})
