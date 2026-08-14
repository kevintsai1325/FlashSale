import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getPurchaseRequest } from '../../api/purchaseApi'

const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'SOLD_OUT', 'REJECTED', 'FAILED'])

const STATUS_MESSAGES: Record<string, string> = {
  PENDING: '搶購處理中，請稍候…',
  SUCCEEDED: '搶購成功！',
  SOLD_OUT: '很抱歉，商品已售完',
  REJECTED: '您已經購買過這個活動的商品',
  FAILED: '訂單建立失敗，系統已自動釋放您的庫存扣減，請重新嘗試搶購',
}

export function PurchaseStatusPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['purchase-requests', requestId],
    queryFn: () => getPurchaseRequest(requestId!),
    enabled: !!requestId,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && TERMINAL_STATUSES.has(status) ? false : 1000
    },
  })

  if (isLoading) return <div>Loading…</div>
  if (isError || !data) return <div role="alert">Failed to load purchase request status.</div>

  return (
    <article>
      <p>{STATUS_MESSAGES[data.status] ?? data.status}</p>
      {data.status === 'SUCCEEDED' && data.orderId != null && (
        <Link to={`/orders/${data.orderId}`}>查看訂單</Link>
      )}
    </article>
  )
}
