import { useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  listNotifications,
  updateNotificationReadStatus,
  type NotificationFilters,
  type NotificationView,
} from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminNotificationsPage.css'

const PAGE_SIZE = 20
const EMPTY_NOTIFICATIONS: NotificationView[] = []

interface NotificationFilterForm {
  userId: string
  channel: string
  status: string
  read: string
}

function emptyFilterForm(): NotificationFilterForm {
  return { userId: '', channel: '', status: '', read: '' }
}

function toQueryFilters(form: NotificationFilterForm, page: number): NotificationFilters {
  return {
    userId: form.userId.trim() ? Number(form.userId) : undefined,
    channel: form.channel || undefined,
    status: form.status || undefined,
    read: form.read === '' ? undefined : form.read === 'true',
    page,
    size: PAGE_SIZE,
  }
}

export function AdminNotificationsPage() {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<NotificationFilterForm>(emptyFilterForm)
  const [appliedForm, setAppliedForm] = useState<NotificationFilterForm>(emptyFilterForm)
  const [page, setPage] = useState(0)
  const [selectedIds, setSelectedIds] = useState<number[]>([])

  const queryFilters = useMemo(() => toQueryFilters(appliedForm, page), [appliedForm, page])

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'notifications', queryFilters],
    queryFn: () => listNotifications(queryFilters),
  })

  const readStatusMutation = useMutation({
    mutationFn: (read: boolean) => updateNotificationReadStatus(selectedIds, read),
    onSuccess: () => {
      setSelectedIds([])
      return queryClient.invalidateQueries({ queryKey: ['admin', 'notifications'] })
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    setSelectedIds([])
    setAppliedForm(form)
  }

  function handleReset() {
    const cleared = emptyFilterForm()
    setForm(cleared)
    setAppliedForm(cleared)
    setPage(0)
    setSelectedIds([])
  }

  function toggleRow(id: number) {
    setSelectedIds((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]))
  }

  const rows = data?.content ?? EMPTY_NOTIFICATIONS
  const totalElements = data?.totalElements ?? 0
  const hasNextPage = (page + 1) * PAGE_SIZE < totalElements

  return (
    <>
      <AdminNav />
      <div className="admin-notifications-page">
        <form className="notification-filter-form" onSubmit={handleSubmit}>
          <label htmlFor="notif-filter-userId">
            User ID
            <input
              id="notif-filter-userId"
              type="number"
              value={form.userId}
              onChange={(e) => setForm((f) => ({ ...f, userId: e.target.value }))}
            />
          </label>
          <label htmlFor="notif-filter-channel">
            Channel
            <select
              id="notif-filter-channel"
              value={form.channel}
              onChange={(e) => setForm((f) => ({ ...f, channel: e.target.value }))}
            >
              <option value="">全部</option>
              <option value="EMAIL">EMAIL</option>
              <option value="SMS">SMS</option>
              <option value="IN_APP">IN_APP</option>
            </select>
          </label>
          <label htmlFor="notif-filter-status">
            Status
            <select
              id="notif-filter-status"
              value={form.status}
              onChange={(e) => setForm((f) => ({ ...f, status: e.target.value }))}
            >
              <option value="">全部</option>
              <option value="PENDING">PENDING</option>
              <option value="SENT">SENT</option>
              <option value="FAILED">FAILED</option>
            </select>
          </label>
          <label htmlFor="notif-filter-read">
            已讀
            <select
              id="notif-filter-read"
              value={form.read}
              onChange={(e) => setForm((f) => ({ ...f, read: e.target.value }))}
            >
              <option value="">全部</option>
              <option value="true">已讀</option>
              <option value="false">未讀</option>
            </select>
          </label>
          <div className="notification-filter-actions">
            <button type="submit" className="btn btn-outline-go">
              查詢
            </button>
            <button type="button" className="btn btn-ghost" onClick={handleReset}>
              重設
            </button>
          </div>
        </form>

        {selectedIds.length > 0 && (
          <div className="notification-batch-bar">
            <span>已選取 {selectedIds.length} 筆</span>
            <button
              type="button"
              className="btn btn-outline-go"
              disabled={readStatusMutation.isPending}
              onClick={() => readStatusMutation.mutate(true)}
            >
              標記已讀
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={readStatusMutation.isPending}
              onClick={() => readStatusMutation.mutate(false)}
            >
              標記未讀
            </button>
          </div>
        )}

        {readStatusMutation.isError && <div role="alert">Failed to update read status.</div>}

        {isLoading ? (
          <div>Loading…</div>
        ) : isError || !data ? (
          <div role="alert">Failed to load notifications.</div>
        ) : (
          <>
            <div className="admin-table-scroll">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th aria-hidden="true"></th>
                    <th>User ID</th>
                    <th>Channel</th>
                    <th>Template</th>
                    <th>狀態</th>
                    <th>已讀</th>
                    <th>建立時間</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="admin-table-empty">
                        無符合條件的通知
                      </td>
                    </tr>
                  ) : (
                    rows.map((n) => (
                      <tr key={n.id}>
                        <td>
                          <input
                            type="checkbox"
                            aria-label={`select notification ${n.id}`}
                            checked={selectedIds.includes(n.id)}
                            onChange={() => toggleRow(n.id)}
                          />
                        </td>
                        <td>{n.userId}</td>
                        <td>{n.channel}</td>
                        <td>
                          <Link to={`/admin/notifications/${n.id}`}>{n.template}</Link>
                        </td>
                        <td>{n.status}</td>
                        <td>{n.read ? '已讀' : '未讀'}</td>
                        <td>{new Date(n.createdAt).toLocaleString()}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="pager">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
              >
                上一頁
              </button>
              <span>
                第 {page + 1} 頁・共 {totalElements} 筆
              </span>
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setPage((p) => p + 1)}
                disabled={!hasNextPage}
              >
                下一頁
              </button>
            </div>
          </>
        )}
      </div>
    </>
  )
}
