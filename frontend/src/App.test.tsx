import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the FlashSale heading', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: /flashsale/i })).toBeInTheDocument()
  })
})
