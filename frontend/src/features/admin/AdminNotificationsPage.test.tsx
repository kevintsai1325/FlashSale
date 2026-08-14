import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminNotificationsPage } from './AdminNotificationsPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminNotificationsPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const page1: adminApi.PagedResult<adminApi.NotificationView> = {
  content: [
    {
      id: 1,
      userId: 42,
      channel: 'EMAIL',
      template: 'ORDER_CONFIRMED',
      recipient: 'user42@example.com',
      status: 'SENT',
      attemptCount: 1,
      lastError: null,
      read: false,
      createdAt: '2026-08-14T13:00:00Z',
      updatedAt: '2026-08-14T13:00:01Z',
    },
  ],
  totalElements: 1,
  page: 0,
  size: 20,
}

describe('AdminNotificationsPage', () => {
  it('renders fetched notifications and links each row to its detail page', async () => {
    vi.spyOn(adminApi, 'listNotifications').mockResolvedValue(page1)
    renderPage()

    await waitFor(() => expect(screen.getByText('ORDER_CONFIRMED')).toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'ORDER_CONFIRMED' })).toHaveAttribute(
      'href',
      '/admin/notifications/1'
    )
  })

  it('applies filter form values as query params on submit', async () => {
    const spy = vi.spyOn(adminApi, 'listNotifications').mockResolvedValue(page1)
    renderPage()

    await waitFor(() => expect(spy).toHaveBeenCalled())

    fireEvent.change(screen.getByLabelText(/User ID/), { target: { value: '42' } })
    fireEvent.change(screen.getByLabelText(/Channel/), { target: { value: 'EMAIL' } })
    fireEvent.change(screen.getByLabelText(/Status/), { target: { value: 'SENT' } })
    fireEvent.change(screen.getByLabelText(/已讀/), { target: { value: 'false' } })
    fireEvent.click(screen.getByRole('button', { name: /查詢/ }))

    await waitFor(() =>
      expect(spy).toHaveBeenLastCalledWith(
        expect.objectContaining({ userId: 42, channel: 'EMAIL', status: 'SENT', read: false, page: 0 })
      )
    )
  })

  it('shows a batch action bar once a row is checked, and marks selected rows read', async () => {
    vi.spyOn(adminApi, 'listNotifications').mockResolvedValue(page1)
    const readStatusSpy = vi.spyOn(adminApi, 'updateNotificationReadStatus').mockResolvedValue(undefined)
    renderPage()

    await waitFor(() => expect(screen.getByText('ORDER_CONFIRMED')).toBeInTheDocument())
    expect(screen.queryByText(/標記已讀/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('select notification 1'))
    expect(await screen.findByText(/標記已讀/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '標記已讀' }))

    await waitFor(() => expect(readStatusSpy).toHaveBeenCalledWith([1], true))
  })

  it('shows an error message when the batch read-status update fails', async () => {
    vi.spyOn(adminApi, 'listNotifications').mockResolvedValue(page1)
    vi.spyOn(adminApi, 'updateNotificationReadStatus').mockRejectedValue(new Error('boom'))
    renderPage()

    await waitFor(() => expect(screen.getByText('ORDER_CONFIRMED')).toBeInTheDocument())
    fireEvent.click(screen.getByLabelText('select notification 1'))
    fireEvent.click(await screen.findByRole('button', { name: '標記已讀' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Failed to update read status.'))
  })
})
