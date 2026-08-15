import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AdminNav } from './AdminNav'

function renderNav() {
  return render(
    <MemoryRouter>
      <AdminNav />
    </MemoryRouter>
  )
}

describe('AdminNav', () => {
  it('links back to the customer-facing storefront', () => {
    renderNav()

    expect(screen.getByRole('link', { name: '回前台' })).toHaveAttribute('href', '/')
  })
})
