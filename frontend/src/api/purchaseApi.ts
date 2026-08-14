import { apiFetch } from './httpClient'

export interface PurchaseRequestView {
  requestId: string
  status: string
  orderId: number | null
}

export async function createPurchaseRequest(flashSaleId: number, idempotencyKey: string): Promise<PurchaseRequestView> {
  const response = await apiFetch(`/api/flash-sales/${flashSaleId}/purchase-requests`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.json()
}

export async function getPurchaseRequest(requestId: string): Promise<PurchaseRequestView> {
  const response = await apiFetch(`/api/purchase-requests/${requestId}`)
  return response.json()
}
