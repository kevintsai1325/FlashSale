import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import * as authApi from './api/authApi'

describe('App', () => {
  it('calls refresh on mount to restore the session after a page reload', async () => {
    const refreshSpy = vi.spyOn(authApi, 'refresh').mockRejectedValue(new Error('no session'))
    render(<App />)
    await waitFor(() => expect(refreshSpy).toHaveBeenCalled())
  })
})
