import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ApiLogsPage } from './ApiLogsPage'
import * as adminApi from '../../api/adminApi'

function renderPage(initialEntries: string[] = ['/admin/api-logs']) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <ApiLogsPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const page1: adminApi.PagedResult<adminApi.ApiAuditLogView> = {
  content: [
    {
      id: 1,
      occurredAt: '2026-08-14T13:00:00Z',
      method: 'POST',
      pathTemplate: '/api/orders/{id}/payments',
      status: 200,
      userId: 42,
      requestId: 'req-1',
      traceId: 'trace-1',
      durationMs: 12,
      clientIp: '127.0.0.1',
      userAgent: 'vitest',
      errorCode: null,
    },
  ],
  totalElements: 1,
  page: 0,
  size: 20,
}

describe('ApiLogsPage', () => {
  it('renders fetched api log rows', async () => {
    vi.spyOn(adminApi, 'listApiLogs').mockResolvedValue(page1)
    renderPage()

    await waitFor(() => expect(screen.getByText('/api/orders/{id}/payments')).toBeInTheDocument())
    expect(screen.getByText('trace-1')).toBeInTheDocument()
  })

  it('applies filter form values as query params on submit', async () => {
    const spy = vi.spyOn(adminApi, 'listApiLogs').mockResolvedValue(page1)
    renderPage()

    await waitFor(() => expect(spy).toHaveBeenCalled())

    fireEvent.change(screen.getByLabelText(/Method/), { target: { value: 'POST' } })
    fireEvent.change(screen.getByLabelText(/User ID/), { target: { value: '42' } })
    fireEvent.click(screen.getByRole('button', { name: /查詢/ }))

    await waitFor(() =>
      expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ method: 'POST', userId: 42, page: 0 }))
    )
  })

  it('pre-fills the traceId filter from the traceId query param', async () => {
    const spy = vi.spyOn(adminApi, 'listApiLogs').mockResolvedValue(page1)
    renderPage(['/admin/api-logs?traceId=trace-xyz'])

    await waitFor(() => expect(spy).toHaveBeenCalledWith(expect.objectContaining({ traceId: 'trace-xyz' })))
    expect(screen.getByLabelText(/Trace ID/)).toHaveValue('trace-xyz')
  })
})
