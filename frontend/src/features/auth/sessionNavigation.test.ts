import { describe, expect, it, vi } from 'vitest'
import { redirectExpiredSession } from './sessionNavigation'

describe('redirectExpiredSession', () => {
  it('clears protected cache and preserves the full return URL', async () => {
    const clear = vi.fn()
    const navigate = vi.fn().mockResolvedValue(undefined)
    const router = {
      state: { location: { pathname: '/orders', search: '?tab=open', hash: '#latest' } },
      navigate,
    }

    await redirectExpiredSession(router, { clear })

    expect(clear).toHaveBeenCalledOnce()
    expect(navigate).toHaveBeenCalledWith('/login', {
      replace: true,
      state: { from: '/orders?tab=open#latest' },
    })
  })
})
