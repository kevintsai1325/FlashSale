import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminProductsPage } from './AdminProductsPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminProductsPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AdminProductsPage', () => {
  it('lists existing products', async () => {
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([
      { id: 1, name: 'Limited Sneakers', description: 'Only 100 pairs' },
    ])

    renderPage()

    await waitFor(() => expect(screen.getByText('Limited Sneakers')).toBeInTheDocument())
  })

  it('submits the create form and refreshes the list', async () => {
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([])
    const createSpy = vi.spyOn(adminApi, 'createProduct')
      .mockResolvedValue({ id: 2, name: 'New Product', description: 'desc' })

    renderPage()

    fireEvent.change(screen.getByLabelText('名稱'), { target: { value: 'New Product' } })
    fireEvent.change(screen.getByLabelText('說明'), { target: { value: 'desc' } })
    fireEvent.click(screen.getByRole('button', { name: '新增商品' }))

    await waitFor(() => expect(createSpy).toHaveBeenCalledWith('New Product', 'desc'))
  })
})
