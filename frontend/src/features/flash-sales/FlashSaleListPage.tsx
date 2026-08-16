import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listFlashSales } from '../../api/flashSaleApi'
import { AppNav } from '../../components/AppNav'
import { StatusPill } from '../../components/StatusPill'
import './FlashSaleListPage.css'

const groups = [
  { status: 'ACTIVE', label: '現正開賣' },
  { status: 'SCHEDULED', label: '即將開賣' },
  { status: 'ENDED', label: '已結束' },
] as const

export function FlashSaleListPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['flash-sales'], queryFn: listFlashSales })

  if (isLoading) return <div>載入搶購活動中…</div>
  if (isError) return <div role="alert">無法載入搶購活動列表。</div>

  return (
    <>
      <AppNav />
      {groups.map((group) => {
        const sales = data!.filter((sale) => sale.status === group.status)
        if (sales.length === 0) return null
        return (
          <section className="sale-group" key={group.status}>
            <h2>{group.label}</h2>
            <ul className="sale-grid">
              {sales.map((sale) => (
                <li key={sale.id} className="sale-card">
                  <div className="sale-card-main">
                    <Link className="sale-card-title" to={`/flash-sales/${sale.id}`}>{sale.productName}</Link>
                    <span className="sale-card-price">${sale.salePrice.toFixed(2)}</span>
                    <StatusPill status={sale.status} />
                  </div>
                  <div className="stub-edge" aria-hidden="true">→</div>
                </li>
              ))}
            </ul>
          </section>
        )
      })}
    </>
  )
}
