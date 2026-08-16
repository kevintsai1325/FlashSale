import { apiFetch } from './httpClient'

export interface OrderItemView {
  productId: number
  productName: string
  quantity: number
  unitPrice: number
}

export interface OrderSummary {
  id: number
  orderNo: string
  totalAmount: number
  status: string
  items: OrderItemView[]
}

export interface OrderDetail extends OrderSummary {
  paymentDueAt: string | null
}

export async function listMyOrders(): Promise<OrderSummary[]> {
  const response = await apiFetch('/api/orders/me')
  return response.json()
}

export async function getOrder(orderId: number): Promise<OrderDetail> {
  const response = await apiFetch(`/api/orders/${orderId}`)
  return response.json()
}

export async function cancelOrder(orderId: number): Promise<OrderDetail> {
  const response = await apiFetch(`/api/orders/${orderId}/cancel`, { method: 'POST' })
  return response.json()
}

export async function submitPayment(orderId: number, result: 'SUCCESS' | 'FAILURE'): Promise<OrderDetail> {
  const response = await apiFetch(`/api/orders/${orderId}/payments`, {
    method: 'POST',
    body: JSON.stringify({ result }),
  })
  return response.json()
}
