import { useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  createColumnHelper,
  createSortedRowModel,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type SortingState,
} from '@tanstack/react-table'
import { listAdminOrders, type AdminOrderSummary } from '../../api/adminApi'
import { StatusPill } from '../../components/StatusPill'
import { AdminNav } from './AdminNav'
import './AdminOrdersPage.css'

const PAGE_SIZE = 20
const EMPTY_ORDERS: AdminOrderSummary[] = []

// Same enum values as the backend's `OrderStatus` (see StatusPill's STATUS_MAP for the canonical
// frontend list of order statuses this codebase renders).
const ORDER_STATUSES = ['PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED'] as const

const features = tableFeatures({ rowSortingFeature, sortedRowModel: createSortedRowModel() })
const columnHelper = createColumnHelper<typeof features, AdminOrderSummary>()

const columns = columnHelper.columns([
  columnHelper.accessor('orderNo', {
    header: '訂單編號',
    cell: (info) => <Link to={`/admin/orders/${info.row.original.id}`}>{info.getValue()}</Link>,
  }),
  columnHelper.accessor('userId', { header: 'User ID' }),
  columnHelper.accessor('totalAmount', {
    header: '金額',
    cell: (info) => `$${info.getValue().toFixed(2)}`,
  }),
  columnHelper.accessor('status', {
    header: '狀態',
    cell: (info) => <StatusPill status={info.getValue()} />,
  }),
  columnHelper.accessor('createdAt', {
    header: '建立時間',
    cell: (info) => new Date(info.getValue()).toLocaleString(),
  }),
])

export function AdminOrdersPage() {
  const [status, setStatus] = useState('')
  const [appliedStatus, setAppliedStatus] = useState('')
  const [page, setPage] = useState(0)
  const [sorting, setSorting] = useState<SortingState>([])

  const queryFilters = useMemo(
    () => ({ status: appliedStatus || undefined, page, size: PAGE_SIZE }),
    [appliedStatus, page]
  )

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'orders', queryFilters],
    queryFn: () => listAdminOrders(queryFilters),
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    setAppliedStatus(status)
  }

  const table = useTable({
    features,
    columns,
    data: data?.content ?? EMPTY_ORDERS,
    state: { sorting },
    onSortingChange: setSorting,
  })

  const totalElements = data?.totalElements ?? 0
  const hasNextPage = (page + 1) * PAGE_SIZE < totalElements

  return (
    <>
      <AdminNav />
      <div className="admin-orders-page">
        <form className="order-filter-form" onSubmit={handleSubmit}>
          <label htmlFor="order-filter-status">
            狀態
            <select
              id="order-filter-status"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              {ORDER_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="btn btn-outline-go">
            查詢
          </button>
        </form>

        {isLoading ? (
          <div>載入中…</div>
        ) : isError || !data ? (
          <div role="alert">無法載入訂單列表。</div>
        ) : (
          <>
            <div className="admin-table-scroll">
              <table className="admin-table">
                <thead>
                  {table.getHeaderGroups().map((headerGroup) => (
                    <tr key={headerGroup.id}>
                      {headerGroup.headers.map((header) => {
                        const sortDir = header.column.getIsSorted()
                        return (
                          <th
                            key={header.id}
                            className={header.column.getCanSort() ? 'sortable' : undefined}
                            onClick={header.column.getToggleSortingHandler()}
                          >
                            {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                            {sortDir === 'asc' ? ' ▲' : sortDir === 'desc' ? ' ▼' : ''}
                          </th>
                        )
                      })}
                    </tr>
                  ))}
                </thead>
                <tbody>
                  {table.getRowModel().rows.length === 0 ? (
                    <tr>
                      <td colSpan={columns.length} className="admin-table-empty">
                        尚無訂單
                      </td>
                    </tr>
                  ) : (
                    table.getRowModel().rows.map((row) => (
                      <tr key={row.id}>
                        {row.getAllCells().map((cell) => (
                          <td key={cell.id}>
                            <table.FlexRender cell={cell} />
                          </td>
                        ))}
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
