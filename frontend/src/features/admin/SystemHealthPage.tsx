import { useQuery } from '@tanstack/react-query'
import { getSystemHealth } from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './SystemHealthPage.css'

export function SystemHealthPage() {
  const query = useQuery({
    queryKey: ['admin', 'system-health'],
    queryFn: getSystemHealth,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  })

  return (
    <>
      <AdminNav />
      <main className="system-health-page">
        <header className="system-health-header">
          <div>
            <h1>系統健康度</h1>
            {query.data && <p>最後更新：{new Date(query.data.checkedAt).toLocaleString()}</p>}
          </div>
          <button className="btn" type="button" onClick={() => query.refetch()} disabled={query.isFetching}>
            {query.isFetching ? '更新中…' : '立即更新'}
          </button>
        </header>
        {query.isLoading ? <p>載入健康狀態中…</p> : query.isError || !query.data ? (
          <p role="alert">無法載入系統健康度。</p>
        ) : (
          <>
            <p className={`health-overall health-${query.data.overallStatus.toLowerCase()}`}>
              整體狀態：{query.data.overallStatus}
            </p>
            <ul className="health-grid">
              {query.data.services.map((service) => (
                <li className="health-card" key={service.name}>
                  <h2>{service.name}</h2>
                  <span className={`health-status health-${service.status.toLowerCase()}`}
                    aria-label={`${service.name} 狀態 ${service.status}`}>{service.status}</span>
                  <p>{service.reason}</p>
                </li>
              ))}
            </ul>
          </>
        )}
      </main>
    </>
  )
}
