import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import * as realtimeApi from '../../api/realtimeApi'
import { RealtimeDashboardPage } from './RealtimeDashboardPage'

describe('RealtimeDashboardPage', () => {
  function renderWithMetrics(metrics: realtimeApi.RealtimeMetric[]) {
    vi.spyOn(realtimeApi, 'subscribeRealtimeMetrics').mockImplementation(async (onMetric) => {
      metrics.forEach(onMetric)
      // 不 resolve：真的連線會一直開著，提早結束會讓畫面切到「已中斷」。
      await new Promise(() => {})
    })
    render(<MemoryRouter><RealtimeDashboardPage /></MemoryRouter>)
  }

  it('shows the latest window and accumulates the recent history', async () => {
    renderWithMetrics([
      { type: 'gmv', windowEnd: 1_000, amount: 100, orderCount: 2 },
      { type: 'gmv', windowEnd: 2_000, amount: 50, orderCount: 1 },
      { type: 'topProducts', windowEnd: 2_000, items: [{ productId: 7, productName: '限量鍵盤', quantity: 3 }] },
    ])

    // 最新視窗是 windowEnd 最大的那個，不是最後收到的那個。
    expect(await screen.findByText('NT$50.00')).toBeInTheDocument()
    expect(screen.getByText('NT$150.00')).toBeInTheDocument()
    expect(screen.getByText('限量鍵盤')).toBeInTheDocument()
  })

  it('overwrites a window that Flink recomputed after a late event', async () => {
    renderWithMetrics([
      { type: 'gmv', windowEnd: 1_000, amount: 100, orderCount: 2 },
      // 同一個 windowEnd 再來一次（遲到事件觸發重算）——必須覆蓋，不能累加。
      { type: 'gmv', windowEnd: 1_000, amount: 130, orderCount: 3 },
    ])

    await waitFor(() => expect(screen.getAllByText('NT$130.00')).toHaveLength(2))
    expect(screen.queryByText('NT$230.00')).not.toBeInTheDocument()
  })
})
