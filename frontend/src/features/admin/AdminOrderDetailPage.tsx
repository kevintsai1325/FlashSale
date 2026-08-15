import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getAdminOrderDetail } from '../../api/adminApi'
import { StatusPill } from '../../components/StatusPill'
import { AdminNav } from './AdminNav'
import './AdminOrderDetailPage.css'

export function AdminOrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const id = Number(orderId)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'orders', id],
    queryFn: () => getAdminOrderDetail(id),
    enabled: !!orderId,
  })

  return (
    <>
      <AdminNav />
      <div className="admin-order-detail">
        <p>
          <Link to="/admin/orders">← 訂單查詢</Link>
        </p>
        {isLoading ? (
          <div>載入中…</div>
        ) : isError || !data ? (
          <div role="alert">無法載入訂單。</div>
        ) : (
          <>
            <div className="order-header">
              <h2>{data.orderNo}</h2>
              <StatusPill status={data.status} />
            </div>

            <section className="order-info-grid">
              <div>
                <span className="order-info-label">User ID</span>
                <span className="order-info-value">{data.userId}</span>
              </div>
              <div>
                <span className="order-info-label">金額</span>
                <span className="order-info-value">${data.totalAmount.toFixed(2)}</span>
              </div>
              <div>
                <span className="order-info-label">建立時間</span>
                <span className="order-info-value">{new Date(data.createdAt).toLocaleString()}</span>
              </div>
              {data.paymentDueAt && (
                <div>
                  <span className="order-info-label">付款期限</span>
                  <span className="order-info-value">{new Date(data.paymentDueAt).toLocaleString()}</span>
                </div>
              )}
            </section>

            <section>
              <h3>訂單項目</h3>
              <ul className="order-item-list">
                {data.items.map((item) => (
                  <li key={item.productId}>
                    商品 #{item.productId}　x{item.quantity}　單價 ${item.unitPrice.toFixed(2)}
                  </li>
                ))}
              </ul>
            </section>

            <section>
              <h3>關聯搶購請求</h3>
              {data.purchaseRequest ? (
                <p>
                  {data.purchaseRequest.requestId}
                  {' — '}
                  <StatusPill status={data.purchaseRequest.status} />
                </p>
              ) : (
                <p className="muted">無關聯搶購請求</p>
              )}
            </section>

            <section>
              <h3>狀態歷程</h3>
              <ol className="status-history-timeline">
                {data.statusHistory.map((entry, index) => (
                  <li key={index}>
                    <span className="status-history-transition">
                      {entry.fromStatus ?? '(建立)'} → {entry.toStatus}
                    </span>
                    <span className="status-history-time">{new Date(entry.changedAt).toLocaleString()}</span>
                  </li>
                ))}
              </ol>
            </section>

            <section>
              <h3>此時段附近的 API 紀錄</h3>
              <p className="muted related-logs-note">
                以使用者與時間區間概略比對，非精確對應同一次請求，僅供排查參考。
              </p>
              {data.relatedApiLogs.length === 0 ? (
                <p className="muted">此時段附近無相關 API 紀錄</p>
              ) : (
                <ul className="related-logs-list">
                  {data.relatedApiLogs.map((log) => (
                    <li key={log.id}>
                      <span>{new Date(log.occurredAt).toLocaleString()}</span>
                      <span>
                        {log.method} {log.pathTemplate}
                      </span>
                      <span>{log.status}</span>
                      {log.traceId ? (
                        <Link to={`/admin/api-logs?traceId=${encodeURIComponent(log.traceId)}`}>{log.traceId}</Link>
                      ) : (
                        <span className="muted">（無 trace id）</span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
      </div>
    </>
  )
}
