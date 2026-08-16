import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import * as adminApi from '../../api/adminApi'
import { SystemHealthPage } from './SystemHealthPage'

describe('SystemHealthPage', () => {
  it('renders all eight service states and supports manual refresh', async () => {
    const checkedAt = '2026-08-16T03:00:00Z'
    const getSpy = vi.spyOn(adminApi, 'getSystemHealth').mockResolvedValue({
      overallStatus: 'UP', checkedAt,
      services: ['Backend', 'PostgreSQL', 'Redis', 'RabbitMQ', 'Mailpit', 'Zipkin', 'Frontend', 'Nginx']
        .map((name, index) => ({
          name, status: index === 5 ? 'UNKNOWN' : index === 4 ? 'DOWN' : 'UP', checkedAt,
          reason: index === 4 ? '狀態異常' : '可用',
        })),
    })
    const queryClient = new QueryClient()
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><SystemHealthPage /></MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('Backend')).toBeInTheDocument()
    expect(screen.getByLabelText('Mailpit 狀態 DOWN')).toBeInTheDocument()
    expect(screen.getByLabelText('Zipkin 狀態 UNKNOWN')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(8)
    fireEvent.click(screen.getByRole('button', { name: '立即更新' }))
    await waitFor(() => expect(getSpy).toHaveBeenCalledTimes(2))
  })
})
