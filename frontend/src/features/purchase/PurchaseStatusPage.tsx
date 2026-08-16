import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getPurchaseRequest } from '../../api/purchaseApi'
import { AppNav } from '../../components/AppNav'
import './PurchaseStatusPage.css'

const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'SOLD_OUT', 'REJECTED', 'FAILED'])
const NON_SUCCESS_TERMINAL_STATUSES = new Set(['SOLD_OUT', 'REJECTED', 'FAILED'])

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

  if (isLoading) return <div>載入中…</div>
  if (isError || !data) return <div role="alert">無法載入搶購請求狀態。</div>

  return (
    <>
      <AppNav />
      <article className="purchase-status-card">
        {data.status === 'PENDING' && <div className="ring spin" role="status" aria-label="處理中" />}
        {data.status === 'SUCCEEDED' && <div className="stamp go" aria-hidden="true">搶購成功</div>}
        {data.status === 'SOLD_OUT' && <div className="stamp stop" aria-hidden="true">已售完</div>}
        {data.status === 'REJECTED' && <div className="stamp stop" aria-hidden="true">未通過</div>}
        {data.status === 'FAILED' && <div className="stamp stop" aria-hidden="true">處理失敗</div>}
        <p className="status-message">{STATUS_MESSAGES[data.status] ?? data.status}</p>
        {data.status === 'PENDING' && requestId && (
          <p className="purchase-request-id">請求編號：<code>{requestId}</code></p>
        )}
        {data.status === 'SUCCEEDED' && data.orderId != null && (
          <Link to={`/orders/${data.orderId}`}>查看訂單</Link>
        )}
        {NON_SUCCESS_TERMINAL_STATUSES.has(data.status) && <Link to="/">回活動列表</Link>}
      </article>
    </>
  )
}
