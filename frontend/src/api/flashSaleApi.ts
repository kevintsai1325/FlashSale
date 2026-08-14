import { apiFetch } from './httpClient'

export interface FlashSaleSummary {
  id: number
  productName: string
  salePrice: number
  startsAt: string
  endsAt: string
  status: string
}

export interface FlashSaleDetail extends FlashSaleSummary {
  productDescription: string
  purchaseLimitPerUser: number
  totalQuantity: number
  availableQuantity: number
}

export async function listFlashSales(): Promise<FlashSaleSummary[]> {
  const response = await apiFetch('/api/flash-sales')
  return response.json()
}

export async function getFlashSale(id: number): Promise<FlashSaleDetail> {
  const response = await apiFetch(`/api/flash-sales/${id}`)
  return response.json()
}
