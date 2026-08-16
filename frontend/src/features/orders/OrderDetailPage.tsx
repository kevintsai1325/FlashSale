import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getOrder, cancelOrder, submitPayment } from '../../api/orderApi'
import type { OrderDetail } from '../../api/orderApi'
import { AppNav } from '../../components/AppNav'
import { StatusPill } from '../../components/StatusPill'
import './OrderDetailPage.css'

const TERMINAL_NOTES: Record<string, string> = {
  PAID: '付款成功，訂單已生效',
  CANCELLED: '此訂單已被取消，無需付款',
  EXPIRED: '付款期限已過，訂單不再受理',
}

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

  if (isLoading) return <div>載入中…</div>
  if (isError || !data) return <div role="alert">無法載入訂單。</div>

  return (
    <>
      <AppNav />
      <article className="order-detail">
        <p><Link to="/orders">← 我的訂單</Link></p>
        <h2>{data.orderNo}</h2>
        <StatusPill status={data.status} />
        <div className="amount-strip">
          <span>訂單金額</span>
          <span className="amount-value">${data.totalAmount.toFixed(2)}</span>
        </div>
        {data.status === 'PENDING_PAYMENT' && (
          <>
            {data.paymentDueAt && (
              <p className="countdown-strip">付款期限：{new Date(data.paymentDueAt).toLocaleString()}</p>
            )}
            <div className="order-actions">
              <button className="btn btn-outline-go" onClick={() => payMutation.mutate('SUCCESS')} disabled={payMutation.isPending}>
                模擬付款成功
              </button>
              <button className="btn btn-outline-stop" onClick={() => payMutation.mutate('FAILURE')} disabled={payMutation.isPending}>
                模擬付款失敗
              </button>
              <button className="btn btn-ghost" onClick={() => cancelMutation.mutate()} disabled={cancelMutation.isPending}>
                取消訂單
              </button>
            </div>
          </>
        )}
        {data.status !== 'PENDING_PAYMENT' && (
          <p className="paid-note">
            <span className={`paid-dot ${data.status === 'PAID' ? 'go' : 'stop'}`} aria-hidden="true" />
            {TERMINAL_NOTES[data.status] ?? '訂單已結案'}
          </p>
        )}
      </article>
    </>
  )
}
