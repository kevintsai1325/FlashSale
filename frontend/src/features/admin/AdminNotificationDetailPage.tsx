import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getNotificationDetail, retryNotification } from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminNotificationDetailPage.css'

export function AdminNotificationDetailPage() {
  const { id } = useParams<{ id: string }>()
  const notificationId = Number(id)
  const queryClient = useQueryClient()

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'notifications', notificationId],
    queryFn: () => getNotificationDetail(notificationId),
    enabled: !!id,
  })

  const retryMutation = useMutation({
    mutationFn: () => retryNotification(notificationId),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin', 'notifications', notificationId] }),
        queryClient.invalidateQueries({ queryKey: ['admin', 'notifications', 'unread-count'] }),
      ]),
  })

  return (
    <>
      <AdminNav />
      <div className="admin-notification-detail">
        <p>
          <Link to="/admin/notifications">← 通知中心</Link>
        </p>
        {isLoading ? (
          <div>Loading…</div>
        ) : isError || !data ? (
          <div role="alert">Failed to load notification.</div>
        ) : (
          <>
            <div className="notification-header">
              <h2>通知 #{data.id}</h2>
              <span className={`notification-status-pill status-${data.status.toLowerCase()}`}>{data.status}</span>
            </div>

            <section className="notification-info-grid">
              <div>
                <span className="notification-info-label">User ID</span>
                <span className="notification-info-value">{data.userId}</span>
              </div>
              <div>
                <span className="notification-info-label">Channel</span>
                <span className="notification-info-value">{data.channel}</span>
              </div>
              <div>
                <span className="notification-info-label">Template</span>
                <span className="notification-info-value">{data.template}</span>
              </div>
              <div>
                <span className="notification-info-label">Recipient</span>
                <span className="notification-info-value">{data.recipient}</span>
              </div>
              <div>
                <span className="notification-info-label">嘗試次數</span>
                <span className="notification-info-value">{data.attemptCount}</span>
              </div>
              <div>
                <span className="notification-info-label">已讀</span>
                <span className="notification-info-value">{data.read ? '已讀' : '未讀'}</span>
              </div>
              <div>
                <span className="notification-info-label">建立時間</span>
                <span className="notification-info-value">{new Date(data.createdAt).toLocaleString()}</span>
              </div>
              <div>
                <span className="notification-info-label">更新時間</span>
                <span className="notification-info-value">{new Date(data.updatedAt).toLocaleString()}</span>
              </div>
            </section>

            {data.lastError && (
              <section>
                <h3>錯誤訊息</h3>
                <p className="notification-last-error">{data.lastError}</p>
              </section>
            )}

            {data.status === 'FAILED' && (
              <button
                type="button"
                className="btn btn-outline-go"
                disabled={retryMutation.isPending}
                onClick={() => retryMutation.mutate()}
              >
                {retryMutation.isPending ? '排程中…' : '重新排程'}
              </button>
            )}

            {retryMutation.isError && <div role="alert">Failed to retry notification.</div>}
          </>
        )}
      </div>
    </>
  )
}
