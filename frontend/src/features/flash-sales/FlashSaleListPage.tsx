import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listFlashSales } from '../../api/flashSaleApi'
import { AppNav } from '../../components/AppNav'
import { StatusPill } from '../../components/StatusPill'
import './FlashSaleListPage.css'

export function FlashSaleListPage() {
  const { data, isLoading, isError } = useQuery({ queryKey: ['flash-sales'], queryFn: listFlashSales })

  if (isLoading) return <div>Loading flash sales…</div>
  if (isError) return <div role="alert">Failed to load flash sales.</div>

  return (
    <>
      <AppNav />
      <ul className="sale-grid">
        {data!.map((sale) => (
          <li key={sale.id} className="sale-card">
            <div className="sale-card-main">
              <Link className="sale-card-title" to={`/flash-sales/${sale.id}`}>{sale.productName}</Link>
              <span className="sale-card-price">${sale.salePrice.toFixed(2)}</span>
              <StatusPill status={sale.status} />
            </div>
            <div className="stub-edge" />
          </li>
        ))}
      </ul>
    </>
  )
}
