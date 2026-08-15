import { useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  createColumnHelper,
  createSortedRowModel,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type SortingState,
} from '@tanstack/react-table'
import { listApiLogs, type ApiAuditLogView, type ApiLogFilters } from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './ApiLogsPage.css'

const PAGE_SIZE = 20
const EMPTY_LOGS: ApiAuditLogView[] = []

const features = tableFeatures({ rowSortingFeature, sortedRowModel: createSortedRowModel() })
const columnHelper = createColumnHelper<typeof features, ApiAuditLogView>()

const columns = columnHelper.columns([
  columnHelper.accessor('occurredAt', {
    header: '時間',
    cell: (info) => new Date(info.getValue()).toLocaleString(),
  }),
  columnHelper.accessor('method', { header: 'Method' }),
  columnHelper.accessor('pathTemplate', { header: 'Path' }),
  columnHelper.accessor('status', { header: 'Status' }),
  columnHelper.accessor('userId', {
    header: 'User ID',
    cell: (info) => info.getValue() ?? '—',
  }),
  columnHelper.accessor('durationMs', { header: 'Duration (ms)' }),
  columnHelper.accessor('traceId', {
    header: 'Trace ID',
    cell: (info) => info.getValue() ?? '—',
  }),
  columnHelper.accessor('clientIp', {
    header: 'Client IP',
    cell: (info) => info.getValue() ?? '—',
  }),
  columnHelper.accessor('errorCode', {
    header: 'Error',
    cell: (info) => info.getValue() ?? '—',
  }),
])

interface LogFilterForm {
  method: string
  pathTemplate: string
  status: string
  userId: string
  traceId: string
  from: string
  to: string
}

function emptyFilterForm(traceId: string): LogFilterForm {
  return { method: '', pathTemplate: '', status: '', userId: '', traceId, from: '', to: '' }
}

function toIsoInstant(datetimeLocalValue: string): string | undefined {
  if (!datetimeLocalValue.trim()) return undefined
  const parsed = new Date(datetimeLocalValue)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}

function toQueryFilters(form: LogFilterForm, page: number): ApiLogFilters {
  return {
    method: form.method.trim() || undefined,
    pathTemplate: form.pathTemplate.trim() || undefined,
    status: form.status.trim() ? Number(form.status) : undefined,
    userId: form.userId.trim() ? Number(form.userId) : undefined,
    traceId: form.traceId.trim() || undefined,
    from: toIsoInstant(form.from),
    to: toIsoInstant(form.to),
    page,
    size: PAGE_SIZE,
  }
}

export function ApiLogsPage() {
  const [searchParams] = useSearchParams()
  const initialTraceId = searchParams.get('traceId') ?? ''

  const [form, setForm] = useState<LogFilterForm>(() => emptyFilterForm(initialTraceId))
  const [appliedForm, setAppliedForm] = useState<LogFilterForm>(() => emptyFilterForm(initialTraceId))
  const [page, setPage] = useState(0)
  const [sorting, setSorting] = useState<SortingState>([])

  const queryFilters = useMemo(() => toQueryFilters(appliedForm, page), [appliedForm, page])

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'api-logs', queryFilters],
    queryFn: () => listApiLogs(queryFilters),
  })

  const table = useTable({
    features,
    columns,
    data: data?.content ?? EMPTY_LOGS,
    state: { sorting },
    onSortingChange: setSorting,
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    setAppliedForm(form)
  }

  function handleReset() {
    const cleared = emptyFilterForm('')
    setForm(cleared)
    setAppliedForm(cleared)
    setPage(0)
  }

  const totalElements = data?.totalElements ?? 0
  const hasNextPage = (page + 1) * PAGE_SIZE < totalElements

  return (
    <>
      <AdminNav />
      <div className="api-logs-page">
        <form className="log-filter-form" onSubmit={handleSubmit}>
          <label htmlFor="log-filter-method">
            Method
            <input
              id="log-filter-method"
              value={form.method}
              onChange={(e) => setForm((f) => ({ ...f, method: e.target.value }))}
            />
          </label>
          <label htmlFor="log-filter-path">
            Path
            <input
              id="log-filter-path"
              value={form.pathTemplate}
              onChange={(e) => setForm((f) => ({ ...f, pathTemplate: e.target.value }))}
            />
          </label>
          <label htmlFor="log-filter-status">
            Status
            <input
              id="log-filter-status"
              type="number"
              value={form.status}
              onChange={(e) => setForm((f) => ({ ...f, status: e.target.value }))}
            />
          </label>
          <label htmlFor="log-filter-userId">
            User ID
            <input
              id="log-filter-userId"
              type="number"
              value={form.userId}
              onChange={(e) => setForm((f) => ({ ...f, userId: e.target.value }))}
            />
          </label>
          <label htmlFor="log-filter-traceId">
            Trace ID
            <input
              id="log-filter-traceId"
              value={form.traceId}
              onChange={(e) => setForm((f) => ({ ...f, traceId: e.target.value }))}
            />
          </label>
          <label htmlFor="log-filter-from">
            From
            <input
              id="log-filter-from"
              type="datetime-local"
              value={form.from}
              onChange={(e) => setForm((f) => ({ ...f, from: e.target.value }))}
            />
          </label>
          <label htmlFor="log-filter-to">
            To
            <input
              id="log-filter-to"
              type="datetime-local"
              value={form.to}
              onChange={(e) => setForm((f) => ({ ...f, to: e.target.value }))}
            />
          </label>
          <div className="log-filter-actions">
            <button type="submit" className="btn btn-outline-go">
              查詢
            </button>
            <button type="button" className="btn btn-ghost" onClick={handleReset}>
              重設
            </button>
          </div>
        </form>

        {isLoading ? (
          <div>載入中…</div>
        ) : isError || !data ? (
          <div role="alert">無法載入 API 紀錄。</div>
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
                        無符合條件的紀錄
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
