import { useQuery } from '@tanstack/react-query'
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { getDashboardSummary, getDashboardTrends, type TrendPoint } from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminDashboardPage.css'

function formatBucketLabel(bucketStart: string): string {
  return new Date(bucketStart).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function TrendChart({ title, data }: { title: string; data: TrendPoint[] }) {
  return (
    <div className="dashboard-chart-card">
      <h3>{title}</h3>
      <div data-testid="dashboard-trend-chart">
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" />
            <XAxis dataKey="bucketStart" tickFormatter={formatBucketLabel} stroke="var(--muted)" />
            <YAxis stroke="var(--muted)" allowDecimals={false} />
            <Tooltip labelFormatter={(value) => formatBucketLabel(String(value))} />
            <Legend />
            <Line
              type="monotone"
              dataKey="purchaseRequestCount"
              name="搶購請求"
              stroke="var(--stub)"
              dot={false}
            />
            <Line type="monotone" dataKey="orderCount" name="訂單" stroke="var(--go)" dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}

export function AdminDashboardPage() {
  const summaryQuery = useQuery({ queryKey: ['admin', 'dashboard', 'summary'], queryFn: getDashboardSummary })
  const trendsQuery = useQuery({ queryKey: ['admin', 'dashboard', 'trends'], queryFn: getDashboardTrends })

  const isLoading = summaryQuery.isLoading || trendsQuery.isLoading
  const isError = summaryQuery.isError || trendsQuery.isError

  return (
    <>
      <AdminNav />
      {isLoading ? (
        <div>Loading…</div>
      ) : isError || !summaryQuery.data || !trendsQuery.data ? (
        <div role="alert">Failed to load dashboard.</div>
      ) : (
        <div className="admin-dashboard">
          <div className="stat-card-grid">
            <div className="stat-card">
              <span className="stat-card-label">搶購請求總數</span>
              <span className="stat-card-value">{summaryQuery.data.totalPurchaseRequests}</span>
            </div>
            <div className="stat-card">
              <span className="stat-card-label">成功請求數</span>
              <span className="stat-card-value" style={{ color: 'var(--go)' }}>
                {summaryQuery.data.succeededPurchaseRequests}
              </span>
            </div>
            <div className="stat-card">
              <span className="stat-card-label">已付款總額</span>
              <span className="stat-card-value">${summaryQuery.data.totalPaidAmount.toFixed(2)}</span>
            </div>
            {Object.entries(summaryQuery.data.ordersByStatus).map(([status, count]) => (
              <div className="stat-card" key={status}>
                <span className="stat-card-label">{status}</span>
                <span className="stat-card-value">{count}</span>
              </div>
            ))}
          </div>

          <div className="dashboard-charts">
            <TrendChart title="近一小時趨勢" data={trendsQuery.data.lastHour} />
            <TrendChart title="近 24 小時趨勢" data={trendsQuery.data.last24Hours} />
          </div>
        </div>
      )}
    </>
  )
}
