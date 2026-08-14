import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminNotificationDetailPage } from './AdminNotificationDetailPage'
import * as adminApi from '../../api/adminApi'

function renderPage(initialEntries: string[] = ['/admin/notifications/7']) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/admin/notifications/:id" element={<AdminNotificationDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const failedNotification: adminApi.NotificationView = {
  id: 7,
  userId: 42,
  channel: 'EMAIL',
  template: 'ORDER_CONFIRMED',
  recipient: 'user42@example.com',
  status: 'FAILED',
  attemptCount: 3,
  lastError: 'SMTP timeout',
  read: false,
  createdAt: '2026-08-14T13:00:00Z',
  updatedAt: '2026-08-14T13:05:00Z',
}

describe('AdminNotificationDetailPage', () => {
  it('renders the full notification record', async () => {
    vi.spyOn(adminApi, 'getNotificationDetail').mockResolvedValue(failedNotification)
    renderPage()

    await waitFor(() => expect(screen.getByText('user42@example.com')).toBeInTheDocument())
    expect(screen.getByText('EMAIL')).toBeInTheDocument()
    expect(screen.getByText('ORDER_CONFIRMED')).toBeInTheDocument()
    expect(screen.getByText('SMTP timeout')).toBeInTheDocument()
  })

  it('shows the retry button only when status is FAILED, and calls retry then refetches', async () => {
    const detailSpy = vi
      .spyOn(adminApi, 'getNotificationDetail')
      .mockResolvedValueOnce(failedNotification)
      .mockResolvedValueOnce({ ...failedNotification, status: 'SENT', lastError: null })
    const retrySpy = vi.spyOn(adminApi, 'retryNotification').mockResolvedValue(undefined)

    renderPage()

    await waitFor(() => expect(screen.getByText('user42@example.com')).toBeInTheDocument())
    const retryButton = screen.getByRole('button', { name: '重新排程' })
    fireEvent.click(retryButton)

    await waitFor(() => expect(retrySpy).toHaveBeenCalledWith(7))
    await waitFor(() => expect(detailSpy).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('button', { name: '重新排程' })).not.toBeInTheDocument())
  })

  it('does not show the retry button when status is not FAILED', async () => {
    vi.spyOn(adminApi, 'getNotificationDetail').mockResolvedValue({ ...failedNotification, status: 'SENT' })
    renderPage()

    await waitFor(() => expect(screen.getByText('user42@example.com')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '重新排程' })).not.toBeInTheDocument()
  })

  it('keeps the retry button pending through the follow-up refetch, not just the POST', async () => {
    const refetchDeferred = createDeferred<adminApi.NotificationView>()
    const detailSpy = vi
      .spyOn(adminApi, 'getNotificationDetail')
      .mockResolvedValueOnce(failedNotification)
      .mockReturnValueOnce(refetchDeferred.promise)
    vi.spyOn(adminApi, 'retryNotification').mockResolvedValue(undefined)

    renderPage()

    await waitFor(() => expect(screen.getByText('user42@example.com')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '重新排程' }))

    // the refetch has been triggered (2nd getNotificationDetail call) — the retry POST already
    // resolved by this point, but the button must stay disabled/pending until the refetch settles.
    await waitFor(() => expect(detailSpy).toHaveBeenCalledTimes(2))
    expect(screen.getByRole('button', { name: '排程中…' })).toBeDisabled()

    refetchDeferred.resolve({ ...failedNotification, status: 'SENT', lastError: null })

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /排程中…|重新排程/ })).not.toBeInTheDocument()
    )
  })

  it('shows an error message when the retry request fails', async () => {
    vi.spyOn(adminApi, 'getNotificationDetail').mockResolvedValue(failedNotification)
    vi.spyOn(adminApi, 'retryNotification').mockRejectedValue(new Error('boom'))

    renderPage()

    await waitFor(() => expect(screen.getByText('user42@example.com')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '重新排程' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Failed to retry notification.'))
  })
})
