import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getOrder, cancelOrder, submitPayment } from '../../api/orderApi'
import type { OrderDetail } from '../../api/orderApi'

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const id = Number(orderId)
  const queryClient = useQueryClient()
  const queryKey = ['orders', orderId]

  const { data, isLoading, isError } = useQuery({
    queryKey,
    queryFn: () => getOrder(id),
    enabled: !!orderId,
  })

  const updateCache = (updated: OrderDetail) => queryClient.setQueryData(queryKey, updated)

  const payMutation = useMutation({
    mutationFn: (result: 'SUCCESS' | 'FAILURE') => submitPayment(id, result),
    onSuccess: updateCache,
  })
  const cancelMutation = useMutation({
    mutationFn: () => cancelOrder(id),
    onSuccess: updateCache,
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load order.</div>

  return (
    <article>
      <p><Link to="/orders">← 我的訂單</Link></p>
      <h2>{data.orderNo}</h2>
      <p>Status: {data.status}</p>
      <p>Total: ${data.totalAmount.toFixed(2)}</p>
      {data.status === 'PENDING_PAYMENT' && (
        <>
          {data.paymentDueAt && <p>付款期限：{new Date(data.paymentDueAt).toLocaleString()}</p>}
          <button onClick={() => payMutation.mutate('SUCCESS')} disabled={payMutation.isPending}>
            模擬付款成功
          </button>
          <button onClick={() => payMutation.mutate('FAILURE')} disabled={payMutation.isPending}>
            模擬付款失敗
          </button>
          <button onClick={() => cancelMutation.mutate()} disabled={cancelMutation.isPending}>
            取消訂單
          </button>
        </>
      )}
    </article>
  )
}
