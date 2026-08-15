import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import * as authApi from './api/authApi'

describe('App', () => {
  it('renders the FlashSale heading', async () => {
    const refreshSpy = vi.spyOn(authApi, 'refresh').mockRejectedValue(new Error('no session'))
    render(<App />)
    expect(screen.getByRole('heading', { name: /flashsale/i })).toBeInTheDocument()
    // Wait for the initial auth-restore attempt to settle so its isRestoring state update
    // (finishRestoring, called in the effect's .finally) doesn't leak into the next test as an
    // unwrapped act() warning.
    await waitFor(() => expect(refreshSpy).toHaveBeenCalled())
  })

  it('calls refresh on mount to restore the session after a page reload', async () => {
    const refreshSpy = vi.spyOn(authApi, 'refresh').mockRejectedValue(new Error('no session'))
    render(<App />)
    await waitFor(() => expect(refreshSpy).toHaveBeenCalled())
  })
})
