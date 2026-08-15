import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { FlashSaleDetailPage } from './FlashSaleDetailPage'
import * as flashSaleApi from '../../api/flashSaleApi'
import * as purchaseApi from '../../api/purchaseApi'
import { AuthContext } from '../auth/useAuth'

const activeSale = {
  id: 1,
  productName: 'Limited Sneakers',
  productDescription: 'desc',
  salePrice: 9.99,
  startsAt: new Date().toISOString(),
  endsAt: new Date().toISOString(),
  purchaseLimitPerUser: 3,
  totalQuantity: 10,
  availableQuantity: 10,
  status: 'ACTIVE',
}

function renderPage(isAuthenticated: boolean) {
  const queryClient = new QueryClient()
  return render(
    <AuthContext.Provider value={{ isAuthenticated, role: null, isRestoring: false, login: vi.fn(), logout: vi.fn(), markAuthenticated: vi.fn(), finishRestoring: vi.fn() }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/flash-sales/1']}>
          <Routes>
            <Route path="/flash-sales/:id" element={<FlashSaleDetailPage />} />
            <Route path="/purchase-requests/:requestId" element={<div>status page</div>} />
            <Route path="/login" element={<div>login page</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </AuthContext.Provider>
  )
}

describe('FlashSaleDetailPage purchase button', () => {
  it('redirects to /login when not authenticated', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    const createSpy = vi.spyOn(purchaseApi, 'createPurchaseRequest')
    renderPage(false)

    await waitFor(() => screen.getByRole('button', { name: /搶購/ }))
    fireEvent.click(screen.getByRole('button', { name: /搶購/ }))

    await waitFor(() => expect(screen.getByText('login page')).toBeInTheDocument())
    expect(createSpy).not.toHaveBeenCalled()
  })

  it('navigates to the status page after a successful purchase request, defaulting quantity to 1', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    vi.spyOn(purchaseApi, 'createPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'PENDING', orderId: null })
    renderPage(true)

    await waitFor(() => screen.getByRole('button', { name: /搶購/ }))
    fireEvent.click(screen.getByRole('button', { name: /搶購/ }))

    await waitFor(() => expect(screen.getByText('status page')).toBeInTheDocument())
    expect(purchaseApi.createPurchaseRequest).toHaveBeenCalledWith(1, expect.any(String), 1)
  })

  it('sends the chosen quantity when it is within the purchase limit', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    vi.spyOn(purchaseApi, 'createPurchaseRequest').mockResolvedValue({ requestId: 'req-1', status: 'PENDING', orderId: null })
    renderPage(true)

    const quantityInput = await screen.findByLabelText('數量')
    fireEvent.change(quantityInput, { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: /搶購/ }))

    await waitFor(() => expect(purchaseApi.createPurchaseRequest).toHaveBeenCalledWith(1, expect.any(String), 3))
  })

  it('disables the purchase button when quantity is 0', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    renderPage(true)

    const quantityInput = await screen.findByLabelText('數量')
    fireEvent.change(quantityInput, { target: { value: '0' } })

    expect(screen.getByRole('button', { name: /搶購/ })).toBeDisabled()
  })

  it('disables the purchase button when quantity exceeds the purchase limit', async () => {
    vi.spyOn(flashSaleApi, 'getFlashSale').mockResolvedValue(activeSale)
    renderPage(true)

    const quantityInput = await screen.findByLabelText('數量')
    fireEvent.change(quantityInput, { target: { value: '4' } })

    expect(screen.getByRole('button', { name: /搶購/ })).toBeDisabled()
  })
})
